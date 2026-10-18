import Foundation
import MangavaultKit
import Observation

@Observable
@MainActor
final class SearchModel {
    enum Phase: Equatable { case idle, loading, results }

    var query = ""
    var phase: Phase = .idle
    /// Browsing the catalog with filters and no text ("See all").
    var browsing = false
    var filters = SearchFilters()
    var results: [MangaSummary] = []
    var hasMore = false
    var loadingMore = false
    /// The request is taking a while: the backend is asking the sites live.
    var slow = false
    var failed = false
    var genres: [String] = SearchFilters.fallbackGenres

    @ObservationIgnored weak var store: LocalStore?
    @ObservationIgnored private let api: APIClient
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var page = 1
    @ObservationIgnored private var genresLoaded = false

    init(api: APIClient) {
        self.api = api
    }

    var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    var showsIdle: Bool { phase == .idle && !browsing }

    func loadGenres() async {
        guard !genresLoaded else { return }
        if let g = try? await api.genres(), !g.isEmpty {
            genres = g
            genresLoaded = true
        }
    }

    /// Typing: debounce, then search.
    func queryChanged() {
        task?.cancel()
        if trimmed.isEmpty {
            phase = browsing ? .results : .idle
            if browsing { run(debounce: false) }
            return
        }
        browsing = false
        phase = .loading
        run(debounce: true)
    }

    func submit() {
        guard !trimmed.isEmpty else { return }
        store?.addRecent(trimmed)
        run(debounce: false)
    }

    func use(_ recent: String) {
        query = recent
        browsing = false
        phase = .loading
        run(debounce: false)
    }

    func clear() {
        task?.cancel()
        query = ""
        browsing = false
        phase = .idle
        results = []
    }

    func startBrowse(filters f: SearchFilters) {
        query = ""
        filters = f
        browsing = true
        run(debounce: false)
    }

    func apply(_ f: SearchFilters) {
        filters = f
        if trimmed.isEmpty { browsing = true }
        run(debounce: false)
    }

    func resetFilters() {
        filters = SearchFilters()
        if phase != .idle || browsing { run(debounce: false) }
    }

    func run(debounce: Bool) {
        task?.cancel()
        let text = trimmed
        let filters = self.filters
        phase = .loading
        slow = false
        failed = false
        task = Task {
            if debounce {
                try? await Task.sleep(for: .milliseconds(450))
                if Task.isCancelled { return }
            }
            let slowTimer = Task {
                try? await Task.sleep(for: .seconds(1.5))
                if !Task.isCancelled { self.slow = true }
            }
            defer { slowTimer.cancel() }
            do {
                let r = try await fetch(text, filters, page: 1)
                if Task.isCancelled { return }
                results = r.items
                hasMore = r.hasMore
                page = 1
                phase = .results
                store?.remember(r.items)
            } catch is CancellationError {
            } catch {
                if Task.isCancelled { return }
                results = []
                hasMore = false
                failed = true
                phase = .results
            }
        }
    }

    func loadMore() {
        guard hasMore, !loadingMore, phase == .results else { return }
        loadingMore = true
        let text = trimmed, filters = self.filters, next = page + 1
        Task {
            defer { loadingMore = false }
            guard let r = try? await fetch(text, filters, page: next) else { return }
            let seen = Set(results.map(\.id))
            results += r.items.filter { !seen.contains($0.id) }
            hasMore = r.hasMore
            page = next
        }
    }

    private func fetch(_ text: String, _ f: SearchFilters, page: Int) async throws -> MangaList {
        if text.isEmpty {
            return try await api.browse(f.query(searching: false, page: page))
        }
        return try await api.search(text, f.query(searching: true, page: page))
    }

    /// For the filter sheet's "Show N titles": a capped count.
    func count(for f: SearchFilters) async -> (Int, Bool)? {
        let text = trimmed
        let q = f.query(searching: !text.isEmpty, page: 1, limit: 100)
        guard let r = try? await (text.isEmpty ? api.browse(q) : api.search(text, q)) else { return nil }
        return (r.items.count, r.hasMore)
    }
}
