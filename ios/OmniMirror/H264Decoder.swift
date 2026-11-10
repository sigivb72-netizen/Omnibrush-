import CoreMedia
import Foundation
import VideoToolbox

/// Hardware H.264 decoding with VideoToolbox. Frames go in as Annex-B access units and come
/// out as pixel buffers in decode order, with no reordering delay.
///
/// Not thread-safe: use it from one queue.
final class H264Decoder {
    /// Called synchronously from `decode` with the decoded image and the frame's capture time.
    var onFrame: ((CVPixelBuffer, Int64) -> Void)?

    private var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var waitingForKey = true

    private(set) var lastDecodeMs: Double = 0

    /// Prepares for a new stream. Returns false if the parameter sets are unusable.
    @discardableResult
    func configure(sps: Data, pps: Data) -> Bool {
        invalidate()
        var description: CMVideoFormatDescription?
        let status = sps.withUnsafeBytes { (spsBytes: UnsafeRawBufferPointer) -> OSStatus in
            pps.withUnsafeBytes { (ppsBytes: UnsafeRawBufferPointer) -> OSStatus in
                guard let spsBase = spsBytes.bindMemory(to: UInt8.self).baseAddress,
                      let ppsBase = ppsBytes.bindMemory(to: UInt8.self).baseAddress else { return -1 }
                let pointers: [UnsafePointer<UInt8>] = [spsBase, ppsBase]
                let sizes: [Int] = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &description)
            }
        }
        guard status == noErr, let description else { return false }
        format = description
        waitingForKey = true
        return createSession()
    }

    private func createSession() -> Bool {
        guard let format else { return false }
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey: [String: Any](),
        ]
        var created: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault, formatDescription: format, decoderSpecification: nil,
            imageBufferAttributes: attributes as CFDictionary, outputCallback: nil, decompressionSessionOut: &created)
        guard status == noErr, let created else { return false }
        VTSessionSetProperty(created, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        session = created
        return true
    }

    /// Decodes one access unit found in `payload` starting at `offset`.
    /// Returns false when the frame could not be decoded and a new keyframe is needed.
    func decode(payload: Data, offset: Int, key: Bool, ptsUs: Int64) -> Bool {
        if waitingForKey {
            guard key else { return false }
            waitingForKey = false
        }
        guard let format else { return false }
        if session == nil, !createSession() { return false }
        guard let session, let sample = makeSample(payload: payload, offset: offset, ptsUs: ptsUs, format: format) else {
            waitingForKey = true
            return false
        }

        let started = Clock.nowUs()
        var decodeStatus: OSStatus = noErr
        // Synchronous decode: the handler has run by the time this call returns.
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [], infoFlagsOut: nil) {
            [weak self] status, _, image, _, _ in
            decodeStatus = status
            guard status == noErr, let image, let self else { return }
            self.lastDecodeMs = Double(Clock.nowUs() - started) / 1000
            self.onFrame?(image, ptsUs)
        }
        if status != noErr || decodeStatus != noErr {
            // Typically the session was invalidated while the app was in the background.
            VTDecompressionSessionInvalidate(session)
            self.session = nil
            waitingForKey = true
            return false
        }
        return true
    }

    /// Wraps the frame's picture slices in a sample buffer, converting Annex-B start codes
    /// to the 4-byte length prefixes VideoToolbox expects.
    private func makeSample(payload: Data, offset: Int, ptsUs: Int64, format: CMVideoFormatDescription) -> CMSampleBuffer? {
        let avcc = H264Decoder.avcc(from: payload, offset: offset)
        guard !avcc.isEmpty else { return nil }

        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count, blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil, offsetToData: 0, dataLength: avcc.count, flags: kCMBlockBufferAssureMemoryNowFlag,
            blockBufferOut: &block) == kCMBlockBufferNoErr,
            let block else { return nil }
        let copied = avcc.withUnsafeBytes { bytes -> OSStatus in
            guard let base = bytes.baseAddress else { return -1 }
            return CMBlockBufferReplaceDataBytes(with: base, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count)
        }
        guard copied == kCMBlockBufferNoErr else { return nil }

        var timing = CMSampleTimingInfo(
            duration: .invalid, presentationTimeStamp: CMTime(value: ptsUs, timescale: 1_000_000), decodeTimeStamp: .invalid)
        var size = avcc.count
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1,
            sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1, sampleSizeArray: &size,
            sampleBufferOut: &sample) == noErr else { return nil }
        return sample
    }

    /// Annex-B (00 00 01 start codes) to AVCC (length prefixes), keeping only picture slices (NAL types 1-5).
    static func avcc(from data: Data, offset: Int) -> Data {
        var out = Data(capacity: data.count - offset + 16)
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let bytes = raw.bindMemory(to: UInt8.self)
            let count = bytes.count
            var nalStart = -1
            var i = offset

            func emit(_ start: Int, _ end: Int) {
                guard end > start else { return }
                let type = bytes[start] & 0x1f
                guard type >= 1, type <= 5 else { return }
                out.appendUInt32(UInt32(end - start))
                out.append(UnsafeBufferPointer(rebasing: bytes[start..<end]))
            }

            while i + 2 < count {
                if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                    if nalStart >= 0 {
                        // A 4-byte start code leaves one zero that belongs to the code, not the NAL.
                        emit(nalStart, (i > nalStart && bytes[i - 1] == 0) ? i - 1 : i)
                    }
                    nalStart = i + 3
                    i += 3
                } else {
                    i += 1
                }
            }
            if nalStart >= 0 { emit(nalStart, count) }
        }
        return out
    }

    /// Forget decoding state; the next frame fed in must be a keyframe.
    func resync() {
        waitingForKey = true
    }

    func invalidate() {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
        format = nil
        waitingForKey = true
    }
}
