import Foundation
import MangavaultKit
import Observation

enum LibraryLayout: String, Codable { case grid, list }

enum LibrarySort: String, Codable, CaseIterable {
    case lastRead = "Last read"
    case lastUpdated = "Last updated"
    case title = "Title"
    case dateAdded = "Date added"
}

struct AppSettings: Codable, Equatable {
    var theme: ThemeKind = .dark
    var libCols = 3
    var libView: LibraryLayout = .grid
    var libSort: LibrarySort = .lastRead
    var libUnread = false
    /// nil: by title type.
    var readerDefault: ReaderMode?
    var readerBackground: ReaderBackground = .black
    var fit: ReaderFit = .width
    var keepScreenOn = true
    var showPageNumber = true
    var invertTaps = false
    var pageGap = false
    var dim = true
    var brightness: Double = 100
    var prefetch = 5
    var dataSaver = true
}

struct AccountInfo: Codable, Equatable {
    var user: User
    /// "Apple ID", "Google", "Dev".
    var provider: String
}

/// Everything the app keeps on the phone, saved as one JSON file.
private struct Snapshot: Codable {
    var library: [Int: LibraryEntry] = [:]
    var progress: [Int: ProgressEntry] = [:]
    var readMarks: [Int: ReadMark] = [:]
    var history: [Int: HistoryEntry] = [:]
    var summaries: [Int: MangaSummary] = [:]
    var customCategories: [String] = []
    var recents: [String] = []
    var onboardingDone = false
    var genres: [String] = []
    var settings = AppSettings()
    var readerModes: [Int: ReaderMode] = [:]
    var pending = PendingOps()
    var cursors = SyncCursors()
    var account: AccountInfo?
    var lastSyncedAt: Date?
}

extension KeyedDecodingContainer {
    /// A missing or unreadable field falls back to its default, so adding a
    /// setting in an update never throws away what's saved.
    fileprivate func value<T: Decodable>(_ key: Key, _ fallback: T) -> T {
        ((try? decodeIfPresent(T.self, forKey: key)) ?? nil) ?? fallback
    }
}

extension AppSettings {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings()
        theme = c.value(.theme, d.theme)
        libCols = c.value(.libCols, d.libCols)
        libView = c.value(.libView, d.libView)
        libSort = c.value(.libSort, d.libSort)
        libUnread = c.value(.libUnread, d.libUnread)
        readerDefault = c.value(.readerDefault, d.readerDefault)
        readerBackground = c.value(.readerBackground, d.readerBackground)
        fit = c.value(.fit, d.fit)
        keepScreenOn = c.value(.keepScreenOn, d.keepScreenOn)
        showPageNumber = c.value(.showPageNumber, d.showPageNumber)
        invertTaps = c.value(.invertTaps, d.invertTaps)
        pageGap = c.value(.pageGap, d.pageGap)
        dim = c.value(.dim, d.dim)
        brightness = c.value(.brightness, d.brightness)
        prefetch = c.value(.prefetch, d.prefetch)
        dataSaver = c.value(.dataSaver, d.dataSaver)
    }
}

extension Snapshot {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        library = c.value(.library, [:])
        progress = c.value(.progress, [:])
        readMarks = c.value(.readMarks, [:])
        history = c.value(.history, [:])
        summaries = c.value(.summaries, [:])
        customCategories = c.value(.customCategories, [])
        recents = c.value(.recents, [])
        onboardingDone = c.value(.onboardingDone, false)
        genres = c.value(.genres, [])
        settings = c.value(.settings, AppSettings())
        readerModes = c.value(.readerModes, [:])
        pending = c.value(.pending, PendingOps())
        cursors = c.value(.cursors, SyncCursors())
        account = c.value(.account, nil)
        lastSyncedAt = c.value(.lastSyncedAt, nil)
    }
}

/// Local-first store: favorites, progress, read marks and history work with
/// no account. Every change is queued in `pending` for the next sync.
@Observable
@MainActor
final class LocalStore {
    var library: [Int: LibraryEntry] = [:]
    var progress: [Int: ProgressEntry] = [:]
    var readMarks: [Int: ReadMark] = [:]
    var history: [Int: HistoryEntry] = [:]
    var summaries: [Int: MangaSummary] = [:]
    var customCategories: [String] = []
    var recents: [String] = []
    var onboardingDone = false
    var genres: [String] = []
    var settings = AppSettings() { didSet { if settings != oldValue { save() } } }
    var readerModes: [Int: ReaderMode] = [:]
    var pending = PendingOps()
    var cursors = SyncCursors()
    var account: AccountInfo?
    var lastSyncedAt: Date?

    /// Called after every user change, so sync can push it.
    @ObservationIgnored var onChange: (() -> Void)?
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private let fileURL: URL

    init(fileName: String = "store.json") {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent(fileName)
        load()
    }

    // MARK: Persistence

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
            let s = try? JSONDecoder().decode(Snapshot.self, from: data)
        else { return }
        library = s.library
        progress = s.progress
        readMarks = s.readMarks
        history = s.history
        summaries = s.summaries
        customCategories = s.customCategories
        recents = s.recents
        onboardingDone = s.onboardingDone
        genres = s.genres
        settings = s.settings
        readerModes = s.readerModes
        pending = s.pending
        cursors = s.cursors
        account = s.account
        lastSyncedAt = s.lastSyncedAt
    }

    private var snapshot: Snapshot {
        Snapshot(
            library: library, progress: progress, readMarks: readMarks, history: history, summaries: summaries,
            customCategories: customCategories, recents: recents, onboardingDone: onboardingDone, genres: genres,
            settings: settings, readerModes: readerModes, pending: pending, cursors: cursors, account: account,
            lastSyncedAt: lastSyncedAt)
    }

    /// Debounced write.
    func save() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    func saveNow() {
        let snap = snapshot
        let url = fileURL
        Task.detached(priority: .utility) {
            if let data = try? JSONEncoder().encode(snap) { try? data.write(to: url, options: .atomic) }
        }
    }

    private func changed() {
        save()
        onChange?()
    }

    // MARK: Summaries

    /// Keeps the latest copy of any title the app has seen, so Library and
    /// History show fresh covers and chapter counts.
    func remember(_ items: [MangaSummary]) {
        var changedAny = false
        for m in items where summaries[m.id] != m {
            // Only keep titles the user has a relationship with, plus the
            // one being looked at; the catalog itself isn't cached here.
            if library[m.id] != nil || progress[m.id] != nil || history[m.id] != nil || summaries[m.id] != nil {
                summaries[m.id] = m
                changedAny = true
            }
        }
        if changedAny { save() }
    }

    /// Stores a summary even if the title isn't in Library or History yet.
    func remember(_ m: MangaSummary, force: Bool) {
        if !force { return remember([m]) }
        if summaries[m.id] != m {
            summaries[m.id] = m
            save()
        }
    }

    // MARK: Library

    func isFavorite(_ id: Int) -> Bool { library[id].map { !$0.deleted } ?? false }

    var favorites: [LibraryEntry] { library.values.filter { !$0.deleted } }

    var categories: [String] {
        Categories.all(custom: customCategories, used: favorites.map(\.category))
    }

    func setFavorite(_ manga: MangaSummary, category: String?) {
        let now = ISO.now()
        summaries[manga.id] = manga
        var e = library[manga.id] ?? LibraryEntry(mangaId: manga.id, category: category, addedAt: now, updatedAt: now)
        if e.deleted { e.addedAt = now }
        e.category = category
        e.deleted = false
        e.updatedAt = now
        library[manga.id] = e
        pending.library[manga.id] = e
        changed()
    }

    func removeFavorite(_ id: Int) {
        guard var e = library[id], !e.deleted else { return }
        e.deleted = true
        e.updatedAt = ISO.now()
        library[id] = e
        pending.library[id] = e
        changed()
    }

    func addCategory(_ name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty, !categories.contains(n) else { return }
        customCategories.append(n)
        save()
    }

    // MARK: Progress, read marks, history

    func readerMode(for manga: MangaSummary?) -> ReaderMode {
        if let id = manga?.id, let m = readerModes[id] { return m }
        return settings.readerDefault ?? ReaderMode.defaultMode(forType: manga?.kind)
    }

    func recordProgress(manga: MangaSummary, chapter: Chapter, page: Int, pageCount: Int) {
        let now = ISO.now()
        let completed = page >= pageCount && pageCount > 0
        if let old = progress[manga.id], old.chapterId == chapter.id, old.page == page, old.completed == completed {
            return
        }
        summaries[manga.id] = manga
        // Finishing a chapter shouldn't be undone by scrolling back a page.
        let wasDone = progress[manga.id].map { $0.chapterId == chapter.id && $0.completed } ?? false
        let p = ProgressEntry(
            mangaId: manga.id, chapterId: chapter.id, chapterNumber: chapter.number, chapterIndex: chapter.index,
            page: max(1, page), pageCount: pageCount, completed: completed || wasDone, updatedAt: now)
        progress[manga.id] = p
        pending.progress[manga.id] = p
        history[manga.id] = HistoryEntry(
            mangaId: manga.id, chapterId: chapter.id, chapterNumber: chapter.number, page: max(1, page),
            pageCount: pageCount, readAt: now)
        if completed && readMarks[chapter.id]?.read != true {
            readMarks[chapter.id] = ReadMark(chapterId: chapter.id, mangaId: manga.id, read: true, updatedAt: now)
            pending.read.append(ReadOp(chapterIds: [chapter.id], read: true, updatedAt: now))
        }
        changed()
    }

    func setRead(_ chapterIds: [Int], mangaId: Int, read: Bool) {
        guard !chapterIds.isEmpty else { return }
        let now = ISO.now()
        for id in chapterIds {
            readMarks[id] = ReadMark(chapterId: id, mangaId: mangaId, read: read, updatedAt: now)
        }
        pending.read.append(ReadOp(chapterIds: chapterIds, read: read, updatedAt: now))
        changed()
    }

    /// Restores read marks exactly as they were (Undo).
    func restoreReadMarks(_ old: [Int: ReadMark?], mangaId: Int) {
        let now = ISO.now()
        var toRead: [Int] = []
        var toUnread: [Int] = []
        for (id, mark) in old {
            if let mark {
                readMarks[id] = ReadMark(chapterId: id, mangaId: mangaId, read: mark.read, updatedAt: now)
                if mark.read { toRead.append(id) } else { toUnread.append(id) }
            } else {
                readMarks[id] = ReadMark(chapterId: id, mangaId: mangaId, read: false, updatedAt: now)
                toUnread.append(id)
            }
        }
        if !toRead.isEmpty { pending.read.append(ReadOp(chapterIds: toRead, read: true, updatedAt: now)) }
        if !toUnread.isEmpty { pending.read.append(ReadOp(chapterIds: toUnread, read: false, updatedAt: now)) }
        changed()
    }

    func isRead(_ chapter: Chapter) -> Bool {
        Reading.isRead(chapter, mark: readMarks[chapter.id], progress: progress[chapter.mangaId])
    }

    var historyList: [HistoryEntry] {
        history.values.sorted { ISO.isLater($0.readAt, than: $1.readAt) }
    }

    /// Local removal; the server delete is queued by `commitHistoryRemoval`
    /// once the Undo toast is gone.
    func removeHistory(_ id: Int) -> HistoryEntry? {
        let e = history.removeValue(forKey: id)
        save()
        return e
    }

    func restoreHistory(_ entries: [HistoryEntry]) {
        for e in entries { history[e.mangaId] = e }
        save()
    }

    func commitHistoryRemoval(_ ids: [Int]) {
        pending.historyDeletes.append(contentsOf: ids)
        changed()
    }

    func clearHistory() -> [HistoryEntry] {
        let all = Array(history.values)
        history.removeAll()
        save()
        return all
    }

    func commitClearHistory() {
        pending.clearHistory = true
        pending.historyDeletes.removeAll()
        changed()
    }

    // MARK: Search recents, onboarding

    func addRecent(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        recents = Array(([t] + recents.filter { $0.caseInsensitiveCompare(t) != .orderedSame }).prefix(6))
        save()
    }

    func removeRecent(_ q: String) {
        recents.removeAll { $0 == q }
        save()
    }

    func clearRecents() {
        recents = []
        save()
    }

    func finishOnboarding(genres: [String]) {
        self.genres = genres
        onboardingDone = true
        save()
    }

    func setReaderMode(_ mode: ReaderMode, for id: Int) {
        readerModes[id] = mode
        save()
    }

    // MARK: Accounts

    var hasUserData: Bool { !favorites.isEmpty || !progress.isEmpty || !history.isEmpty }

    /// Queue everything on the phone for upload (Merge and sync).
    func enqueueAll() {
        for (id, e) in library { pending.library[id] = e }
        for (id, p) in progress { pending.progress[id] = p }
        let byManga = Dictionary(grouping: readMarks.values, by: \.read)
        for (read, marks) in byManga {
            pending.read.append(ReadOp(chapterIds: marks.map(\.chapterId), read: read, updatedAt: ISO.now()))
        }
        save()
    }

    /// Drop what this phone has (Use account data only).
    func clearUserData() {
        library = [:]
        progress = [:]
        readMarks = [:]
        history = [:]
        pending = PendingOps()
        cursors = SyncCursors()
        save()
    }

    func signedOut() {
        account = nil
        pending = PendingOps()
        cursors = SyncCursors()
        lastSyncedAt = nil
        save()
    }
}
