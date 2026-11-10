import SwiftUI

/// The pairing screen: phones found on the network, manual connection, and this iPad's own
/// code for when the connection is started from the phone.
struct ConnectView: View {
    @EnvironmentObject private var model: AppModel
    @State private var address = ""
    @State private var code = ""
    @State private var promptSender: FoundSender?
    @State private var promptCode = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("OmniMirror").font(.system(size: 30, weight: .bold))
                    statusLine
                }
                .padding(.top, 24)

                card("Phones nearby") {
                    if model.senders.isEmpty {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Open OmniMirror on your phone and tap Start mirroring.")
                                .foregroundColor(Palette.dim)
                        }
                    } else {
                        ForEach(model.senders) { sender in
                            Button {
                                if let saved = model.savedCode(for: sender) {
                                    model.connect(to: sender, code: saved)
                                } else {
                                    promptCode = ""
                                    promptSender = sender
                                }
                            } label: {
                                HStack {
                                    Image(systemName: "iphone")
                                    Text(sender.name)
                                    Spacer()
                                    Image(systemName: "chevron.right").foregroundColor(Palette.dim)
                                }
                                .padding(.vertical, 12)
                                .padding(.horizontal, 14)
                                .background(field)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                card("Connect manually") {
                    TextField("Phone IP address (optional)", text: $address)
                        .keyboardType(.numbersAndPunctuation)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .padding(12)
                        .background(field)
                    TextField("Code shown on the phone", text: $code)
                        .keyboardType(.numberPad)
                        .padding(12)
                        .background(field)
                    Button {
                        model.connectManually(address: address, code: digits(code))
                    } label: {
                        Text("Connect")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(RoundedRectangle(cornerRadius: 10).fill(Palette.accent))
                            .foregroundColor(.white)
                    }
                    .buttonStyle(.plain)
                    .disabled(digits(code).count != 6)
                    .opacity(digits(code).count == 6 ? 1 : 0.5)
                    Text("Leave the address empty to find the phone by its code.")
                        .font(.footnote)
                        .foregroundColor(Palette.dim)
                }

                card("Or connect from the phone") {
                    Text("In the phone app, pick this iPad and enter this code:")
                        .foregroundColor(Palette.dim)
                    HStack {
                        Text(spaced(model.ownCode))
                            .font(.system(size: 30, weight: .bold, design: .monospaced))
                        Spacer()
                        Button("New code") { model.regenerateOwnCode() }
                            .buttonStyle(.bordered)
                    }
                    if let ip = model.ownAddress, let port = model.listeningPort {
                        Text("This iPad: \(ip)" + (port == Wire.defaultPort ? "" : ":\(String(port))"))
                            .font(.footnote)
                            .foregroundColor(Palette.dim)
                    } else if model.listeningPort == nil {
                        Text("Starting…").font(.footnote).foregroundColor(Palette.dim)
                    }
                }
            }
            .frame(maxWidth: 520)
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
            .frame(maxWidth: .infinity)
        }
        .foregroundColor(.white)
        .alert("Code for \(promptSender?.name ?? "phone")", isPresented: promptBinding) {
            TextField("6-digit code", text: $promptCode).keyboardType(.numberPad)
            Button("Connect") {
                if let sender = promptSender { model.connect(to: sender, code: digits(promptCode)) }
                promptSender = nil
            }
            Button("Cancel", role: .cancel) { promptSender = nil }
        } message: {
            Text("Enter the code shown in the phone app.")
        }
    }

    @ViewBuilder private var statusLine: some View {
        switch model.state.phase {
        case .connecting(let text):
            HStack(spacing: 8) {
                ProgressView()
                Text(text).foregroundColor(Palette.dim)
                Button("Cancel") { model.disconnect() }.font(.subheadline)
            }
        case .failed(let text):
            Text(text).foregroundColor(Palette.bad)
        default:
            Text("Show your Android phone's screen on this iPad.").foregroundColor(Palette.dim)
        }
    }

    private var promptBinding: Binding<Bool> {
        Binding(get: { promptSender != nil }, set: { if !$0 { promptSender = nil } })
    }

    private var field: some View {
        RoundedRectangle(cornerRadius: 10)
            .fill(Color.black.opacity(0.6))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.line, lineWidth: 1))
    }

    private func card<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title.uppercased())
                .font(.caption)
                .tracking(1)
                .foregroundColor(Palette.dim)
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Palette.card)
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.line, lineWidth: 1))
        )
    }

    private func digits(_ text: String) -> String {
        String(text.filter { $0.isNumber }.prefix(6))
    }

    private func spaced(_ code: String) -> String {
        code.count == 6 ? "\(code.prefix(3)) \(code.suffix(3))" : code
    }
}
