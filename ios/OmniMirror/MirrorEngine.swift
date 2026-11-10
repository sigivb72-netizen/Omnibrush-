import CoreVideo
import Foundation
import Network

/// What the UI shows about the mirroring session.
struct EngineState: Equatable {
    enum Phase: Equatable {
        case idle
        case connecting(String)
        /// Paired; the phone has not sent video yet.
        case waiting(sender: String)
        case streaming(sender: String)
        case failed(String)
    }

    var phase: Phase = .idle
    var width = 0
    var height = 0
    var fps = 0
    var latencyMs = 0.0
    var decodeMs = 0.0
    var mbps = 0.0
    var roundTripMs = 0.0
    /// Set when a connection was refused because the code was wrong, so the UI can forget it.
    var wrongCodeFor: String?

    var isActive: Bool {
        switch phase {
        case .waiting, .streaming: return true
        default: return false
        }
    }
}

/// Where to find the sender.
enum SenderTarget {
    case service(FoundSender)
    case host(String, UInt16)
    /// No address: try every device on the subnet until one accepts the code.
    case anyWithCode
}

/// Runs the receiving side: connection, pairing, decoding, display, statistics, reconnection.
/// All of it happens on one serial queue; `onState` is delivered on the main queue.
final class MirrorEngine {
    let display: MirrorDisplayView
    var onState: ((EngineState) -> Void)?

    private let queue = DispatchQueue(label: "omnimirror.engine", qos: .userInteractive)
    private let deviceName: String
    private let decoder = H264Decoder()

    private var state = EngineState()
    private var current: ReceiverSession?
    /// Sessions still connecting or pairing. With `anyWithCode` there is one per subnet host.
    private var candidates: [ReceiverSession] = []
    private var wanted: (target: SenderTarget, code: String, label: String)?
    private var generation = 0
    private var statsTimer: DispatchSourceTimer?

    // Counters for the current one-second statistics window.
    private var frames = 0
    private var bytes = 0
    private var latencies: [Double] = []

    init(display: MirrorDisplayView, deviceName: String) {
        self.display = display
        self.deviceName = deviceName
        decoder.onFrame = { [weak self] image, ptsUs in
            guard let self else { return }
            self.display.show(image, ptsUs: ptsUs)
            self.frames += 1
            if let offset = self.current?.clockOffsetUs {
                self.latencies.append(Double(Clock.nowUs() + offset - ptsUs) / 1000)
            }
            let width = CVPixelBufferGetWidth(image), height = CVPixelBufferGetHeight(image)
            if width != self.state.width || height != self.state.height {
                self.state.width = width
                self.state.height = height
            }
        }
    }

    // MARK: Commands (callable from any thread)

    func connect(to target: SenderTarget, code: String, label: String) {
        queue.async {
            self.tearDown()
            self.wanted = (target, code, label)
            self.dial(reconnecting: false)
        }
    }

    /// A phone connected to this iPad. It becomes the session once it proves it knows `code`.
    func accept(_ connection: NWConnection, code: String) {
        queue.async {
            let session = ReceiverSession(connection: connection, direction: .incoming(code: code), deviceName: self.deviceName, queue: self.queue)
            self.candidates.append(session)
            session.onEvent = { [weak self] session, event in self?.handle(session, event) }
            session.start()
        }
    }

    func disconnect() {
        queue.async {
            self.tearDown()
            self.publish { $0 = EngineState() }
        }
    }

    /// The app came back to the foreground: decoded state is stale, start from a fresh keyframe.
    func resync() {
        queue.async {
            self.decoder.resync()
            self.current?.requestKeyFrame()
        }
    }

    // MARK: Connecting

    private func dial(reconnecting: Bool) {
        guard let wanted else { return }
        generation += 1
        let attempt = generation
        let parameters = ReceiverSession.tcpParameters()
        var connections: [NWConnection] = []
        var timeout: TimeInterval = 5

        switch wanted.target {
        case .service(let sender):
            connections = [NWConnection(to: sender.endpoint, using: parameters)]
        case .host(let host, let port):
            guard let port = NWEndpoint.Port(rawValue: port) else { return }
            connections = [NWConnection(host: NWEndpoint.Host(host), port: port, using: parameters)]
        case .anyWithCode:
            guard let network = LocalNetwork.wifi(), let port = NWEndpoint.Port(rawValue: Wire.defaultPort) else {
                publish { $0.phase = .failed("This iPad is not connected to Wi-Fi.") }
                self.wanted = nil
                return
            }
            connections = network.hosts.map { NWConnection(host: NWEndpoint.Host($0), port: port, using: parameters) }
            timeout = 2.5
        }

        publish { $0.phase = .connecting(reconnecting ? "Reconnecting to \(wanted.label)…" : "Connecting to \(wanted.label)…") }
        for connection in connections {
            let session = ReceiverSession(
                connection: connection, direction: .outgoing(code: wanted.code), deviceName: deviceName, queue: queue, connectTimeout: timeout)
            candidates.append(session)
            session.onEvent = { [weak self] session, event in
                guard let self, attempt == self.generation else { return }
                self.handle(session, event)
            }
            session.start()
        }
    }

    private func handle(_ session: ReceiverSession, _ event: ReceiverSession.Event) {
        switch event {
        case .paired(let senderName):
            // First candidate to pair wins; everything else (including a previous session) goes.
            let losers = candidates.filter { $0 !== session } + (current.map { [$0] } ?? [])
            candidates = []
            current = session
            for loser in losers {
                loser.onEvent = nil
                loser.close()
            }
            if case .anyWithCode? = wanted?.target, case .hostPort(let host, let port) = session.connection.endpoint {
                // Remember where the code was found so reconnecting does not rescan the subnet.
                wanted?.target = .host("\(host)", port.rawValue)
                wanted?.label = senderName
            }
            decoder.invalidate()
            resetCounters()
            startStatsTimer()
            publish { $0.phase = .waiting(sender: senderName); $0.wrongCodeFor = nil }

        case .streamInfo(let info):
            guard session === current else { return }
            if !decoder.configure(sps: info.sps, pps: info.pps) {
                session.requestKeyFrame()
            }

        case .video(let payload, let key, let ptsUs):
            guard session === current else { return }
            bytes += payload.count
            if !decoder.decode(payload: payload, offset: Wire.videoHeaderSize, key: key, ptsUs: ptsUs) {
                session.requestKeyFrame()
            }

        case .closed(let reason, let wrongCode):
            if session === current {
                current = nil
                stopStatsTimer()
                decoder.invalidate()
                if wanted != nil {
                    // We dialled this phone: keep trying until the user disconnects.
                    publish { $0.phase = .connecting("Connection lost. Reconnecting…") }
                    scheduleRedial()
                } else {
                    // The phone dialled us; it will reconnect by itself if it wants to.
                    publish { $0 = EngineState() }
                }
                return
            }
            guard let index = candidates.firstIndex(where: { $0 === session }) else { return }
            candidates.remove(at: index)
            guard current == nil, let wanted else { return }
            if case .anyWithCode = wanted.target {
                if candidates.isEmpty {
                    self.wanted = nil
                    publish { $0.phase = .failed("No phone with that code was found on this network.") }
                }
            } else if wrongCode {
                self.wanted = nil
                publish { $0.phase = .failed("Wrong code. Check the code on the phone."); $0.wrongCodeFor = wanted.label }
            } else if case .connecting(let text) = state.phase, text.contains("Reconnecting") {
                scheduleRedial()
            } else {
                self.wanted = nil
                publish { $0.phase = .failed("Could not connect to \(wanted.label): \(reason)") }
            }
        }
    }

    private func scheduleRedial() {
        let attempt = generation
        queue.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self, attempt == self.generation, self.wanted != nil, self.current == nil else { return }
            self.dial(reconnecting: true)
        }
    }

    private func tearDown() {
        generation += 1
        wanted = nil
        for session in candidates + (current.map { [$0] } ?? []) {
            session.onEvent = nil
            session.close()
        }
        candidates = []
        current = nil
        stopStatsTimer()
        decoder.invalidate()
        DispatchQueue.main.async { self.display.clear() }
    }

    // MARK: Statistics

    private func resetCounters() {
        frames = 0
        bytes = 0
        latencies.removeAll(keepingCapacity: true)
    }

    private func startStatsTimer() {
        stopStatsTimer()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: .seconds(1))
        timer.setEventHandler { [weak self] in self?.reportStats() }
        timer.resume()
        statsTimer = timer
    }

    private func stopStatsTimer() {
        statsTimer?.cancel()
        statsTimer = nil
    }

    private func reportStats() {
        guard let session = current else { return }
        latencies.sort()
        let latency = latencies.isEmpty ? 0 : latencies[latencies.count / 2]
        let fps = frames
        let mbps = Double(bytes) * 8 / 1_000_000
        let decodeMs = decoder.lastDecodeMs
        resetCounters()
        session.sendStats(fps: fps, latencyMs: latency, decoder: "VideoToolbox")
        publish {
            $0.fps = fps
            $0.mbps = mbps
            $0.decodeMs = decodeMs
            $0.roundTripMs = session.roundTripMs
            if latency > 0 { $0.latencyMs = latency }
            if fps > 0, case .waiting(let sender) = $0.phase { $0.phase = .streaming(sender: sender) }
        }
    }

    private func publish(_ change: (inout EngineState) -> Void) {
        change(&state)
        let snapshot = state
        DispatchQueue.main.async { self.onState?(snapshot) }
    }
}
