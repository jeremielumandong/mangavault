import Foundation

public enum APIFailure: Error, Sendable, Equatable, LocalizedError {
    /// The server answered with an error body (or a bare status).
    case server(status: Int, code: String, message: String)
    /// No answer: offline, timed out, DNS, TLS.
    case network(String)
    case decoding(String)

    public var isUpstream: Bool {
        if case .server(_, let code, _) = self { return code == "upstream" }
        return false
    }

    public var isUnauthorized: Bool {
        if case .server(let status, _, _) = self { return status == 401 }
        return false
    }

    public var isNotFound: Bool {
        if case .server(let status, _, _) = self { return status == 404 }
        return false
    }

    public var isNetwork: Bool {
        if case .network = self { return true }
        return false
    }

    public var errorDescription: String? {
        switch self {
        case .server(_, _, let message): return message
        case .network(let m): return m
        case .decoding(let m): return m
        }
    }
}

/// Browse / search parameters for `GET /v1/manga` and `GET /v1/search`.
public struct MangaQuery: Sendable, Equatable {
    public var sort: String?
    public var genres: [String] = []
    public var excludeGenres: [String] = []
    public var status: String?
    public var type: String?
    public var page: Int = 1
    public var limit: Int = 30

    public init(
        sort: String? = nil, genres: [String] = [], excludeGenres: [String] = [], status: String? = nil,
        type: String? = nil, page: Int = 1, limit: Int = 30
    ) {
        self.sort = sort
        self.genres = genres
        self.excludeGenres = excludeGenres
        self.status = status
        self.type = type
        self.page = page
        self.limit = limit
    }

    var items: [URLQueryItem] {
        var q: [URLQueryItem] = []
        if let sort { q.append(.init(name: "sort", value: sort)) }
        q += genres.map { URLQueryItem(name: "genre", value: $0) }
        q += excludeGenres.map { URLQueryItem(name: "excludeGenre", value: $0) }
        if let status { q.append(.init(name: "status", value: status)) }
        if let type { q.append(.init(name: "type", value: type)) }
        q.append(.init(name: "page", value: String(page)))
        q.append(.init(name: "limit", value: String(limit)))
        return q
    }
}

/// Thin async client for the v1 API. Thread-safe; the bearer token can be
/// swapped at any time.
public final class APIClient: @unchecked Sendable {
    public let baseURL: URL
    private let session: URLSession
    private let lock = NSLock()
    private var _token: String?

    public var token: String? {
        get { lock.lock(); defer { lock.unlock() }; return _token }
        set { lock.lock(); _token = newValue; lock.unlock() }
    }

    public init(baseURL: URL, session: URLSession? = nil) {
        self.baseURL = baseURL
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 30
            config.waitsForConnectivity = false
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: config)
        }
    }

    /// `https://<domain>/v1`. A value with a scheme (`http://localhost:8787`)
    /// is used as is, for local development.
    public static func baseURL(domain raw: String) -> URL? {
        var d = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while d.hasSuffix("/") { d.removeLast() }
        guard !d.isEmpty else { return nil }
        let root = d.contains("://") ? d : "https://" + d
        return URL(string: root + "/v1")
    }

    // MARK: Plumbing

    private static let decoder = JSONDecoder()
    private static let encoder = JSONEncoder()

    private func makeRequest(_ method: String, _ path: String, query: [URLQueryItem], body: Data?) throws -> URLRequest {
        var comps = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)
        if !query.isEmpty { comps?.queryItems = query }
        guard let url = comps?.url else { throw APIFailure.network("Bad URL for \(path)") }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return req
    }

    private func send(_ method: String, _ path: String, query: [URLQueryItem] = [], body: Data? = nil) async throws -> Data {
        let req = try makeRequest(method, path, query: query, body: body)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch is CancellationError {
            throw CancellationError()
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            throw APIFailure.network(error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            if let body = try? Self.decoder.decode(ErrorBody.self, from: data) {
                throw APIFailure.server(status: status, code: body.error.code, message: body.error.message)
            }
            throw APIFailure.server(status: status, code: "http_\(status)", message: "HTTP \(status)")
        }
        return data
    }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do {
            return try Self.decoder.decode(T.self, from: data)
        } catch {
            throw APIFailure.decoding(String(describing: error))
        }
    }

    private func get<T: Decodable>(_ path: String, _ query: [URLQueryItem] = []) async throws -> T {
        try decode(T.self, try await send("GET", path, query: query))
    }

    private func body<B: Encodable>(_ b: B) throws -> Data {
        try Self.encoder.encode(b)
    }

    // MARK: Catalog

    public func home() async throws -> HomeFeed { try await get("home") }

    public func genres() async throws -> [String] {
        let r: Items<String> = try await get("genres")
        return r.items
    }

    public func browse(_ q: MangaQuery) async throws -> MangaList { try await get("manga", q.items) }

    public func search(_ text: String, _ q: MangaQuery) async throws -> MangaList {
        try await get("search", [URLQueryItem(name: "q", value: text)] + q.items)
    }

    public func manga(_ id: Int) async throws -> MangaDetail { try await get("manga/\(id)") }

    /// Reading order (ascending index).
    public func chapters(_ mangaId: Int) async throws -> [Chapter] {
        let r: Items<Chapter> = try await get("manga/\(mangaId)/chapters")
        return r.items
    }

    public func pages(_ chapterId: Int) async throws -> ChapterPages { try await get("chapters/\(chapterId)/pages") }

    // MARK: Auth

    public func signInApple(identityToken: String, name: String?) async throws -> AuthResponse {
        struct B: Encodable { var identityToken: String; var name: String? }
        return try decode(AuthResponse.self, try await send("POST", "auth/apple", body: body(B(identityToken: identityToken, name: name))))
    }

    public func signInGoogle(idToken: String) async throws -> AuthResponse {
        struct B: Encodable { var idToken: String }
        return try decode(AuthResponse.self, try await send("POST", "auth/google", body: body(B(idToken: idToken))))
    }

    public func signInDev(name: String) async throws -> AuthResponse {
        struct B: Encodable { var name: String }
        return try decode(AuthResponse.self, try await send("POST", "auth/dev", body: body(B(name: name))))
    }

    /// Single-user backends (AUTH_MODE=open): the owner session, no credentials.
    public func signInOpen() async throws -> AuthResponse {
        struct B: Encodable {}
        return try decode(AuthResponse.self, try await send("POST", "auth/open", body: body(B())))
    }

    public func logout() async throws {
        _ = try await send("POST", "auth/logout")
    }

    public func me() async throws -> User {
        let r: MeResponse = try await get("me")
        return r.user
    }

    // MARK: Sync

    private func sinceQuery(_ since: String?) -> [URLQueryItem] {
        since.map { [URLQueryItem(name: "since", value: $0)] } ?? []
    }

    public func library(since: String?) async throws -> SyncPage<RemoteLibraryItem> {
        try await get("me/library", sinceQuery(since))
    }

    public func putLibrary(mangaId: Int, category: String?, updatedAt: String) async throws {
        _ = try await send("PUT", "me/library/\(mangaId)", body: body(LibraryPutBody(category: category, updatedAt: updatedAt)))
    }

    public func deleteLibrary(mangaId: Int, updatedAt: String) async throws {
        _ = try await send("DELETE", "me/library/\(mangaId)", query: [URLQueryItem(name: "updatedAt", value: updatedAt)])
    }

    public func progress(since: String?) async throws -> SyncPage<RemoteProgress> {
        try await get("me/progress", sinceQuery(since))
    }

    @discardableResult
    public func putProgress(_ items: [ProgressPutItem]) async throws -> Int {
        let r = try decode(AcceptedResponse.self, try await send("PUT", "me/progress", body: body(ProgressPutBody(items: items))))
        return r.accepted
    }

    public func readChapterIds(mangaId: Int) async throws -> [Int] {
        let r: ReadIdsResponse = try await get("me/read", [URLQueryItem(name: "mangaId", value: String(mangaId))])
        return r.chapterIds
    }

    public func readChanges(since: String?) async throws -> SyncPage<RemoteReadMark> {
        try await get("me/read", sinceQuery(since))
    }

    public func putRead(chapterIds: [Int], read: Bool, updatedAt: String) async throws {
        _ = try await send("PUT", "me/read", body: body(ReadPutBody(chapterIds: chapterIds, read: read, updatedAt: updatedAt)))
    }

    public func history(limit: Int = 200, before: String? = nil) async throws -> [RemoteHistory] {
        var q = [URLQueryItem(name: "limit", value: String(limit))]
        if let before { q.append(.init(name: "before", value: before)) }
        let r: Items<RemoteHistory> = try await get("me/history", q)
        return r.items
    }

    /// Removes one series from history, or all of it when `mangaId` is nil.
    public func deleteHistory(mangaId: Int?) async throws {
        _ = try await send("DELETE", mangaId.map { "me/history/\($0)" } ?? "me/history")
    }

    public func continueReading() async throws -> [ContinueItem] {
        let r: Items<ContinueItem> = try await get("me/continue")
        return r.items
    }

    public func updates() async throws -> [UpdateItem] {
        let r: Items<UpdateItem> = try await get("me/updates")
        return r.items
    }
}
