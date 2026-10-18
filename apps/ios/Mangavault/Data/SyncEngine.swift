import Foundation
import MangavaultKit
import Observation

/// Pushes queued local changes, then pulls everything changed on the
/// server since the last pull, merging last-write-wins.
@Observable
@MainActor
final class SyncEngine {
    enum Status: Equatable { case idle, syncing, offline, failed(String) }

    var status: Status = .idle

    @ObservationIgnored private let api: APIClient
    @ObservationIgnored private let store: LocalStore
    @ObservationIgnored private var running = false
    @ObservationIgnored private var again = false
    @ObservationIgnored private var soonTask: Task<Void, Never>?
    /// Called when the server rejects our token.
    @ObservationIgnored var onUnauthorized: (() -> Void)?

    init(api: APIClient, store: LocalStore) {
        self.api = api
        self.store = store
    }

    var isSignedIn: Bool { api.token != nil && store.account != nil }

    /// Sync a moment after a change, coalescing bursts (reading a chapter
    /// writes progress on every page).
    func scheduleSoon() {
        guard isSignedIn else { return }
        soonTask?.cancel()
        soonTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            await self?.sync()
        }
    }

    func sync() async {
        guard isSignedIn else { return }
        if running {
            again = true
            return
        }
        running = true
        status = .syncing
        defer { running = false }
        repeat {
            again = false
            do {
                try await push()
                try await pull()
                store.lastSyncedAt = Date()
                store.save()
                status = .idle
            } catch let e as APIFailure where e.isUnauthorized {
                status = .failed("Signed out")
                onUnauthorized?()
                return
            } catch let e as APIFailure where e.isNetwork {
                status = .offline
                return
            } catch is CancellationError {
                status = .idle
                return
            } catch {
                status = .failed(error.localizedDescription)
                return
            }
        } while again
    }

    // MARK: Push

    private func push() async throws {
        for (id, e) in store.pending.library {
            do {
                if e.deleted {
                    try await api.deleteLibrary(mangaId: id, updatedAt: e.updatedAt)
                } else {
                    try await api.putLibrary(mangaId: id, category: e.category, updatedAt: e.updatedAt)
                }
            } catch let f as APIFailure where f.isNotFound {
                // The title is gone from the catalog; nothing to sync.
            }
            if store.pending.library[id] == e { store.pending.library[id] = nil }
        }

        let progress = Array(store.pending.progress.values)
        for chunk in stride(from: 0, to: progress.count, by: 200).map({ Array(progress[$0..<min($0 + 200, progress.count)]) }) {
            try await api.putProgress(chunk.map(\.putItem))
            for p in chunk where store.pending.progress[p.mangaId] == p { store.pending.progress[p.mangaId] = nil }
        }

        let reads = store.pending.read
        for op in reads {
            for part in stride(from: 0, to: op.chapterIds.count, by: 5000) {
                let ids = Array(op.chapterIds[part..<min(part + 5000, op.chapterIds.count)])
                try await api.putRead(chapterIds: ids, read: op.read, updatedAt: op.updatedAt)
            }
            if let i = store.pending.read.firstIndex(of: op) { store.pending.read.remove(at: i) }
        }

        if store.pending.clearHistory {
            try await api.deleteHistory(mangaId: nil)
            store.pending.clearHistory = false
        }
        for id in store.pending.historyDeletes {
            try? await api.deleteHistory(mangaId: id)
            store.pending.historyDeletes.removeAll { $0 == id }
        }
        store.save()
    }

    // MARK: Pull

    private func pull() async throws {
        let lib = try await api.library(since: SyncCursors.since(store.cursors.library))
        for item in lib.items {
            store.summaries[item.manga.id] = item.manga
            if store.pending.library[item.manga.id] != nil,
                let local = store.library[item.manga.id], ISO.isLater(local.updatedAt, than: item.updatedAt)
            {
                continue
            }
            if let merged = Merge.library(store.library[item.manga.id], item) {
                store.library[item.manga.id] = merged
                if let p = store.pending.library[item.manga.id], !ISO.isLater(p.updatedAt, than: item.updatedAt) {
                    store.pending.library[item.manga.id] = nil
                }
            }
        }
        store.cursors.library = lib.serverTime

        let prog = try await api.progress(since: SyncCursors.since(store.cursors.progress))
        for item in prog.items {
            if let merged = Merge.progress(store.progress[item.mangaId], item) {
                store.progress[item.mangaId] = merged
                if let p = store.pending.progress[item.mangaId], !ISO.isLater(p.updatedAt, than: item.updatedAt) {
                    store.pending.progress[item.mangaId] = nil
                }
            }
        }
        store.cursors.progress = prog.serverTime

        let reads = try await api.readChanges(since: SyncCursors.since(store.cursors.read))
        for item in reads.items {
            if let merged = Merge.read(store.readMarks[item.chapterId], item) {
                store.readMarks[item.chapterId] = merged
            }
        }
        store.cursors.read = reads.serverTime

        let hist = try await api.history(limit: 200)
        let removed = Set(store.pending.historyDeletes)
        for item in hist where !removed.contains(item.manga.id) && !store.pending.clearHistory {
            store.summaries[item.manga.id] = item.manga
            if let merged = Merge.history(store.history[item.manga.id], item) {
                var m = merged
                if m.pageCount == nil, let p = store.progress[m.mangaId], p.chapterId == m.chapterId {
                    m.pageCount = p.pageCount
                }
                store.history[item.manga.id] = m
            }
        }
        store.save()
    }
}
