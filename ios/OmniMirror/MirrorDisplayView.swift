import AVFoundation
import SwiftUI
import UIKit

/// Shows decoded frames the moment they arrive, scaled to fit with the stream's aspect ratio.
/// Backed by AVSampleBufferDisplayLayer, which hands the pixel buffers straight to the
/// compositor without copying them.
final class MirrorDisplayView: UIView {
    override class var layerClass: AnyClass { AVSampleBufferDisplayLayer.self }

    /// Captured once on the main thread so frames can be enqueued from the decoding queue.
    private var displayLayer: AVSampleBufferDisplayLayer!

    private var formatCache: CMVideoFormatDescription?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        displayLayer = layer as? AVSampleBufferDisplayLayer
        displayLayer.videoGravity = .resizeAspect
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    /// Safe to call from the decoding queue.
    func show(_ image: CVPixelBuffer, ptsUs: Int64) {
        if let cached = formatCache, !CMVideoFormatDescriptionMatchesImageBuffer(cached, imageBuffer: image) {
            formatCache = nil
        }
        if formatCache == nil {
            CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: image, formatDescriptionOut: &formatCache)
        }
        guard let format = formatCache else { return }

        var timing = CMSampleTimingInfo(
            duration: .invalid, presentationTimeStamp: CMTime(value: ptsUs, timescale: 1_000_000), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: image, formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample)
        guard let sample else { return }

        // No timeline, no buffering: present this frame at the next display refresh.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true), CFArrayGetCount(attachments) > 0 {
            let dictionary = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                dictionary,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }

        guard let target = displayLayer else { return }
        if target.status == .failed { target.flush() }
        target.enqueue(sample)
    }

    func clear() {
        displayLayer.flushAndRemoveImage()
    }
}

struct MirrorDisplay: UIViewRepresentable {
    let view: MirrorDisplayView

    func makeUIView(context: Context) -> MirrorDisplayView { view }

    func updateUIView(_ uiView: MirrorDisplayView, context: Context) {}
}
