import Foundation
import Network

/// One connection to a sender, from TCP connect through pairing to the video stream.
///
/// The connection can be made either way round. `outgoing`: this iPad dialled the phone and
/// proves it knows the phone's code. `incoming`: the phone dialled this iPad and must prove
/// it knows the iPad's code. After pairing the two are identical.
///
/// Everything here runs on the queue passed to `init`; callbacks are delivered on it too.
final class ReceiverSession {
    enum Direction {
        case outgoing(code: String)
        case incoming(code: String)
    }

    enum Event {
        case paired(senderName: String)
        case streamInfo(StreamInfo)
        /// `payload` is the whole VIDEO message body; the Annex-B access unit starts at `Wire.videoHeaderSize`.
        case video(payload: Data, key: Bool, ptsUs: Int64)
        case closed(reason: String, wrongCode: Bool)
    }

    var onEvent: ((ReceiverSession, Event) -> Void)?

    let connection: NWConnection
    private let queue: DispatchQueue
    private let direction: Direction
    private let deviceName: String
    private let connectTimeout: TimeInterval

    private var myNonce = Wire.newNonce()
    private var connectorNonce = ""
    private var paired = false
    private var closed = false
    private var pingTimer: DispatchSourceTimer?
    private var pingsSent = 0
    private var lastReceiveUs = Clock.nowUs()
    private var clockSamples: [(rtt: Int64, offset: Int64)] = []

    /// Add to this device's clock to get the sender's clock (µs). Nil until the first pong.
    private(set) var clockOffsetUs: Int64?
    private(set) var roundTripMs: Double = 0
    private(set) var senderName = "Android"

    static func tcpParameters() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.connectionTimeout = 5
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.serviceClass = .interactiveVideo
        return parameters
    }

    init(connection: NWConnection, direction: Direction, deviceName: String, queue: DispatchQueue, connectTimeout: TimeInterval = 5) {
        self.connection = connection
        self.direction = direction
        self.deviceName = deviceName
        self.queue = queue
        self.connectTimeout = connectTimeout
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.begin()
            case .failed(let error):
                self.close(reason: error.localizedDescription)
            case .cancelled:
                self.close(reason: "Connection closed")
            default:
                break
            }
        }
        connection.start(queue: queue)
        // NWConnection waits indefinitely for an unreachable host; bound it ourselves.
        queue.asyncAfter(deadline: .now() + connectTimeout) { [weak self] in
            guard let self, !self.paired else { return }
            self.close(reason: "The phone did not answer")
        }
    }

    private func begin() {
        switch direction {
        case .outgoing:
            connection.send(content: Wire.magic, completion: .contentProcessed { _ in })
            send(.hello, Wire.json(["v": Wire.version, "role": "receiver", "name": deviceName, "nonce": myNonce, "client": "ios"]))
            readMessage()
        case .incoming:
            connection.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, _, error in
                guard let self else { return }
                guard let data, data == Wire.magic, error == nil else {
                    self.close(reason: "Not an OmniMirror sender")
                    return
                }
                self.readMessage()
            }
        }
    }

    // MARK: Framing

    private func readMessage() {
        connection.receive(minimumIncompleteLength: 5, maximumLength: 5) { [weak self] header, _, _, error in
            guard let self, !self.closed else { return }
            guard let header, header.count == 5, error == nil else {
                self.close(reason: error?.localizedDescription ?? "The phone closed the connection")
                return
            }
            let length = Int(header.readUInt32(at: 0)) - 1
            let type = header[header.startIndex + 4]
            guard length >= 0, length <= Wire.maxMessage else {
                self.close(reason: "Protocol error")
                return
            }
            if length == 0 {
                self.handle(type, Data())
                self.readMessage()
                return
            }
            self.connection.receive(minimumIncompleteLength: length, maximumLength: length) { [weak self] body, _, _, error in
                guard let self, !self.closed else { return }
                guard let body, body.count == length, error == nil else {
                    self.close(reason: error?.localizedDescription ?? "The phone closed the connection")
                    return
                }
                self.handle(type, body)
                self.readMessage()
            }
        }
    }

    private func send(_ type: Wire.MessageType, _ payload: Data) {
        guard !closed else { return }
        connection.send(content: Wire.frame(type, payload), completion: .contentProcessed { _ in })
    }

    // MARK: Messages

    private func handle(_ rawType: UInt8, _ payload: Data) {
        lastReceiveUs = Clock.nowUs()
        guard let type = Wire.MessageType(rawValue: rawType) else { return } // unknown types are ignored for forward compatibility
        switch type {
        case .video:
            guard paired, payload.count > Wire.videoHeaderSize else { return }
            let key = payload[payload.startIndex] & 1 != 0
            onEvent?(self, .video(payload: payload, key: key, ptsUs: payload.readInt64(at: 1)))
        case .pong:
            guard payload.count >= 16 else { return }
            handlePong(sentUs: payload.readInt64(at: 0), senderUs: payload.readInt64(at: 8))
        case .streamInfo:
            guard paired, let info = StreamInfo(json: Wire.parseJSON(payload)) else { return }
            onEvent?(self, .streamInfo(info))
        case .challenge:
            guard case .outgoing(let code) = direction, !paired else { return }
            let body = Wire.parseJSON(payload)
            guard body["role"] as? String == "sender", let theirNonce = body["nonce"] as? String else {
                close(reason: "That device is not an OmniMirror sender")
                return
            }
            senderName = body["name"] as? String ?? senderName
            send(.auth, Wire.json(["proof": Wire.proof(code: code, connectorNonce: myNonce, listenerNonce: theirNonce)]))
        case .authResult:
            guard case .outgoing = direction, !paired else { return }
            if Wire.parseJSON(payload)["ok"] as? Bool == true {
                didPair()
            } else {
                close(reason: "Wrong code", wrongCode: true)
            }
        case .hello:
            guard case .incoming = direction, !paired else { return }
            let body = Wire.parseJSON(payload)
            guard body["role"] as? String == "sender", let nonce = body["nonce"] as? String else {
                close(reason: "That device is not an OmniMirror sender")
                return
            }
            connectorNonce = nonce
            senderName = body["name"] as? String ?? senderName
            send(.challenge, Wire.json(["v": Wire.version, "role": "receiver", "name": deviceName, "nonce": myNonce]))
        case .auth:
            guard case .incoming(let code) = direction, !paired, !connectorNonce.isEmpty else { return }
            let proof = (Wire.parseJSON(payload)["proof"] as? String ?? "").lowercased()
            if proof == Wire.proof(code: code, connectorNonce: connectorNonce, listenerNonce: myNonce) {
                send(.authResult, Wire.json(["ok": true]))
                didPair()
            } else {
                send(.authResult, Wire.json(["ok": false, "reason": "wrong code"]))
                queue.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.close(reason: "The phone sent a wrong code", wrongCode: true) }
            }
        case .bye:
            close(reason: "The phone stopped mirroring")
        case .ping, .stats, .requestKey:
            break
        }
    }

    private func didPair() {
        paired = true
        onEvent?(self, .paired(senderName: senderName))
        let timer = DispatchSource.makeTimerSource(queue: queue)
        // A quick burst to lock the clock offset, then a slow keep-alive that tracks drift.
        timer.schedule(deadline: .now(), repeating: .milliseconds(150))
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            // The sender repeats frames and answers pings, so silence means the link is dead.
            if Clock.nowUs() - self.lastReceiveUs > 6_000_000 {
                self.close(reason: "Connection lost")
                return
            }
            var payload = Data(capacity: 8)
            payload.appendInt64(Clock.nowUs())
            self.send(.ping, payload)
            self.pingsSent += 1
            if self.pingsSent == 8 { timer.schedule(deadline: .now() + 1, repeating: .seconds(1)) }
        }
        timer.resume()
        pingTimer = timer
    }

    private func handlePong(sentUs: Int64, senderUs: Int64) {
        let now = Clock.nowUs()
        let rtt = now - sentUs
        guard rtt >= 0 else { return }
        clockSamples.append((rtt, senderUs - (sentUs + now) / 2))
        if clockSamples.count > 30 { clockSamples.removeFirst() }
        // The sample with the shortest round trip has the least queueing error in it.
        if let best = clockSamples.min(by: { $0.rtt < $1.rtt }) {
            clockOffsetUs = best.offset
            roundTripMs = Double(best.rtt) / 1000
        }
    }

    // MARK: Receiver -> sender

    func requestKeyFrame() {
        if paired { send(.requestKey, Data()) }
    }

    func sendStats(fps: Int, latencyMs: Double, decoder: String) {
        if paired { send(.stats, Wire.json(["fps": fps, "latencyMs": (latencyMs * 10).rounded() / 10, "decoder": decoder])) }
    }

    func close(reason: String = "Disconnected", wrongCode: Bool = false) {
        guard !closed else { return }
        if paired { connection.send(content: Wire.frame(.bye, Data()), completion: .idempotent) }
        closed = true
        pingTimer?.cancel()
        pingTimer = nil
        connection.stateUpdateHandler = nil
        connection.cancel()
        onEvent?(self, .closed(reason: reason, wrongCode: wrongCode))
        onEvent = nil
    }
}
