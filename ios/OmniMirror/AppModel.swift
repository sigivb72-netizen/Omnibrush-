import Foundation
import Network
import SwiftUI
import UIKit

/// Main-thread state for the UI: discovered phones, this iPad's own code, and the session.
final class AppModel: ObservableObject {
    @Published private(set) var senders: [FoundSender] = []
    @Published private(set) var state = EngineState()
    @Published private(set) var ownCode: String
    @Published private(set) var ownAddress: String?
    @Published private(set) var listeningPort: UInt16?

    let display = MirrorDisplayView()
    private let engine: MirrorEngine
    private let discovery = Discovery()
    private let defaults = UserDefaults.standard
    private let deviceName = UIDevice.current.name

    init() {
        if let saved = defaults.string(forKey: "ownCode"), saved.count == 6 {
            ownCode = saved
        } else {
            let code = Wire.newCode()
            defaults.set(code, forKey: "ownCode")
            ownCode = code
        }
        engine = MirrorEngine(display: display, deviceName: deviceName)
        engine.onState = { [weak self] state in self?.apply(state) }
        discovery.onSenders = { [weak self] senders in self?.senders = senders }
        discovery.onListening = { [weak self] port in
            self?.listeningPort = port
            self?.ownAddress = LocalNetwork.wifi()?.ip
        }
        discovery.onIncoming = { [weak self] connection in
            guard let self else { return }
            self.engine.accept(connection, code: self.ownCode)
        }
        discovery.start(deviceName: deviceName)
    }

    private func apply(_ new: EngineState) {
        if let name = new.wrongCodeFor { forgetCode(for: name) }
        // Remember a code once it has worked, so the next connection is one tap.
        if case .waiting(let sender) = new.phase, let pending = pendingCode {
            defaults.set(pending, forKey: "code." + sender)
            if let label = pendingLabel { defaults.set(pending, forKey: "code." + label) }
            pendingCode = nil
        }
        UIApplication.shared.isIdleTimerDisabled = new.isActive
        state = new
    }

    private var pendingCode: String?
    private var pendingLabel: String?

    // MARK: Actions

    func savedCode(for sender: FoundSender) -> String? {
        defaults.string(forKey: "code." + sender.name)
    }

    private func forgetCode(for name: String) {
        defaults.removeObject(forKey: "code." + name)
    }

    func connect(to sender: FoundSender, code: String) {
        begin(.service(sender), code: code, label: sender.name)
    }

    /// `address` may be "192.168.1.23", "192.168.1.23:47800", or empty to search by code alone.
    func connectManually(address: String, code: String) {
        let trimmed = address.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty {
            begin(.anyWithCode, code: code, label: "the phone")
            return
        }
        var host = trimmed
        var port = Wire.defaultPort
        let parts = trimmed.split(separator: ":")
        if parts.count == 2, let parsed = UInt16(parts[1]) {
            host = String(parts[0])
            port = parsed
        }
        begin(.host(host, port), code: code, label: host)
    }

    private func begin(_ target: SenderTarget, code: String, label: String) {
        pendingCode = code
        pendingLabel = label
        engine.connect(to: target, code: code, label: label)
    }

    func disconnect() {
        pendingCode = nil
        engine.disconnect()
    }

    func regenerateOwnCode() {
        let code = Wire.newCode()
        defaults.set(code, forKey: "ownCode")
        ownCode = code
    }

    func appBecameActive() {
        ownAddress = LocalNetwork.wifi()?.ip
        engine.resync()
    }
}
