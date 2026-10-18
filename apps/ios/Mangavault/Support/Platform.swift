import Foundation
import MangavaultKit
import Network
import Security
import SwiftUI
import UIKit

/// Build-time settings from Info.plist (filled from the xcconfig, see
/// scripts/configure.sh). Nothing here is hardcoded.
enum AppConfig {
    static var apiDomain: String {
        (Bundle.main.object(forInfoDictionaryKey: "MVAPIDomain") as? String) ?? ""
    }

    static var baseURL: URL {
        APIClient.baseURL(domain: apiDomain) ?? URL(string: "https://api.example.com/v1")!
    }

    static var googleClientId: String? {
        let v = (Bundle.main.object(forInfoDictionaryKey: "MVGoogleClientID") as? String)?
            .trimmingCharacters(in: .whitespaces)
        return (v?.isEmpty ?? true) ? nil : v
    }

    static var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(v) (\(b))"
    }
}

enum Keychain {
    private static let service = "dev.mangavault.session"

    static func set(_ value: String?, for account: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        guard let value else { return }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ account: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
}

/// Online / offline and cellular, for the offline banners and data saver.
@Observable
@MainActor
final class NetworkStatus {
    var isOffline = false
    var isExpensive = false
    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let offline = path.status != .satisfied
            let expensive = path.isExpensive
            Task { @MainActor in
                self?.isOffline = offline
                self?.isExpensive = expensive
            }
        }
        monitor.start(queue: DispatchQueue(label: "mv.network"))
    }
}

/// UIKit presentation from SwiftUI actions (share sheets).
@MainActor
enum Presenter {
    static var top: UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var vc = window?.rootViewController
        while let p = vc?.presentedViewController { vc = p }
        return vc
    }

    static func share(_ items: [Any]) {
        guard let top else { return }
        let vc = UIActivityViewController(activityItems: items, applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = top.view
        top.present(vc, animated: true)
    }
}

/// Re-enables the swipe-back gesture on screens that hide the navigation bar
/// (Details draws its own back button over the cover header).
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}
