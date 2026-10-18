import AuthenticationServices
import Foundation
import MangavaultKit
import UIKit

/// Sign in with Apple and Google (OAuth + PKCE through the system browser
/// sheet), exchanging the provider token for our own bearer token.
@MainActor
final class AuthService: NSObject {
    private let api: APIClient
    private var webSession: ASWebAuthenticationSession?

    init(api: APIClient) {
        self.api = api
    }

    func apple(_ credential: ASAuthorizationAppleIDCredential) async throws -> AuthResponse {
        guard let data = credential.identityToken, let token = String(data: data, encoding: .utf8) else {
            throw APIFailure.server(status: 400, code: "bad_request", message: "Apple didn't return an identity token.")
        }
        let name = credential.fullName.flatMap {
            PersonNameComponentsFormatter.localizedString(from: $0, style: .default)
        }
        return try await api.signInApple(identityToken: token, name: (name?.isEmpty ?? true) ? nil : name)
    }

    func google() async throws -> AuthResponse {
        guard let clientId = AppConfig.googleClientId else {
            throw APIFailure.server(
                status: 400, code: "bad_request",
                message: "Google sign-in isn't set up in this build (GOOGLE_IOS_CLIENT_ID).")
        }
        let flow = GoogleOAuth(clientId: clientId)
        let callback: URL = try await withCheckedThrowingContinuation { cont in
            let session = ASWebAuthenticationSession(url: flow.authorizationURL, callbackURLScheme: flow.callbackScheme) {
                url, error in
                if let url {
                    cont.resume(returning: url)
                } else if let e = error as? ASWebAuthenticationSessionError, e.code == .canceledLogin {
                    cont.resume(throwing: GoogleOAuth.Failure.cancelled)
                } else {
                    cont.resume(throwing: error ?? GoogleOAuth.Failure.badCallback)
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            webSession = session
            if !session.start() { cont.resume(throwing: GoogleOAuth.Failure.badCallback) }
        }
        webSession = nil
        let code = try flow.code(from: callback)
        let idToken = try await flow.idToken(code: code)
        return try await api.signInGoogle(idToken: idToken)
    }

    func dev(name: String) async throws -> AuthResponse {
        try await api.signInDev(name: name)
    }
}

extension AuthService: ASWebAuthenticationPresentationContextProviding {
    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            return scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? ASPresentationAnchor()
        }
    }
}
