import CryptoKit
import Foundation

/// Wire constants and helpers shared with the Android sender. See docs/PROTOCOL.md.
enum Wire {
    static let version = 1
    /// First bytes a native peer sends after connecting, so the listener can tell it from HTTP.
    static let magic = Data("OMM1".utf8)
    static let defaultPort: UInt16 = 47800
    static let serviceType = "_omnimirror._tcp"
    static let maxMessage = 16 * 1024 * 1024
    /// VIDEO payload header: flags(1) + capture time in µs on the sender's clock(8).
    static let videoHeaderSize = 9

    enum MessageType: UInt8 {
        case hello = 0x01
        case challenge = 0x02
        case auth = 0x03
        case authResult = 0x04
        case streamInfo = 0x10
        case video = 0x11
        case ping = 0x20
        case pong = 0x21
        case stats = 0x30
        case requestKey = 0x31
        case bye = 0x40
    }

    /// Native framing: [u32 length][u8 type][payload], length counts type + payload.
    static func frame(_ type: MessageType, _ payload: Data) -> Data {
        var out = Data(capacity: payload.count + 5)
        out.appendUInt32(UInt32(payload.count + 1))
        out.append(type.rawValue)
        out.append(payload)
        return out
    }

    static func json(_ object: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
    }

    static func parseJSON(_ data: Data) -> [String: Any] {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any]) ?? [:]
    }

    /// Proof that the connecting side knows the listener's code, without sending the code.
    static func proof(code: String, connectorNonce: String, listenerNonce: String) -> String {
        let key = SymmetricKey(data: Data(code.utf8))
        let mac = HMAC<SHA256>.authenticationCode(for: Data("\(connectorNonce):\(listenerNonce)".utf8), using: key)
        return mac.map { String(format: "%02x", $0) }.joined()
    }

    static func newNonce() -> String {
        (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }

    static func newCode() -> String {
        String(format: "%06d", Int.random(in: 0..<1_000_000))
    }
}

/// Stream parameters the sender announces before (and whenever it restarts) the video.
struct StreamInfo {
    let width: Int
    let height: Int
    let fps: Int
    let bitrate: Int
    let sps: Data
    let pps: Data

    init?(json: [String: Any]) {
        guard let width = json["width"] as? Int, let height = json["height"] as? Int,
              let spsText = json["sps"] as? String, let ppsText = json["pps"] as? String,
              let sps = Data(base64Encoded: spsText), let pps = Data(base64Encoded: ppsText),
              !sps.isEmpty, !pps.isEmpty
        else { return nil }
        self.width = width
        self.height = height
        self.fps = json["fps"] as? Int ?? 60
        self.bitrate = json["bitrate"] as? Int ?? 0
        self.sps = sps
        self.pps = pps
    }
}

extension Data {
    mutating func appendUInt32(_ value: UInt32) {
        append(UInt8(truncatingIfNeeded: value >> 24))
        append(UInt8(truncatingIfNeeded: value >> 16))
        append(UInt8(truncatingIfNeeded: value >> 8))
        append(UInt8(truncatingIfNeeded: value))
    }

    mutating func appendInt64(_ value: Int64) {
        let bits = UInt64(bitPattern: value)
        for shift in stride(from: 56, through: 0, by: -8) {
            append(UInt8(truncatingIfNeeded: bits >> UInt64(shift)))
        }
    }

    /// Big-endian reads at an offset from the start of this (possibly sliced) Data.
    func readUInt32(at offset: Int) -> UInt32 {
        var value: UInt32 = 0
        for i in 0..<4 { value = (value << 8) | UInt32(self[startIndex + offset + i]) }
        return value
    }

    func readInt64(at offset: Int) -> Int64 {
        var value: UInt64 = 0
        for i in 0..<8 { value = (value << 8) | UInt64(self[startIndex + offset + i]) }
        return Int64(bitPattern: value)
    }
}

enum Clock {
    /// Monotonic microseconds; only ever compared with itself or, via the measured offset, with the sender's clock.
    static func nowUs() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / 1000)
    }
}
