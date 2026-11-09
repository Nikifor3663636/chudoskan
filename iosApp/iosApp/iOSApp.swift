import SwiftUI
import UIKit
import Shared

/// iOS-оболочка: весь интерфейс — общий Compose Multiplatform (shared/), ядро — Rust (core/).
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        Platform_iosKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
