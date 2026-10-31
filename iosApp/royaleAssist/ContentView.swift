import SwiftUI
import UIKit
import Shared

/// Hosts the shared Compose Multiplatform UI (`MainViewController()` in :shared iosMain).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    /// Royal.Night, the app background (also the launch screen colour).
    private static let night = Color(red: 0x0C / 255.0, green: 0x18 / 255.0, blue: 0x30 / 255.0)

    var body: some View {
        ZStack {
            Self.night.ignoresSafeArea()
            // Compose applies the safe-area and keyboard insets itself.
            ComposeView().ignoresSafeArea()
        }
        .preferredColorScheme(.dark)
    }
}
