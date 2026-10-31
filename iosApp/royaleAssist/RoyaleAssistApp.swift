import SwiftUI
import Shared

/// The iOS/iPadOS app: a SwiftUI shell (UIScene lifecycle) around the shared Compose UI.
@main
struct RoyaleAssistApp: App {
    init() {
        // The bubble (picture in picture) and the screen broadcast picker live in Swift.
        IosOverlay.shared.attach(host: PipBubble.shared)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                // royaleassist://lookup?name=..., scan, history, setup, verify (see DeepLink.kt).
                .onOpenURL { url in
                    _ = MainViewControllerKt.handleOpenUrl(url: url.absoluteString)
                }
        }
    }
}
