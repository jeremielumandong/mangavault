import CryptoKit
import Foundation

/// Google sign-in without the SDK: OAuth 2 authorization code + PKCE for an
/// iOS client id, then the token endpoint returns the id_token we hand to
/// `/v1/auth/google`.
public struct GoogleOAuth: Sendable {
    public let clientId: String
    public let verifier: String
    public let state: String

    public init(clientId: String, verifier: String = GoogleOAuth.randomVerifier(), state: String = UUID().uuidString) {
        self.clientId = clientId
        self.verifier = verifier
        self.state = state
    }

    /// "123-abc.apps.googleusercontent.com" → "com.googleusercontent.apps.123-abc"
    public static func reversedClientId(_ id: String) -> String {
        id.split(separator: ".").reversed().joined(separator: ".")
    }

    public var callbackScheme: String { Self.reversedClientId(clientId) }
    public var redirectURI: String { callbackScheme + ":/oauthredirect" }

    public static func randomVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        for i in bytes.indices { bytes[i] = UInt8.random(in: 0...255) }
        return base64url(Data(bytes))
    }

    public static func challenge(for verifier: String) -> String {
        base64url(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    static func base64url(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public var authorizationURL: URL {
        var c = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        c.queryItems = [
            .init(name: "client_id", value: clientId),
            .init(name: "redirect_uri", value: redirectURI),
            .init(name: "response_type", value: "code"),
            .init(name: "scope", value: "openid email profile"),
            .init(name: "code_challenge", value: Self.challenge(for: verifier)),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "state", value: state),
            .init(name: "prompt", value: "select_account"),
        ]
        return c.url!
    }

    public enum Failure: Error, LocalizedError {
        case cancelled
        case badCallback
        case stateMismatch
        case noIdToken(String)

        public var errorDescription: String? {
            switch self {
            case .cancelled: return "Sign-in was cancelled."
            case .badCallback: return "Google didn't return a sign-in code."
            case .stateMismatch: return "Google's answer didn't match this sign-in."
            case .noIdToken(let m): return "Google sign-in failed: \(m)"
            }
        }
    }

    /// The authorization code from the redirect.
    public func code(from callback: URL) throws -> String {
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if items.first(where: { $0.name == "error" })?.value == "access_denied" { throw Failure.cancelled }
        guard items.first(where: { $0.name == "state" })?.value == state else { throw Failure.stateMismatch }
        guard let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else { throw Failure.badCallback }
        return code
    }

    public func tokenRequest(code: String) -> URLRequest {
        var req = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var c = URLComponents()
        c.queryItems = [
            .init(name: "code", value: code),
            .init(name: "client_id", value: clientId),
            .init(name: "redirect_uri", value: redirectURI),
            .init(name: "grant_type", value: "authorization_code"),
            .init(name: "code_verifier", value: verifier),
        ]
        req.httpBody = Data((c.percentEncodedQuery ?? "").utf8)
        return req
    }

    public func idToken(code: String, session: URLSession = .shared) async throws -> String {
        let (data, _) = try await session.data(for: tokenRequest(code: code))
        struct R: Decodable {
            var id_token: String?
            var error: String?
            var error_description: String?
        }
        let r = try JSONDecoder().decode(R.self, from: data)
        guard let t = r.id_token else { throw Failure.noIdToken(r.error_description ?? r.error ?? "no id_token") }
        return t
    }
}
