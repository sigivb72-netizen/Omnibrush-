import SwiftUI

@main
struct OmniMirrorApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
                .onChange(of: scenePhase) { phase in
                    if phase == .active { model.appBecameActive() }
                }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if model.state.isActive {
                MirrorView()
            } else {
                ConnectView()
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.state.isActive)
    }
}

enum Palette {
    static let card = Color(red: 0.067, green: 0.075, blue: 0.086)
    static let line = Color(red: 0.149, green: 0.165, blue: 0.188)
    static let dim = Color(red: 0.545, green: 0.569, blue: 0.6)
    static let accent = Color(red: 0.298, green: 0.553, blue: 1.0)
    static let ok = Color(red: 0.243, green: 0.812, blue: 0.557)
    static let bad = Color(red: 1.0, green: 0.36, blue: 0.36)
}
