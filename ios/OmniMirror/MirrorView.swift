import SwiftUI

/// The mirrored screen, edge to edge. Tap to show or hide the status overlay.
struct MirrorView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showOverlay = true
    @State private var hideTask: Task<Void, Never>?

    var body: some View {
        ZStack(alignment: .top) {
            MirrorDisplay(view: model.display)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.2)) { showOverlay.toggle() }
                    if showOverlay { scheduleHide() } else { hideTask?.cancel() }
                }

            if case .waiting(let sender) = model.state.phase {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Connected to \(sender). Waiting for video…").foregroundColor(Palette.dim)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .allowsHitTesting(false)
            }

            if showOverlay {
                HStack(alignment: .top) {
                    HStack(spacing: 8) {
                        Circle().fill(isStreaming ? Palette.ok : Palette.accent).frame(width: 8, height: 8)
                        Text(summary)
                            .font(.system(size: 12, design: .monospaced))
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(pill)

                    Spacer()

                    Button {
                        model.disconnect()
                    } label: {
                        Text("Disconnect")
                            .font(.system(size: 12, weight: .medium))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(pill)
                    }
                    .buttonStyle(.plain)
                }
                .foregroundColor(.white)
                .padding(12)
                .transition(.opacity)
            }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .onAppear { scheduleHide() }
        .onDisappear { hideTask?.cancel() }
    }

    private var isStreaming: Bool {
        if case .streaming = model.state.phase { return true }
        return false
    }

    private var pill: some View {
        RoundedRectangle(cornerRadius: 10)
            .fill(Color.black.opacity(0.72))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.white.opacity(0.1), lineWidth: 1))
    }

    private var summary: String {
        let s = model.state
        switch s.phase {
        case .streaming(let sender):
            var parts = [sender, "\(s.width)×\(s.height)", "\(s.fps) fps"]
            if s.latencyMs > 0 { parts.append("\(Int(s.latencyMs.rounded())) ms") }
            parts.append(String(format: "%.1f Mbps", s.mbps))
            parts.append(String(format: "decode %.1f ms", s.decodeMs))
            return parts.joined(separator: " · ")
        case .waiting(let sender):
            return "\(sender) · waiting for video"
        default:
            return ""
        }
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled {
                withAnimation(.easeInOut(duration: 0.2)) { showOverlay = false }
            }
        }
    }
}
