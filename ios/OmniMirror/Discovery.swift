import Foundation
import Network

/// An Android phone advertising itself as a sender.
struct FoundSender: Identifiable, Equatable {
    let name: String
    let endpoint: NWEndpoint
    var id: String { name }

    static func == (lhs: FoundSender, rhs: FoundSender) -> Bool { lhs.name == rhs.name }
}

/// Bonjour in both directions: lists phones that are ready to mirror, and advertises this
/// iPad so a phone can find it and connect to it instead.
final class Discovery {
    var onSenders: (([FoundSender]) -> Void)?
    var onIncoming: ((NWConnection) -> Void)?
    /// The port the listener ended up on, or nil if it could not start.
    var onListening: ((UInt16?) -> Void)?

    private var browser: NWBrowser?
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "omnimirror.discovery")

    func start(deviceName: String) {
        startBrowsing()
        startListening(deviceName: deviceName, port: Wire.defaultPort)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        listener?.cancel()
        listener = nil
    }

    private func startBrowsing() {
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: Wire.serviceType, domain: nil), using: NWParameters.tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            var senders: [FoundSender] = []
            for result in results {
                guard case .service(let name, _, _, _) = result.endpoint else { continue }
                // Other iPads advertise the same service type with role=receiver; skip those.
                if case .bonjour(let txt) = result.metadata, txt["role"] == "receiver" { continue }
                senders.append(FoundSender(name: name, endpoint: result.endpoint))
            }
            senders.sort { $0.name < $1.name }
            DispatchQueue.main.async { self?.onSenders?(senders) }
        }
        browser.stateUpdateHandler = { [weak self] state in
            // Restart after transient failures (for example Wi-Fi dropping out).
            if case .failed = state {
                self?.queue.asyncAfter(deadline: .now() + 2) {
                    guard let self, self.browser != nil else { return }
                    self.browser?.cancel()
                    self.startBrowsing()
                }
            }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    private func startListening(deviceName: String, port: UInt16) {
        let parameters = ReceiverSession.tcpParameters()
        parameters.allowLocalEndpointReuse = true
        // Prefer the well-known port so "connect by IP" works; fall back to any free port
        // (phones that find us through Bonjour learn the port from the advertisement).
        let created: NWListener?
        if port != 0, let fixed = NWEndpoint.Port(rawValue: port), let onFixedPort = try? NWListener(using: parameters, on: fixed) {
            created = onFixedPort
        } else {
            created = try? NWListener(using: parameters)
        }
        guard let listener = created else {
            DispatchQueue.main.async { self.onListening?(nil) }
            return
        }
        listener.service = NWListener.Service(
            name: deviceName, type: Wire.serviceType, domain: nil,
            txtRecord: NWTXTRecord(["role": "receiver", "v": String(Wire.version)]))
        listener.newConnectionHandler = { [weak self] connection in
            DispatchQueue.main.async { self?.onIncoming?(connection) }
        }
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                let actual = listener.port?.rawValue
                DispatchQueue.main.async { self.onListening?(actual) }
            case .failed:
                listener.cancel()
                if port != 0 {
                    self.queue.async { self.startListening(deviceName: deviceName, port: 0) }
                } else {
                    DispatchQueue.main.async { self.onListening?(nil) }
                }
            default:
                break
            }
        }
        listener.start(queue: queue)
        self.listener = listener
    }
}

/// This device's Wi-Fi address and the other host addresses on its subnet.
enum LocalNetwork {
    struct Address {
        let ip: String
        let hosts: [String]
    }

    static func wifi() -> Address? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }

        var best: (address: UInt32, mask: UInt32)?
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let flags = Int32(entry.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  let addr = entry.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  let mask = entry.pointee.ifa_netmask else { continue }
            let name = String(cString: entry.pointee.ifa_name)
            guard name.hasPrefix("en") else { continue } // en0 is Wi-Fi on iPad
            let address = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let netmask = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            if best == nil || name == "en0" { best = (address, netmask) }
        }
        guard let found = best else { return nil }

        // Scan at most a /24 around our own address so "connect by code" stays quick.
        let mask = max(found.mask, 0xFFFF_FF00)
        let network = found.address & mask
        let size = ~mask
        var hosts: [String] = []
        if size >= 2 {
            for offset in 1..<size {
                let candidate = network + offset
                if candidate != found.address { hosts.append(dotted(candidate)) }
            }
        }
        return Address(ip: dotted(found.address), hosts: hosts)
    }

    private static func dotted(_ value: UInt32) -> String {
        "\((value >> 24) & 0xff).\((value >> 16) & 0xff).\((value >> 8) & 0xff).\(value & 0xff)"
    }
}
