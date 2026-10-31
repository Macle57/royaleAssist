import SwiftUI
import Shared

/// The iOS/iPadOS app: a SwiftUI shell (UIScene lifecycle) around the shared Compose UI.
@main
struct RoyaleAssistApp: App {
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
