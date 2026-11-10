import Shared
import SwiftUI
import UIKit

@main
struct GlossoApp: App {
    var body: some Scene {
        WindowGroup {
            // Compose draws edge to edge and pads itself with the safe area and
            // keyboard insets, as the Android app does with enableEdgeToEdge.
            ComposeView().ignoresSafeArea()
        }
    }
}

/// The whole shared UI (`shared/src/iosMain/.../MainViewController.kt`).
private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(build: IosBuild(
            apiBaseUrl: infoString("GlossoApiBase") ?? "https://api.glossostudio.com",
            apiKey: infoString("GlossoApiKey") ?? "",
            debug: isDebugBuild
        ))
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}

/// Build settings that Info.plist carries from Glosso.xcconfig (and Secrets.xcconfig).
private func infoString(_ key: String) -> String? {
    guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String, !value.isEmpty else {
        return nil
    }
    return value
}

#if DEBUG
private let isDebugBuild = true
#else
private let isDebugBuild = false
#endif
