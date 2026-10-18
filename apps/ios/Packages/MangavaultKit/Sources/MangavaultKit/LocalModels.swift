import Foundation

// What the app keeps on the phone. Everything works without an account;
// sync only merges these with the server, last write wins on `updatedAt`.

public struct LibraryEntry: Codable, Hashable, Sendable {
    public var mangaId: Int
    public var category: String?
    public var addedAt: String
    public var updatedAt: String
    /// A tombstone so the removal syncs; hidden everywhere in the UI.
    public var deleted: Bool

    public init(mangaId: Int, category: String?, addedAt: String, updatedAt: String, deleted: Bool = false) {
        self.mangaId = mangaId
        self.category = category
        self.addedAt = addedAt
        self.updatedAt = updatedAt
        self.deleted = deleted
    }
}

public struct ProgressEntry: Codable, Hashable, Sendable {
    public var mangaId: Int
    public var chapterId: Int
    public var chapterNumber: Double?
    /// Position of the chapter in reading order, when known.
    public var chapterIndex: Int?
    /// 1-based page.
    public var page: Int
    public var pageCount: Int?
    public var completed: Bool
    public var updatedAt: String
    /// Removed from Continue reading (nil in older saves means no). The next
    /// read clears it.
    public var hidden: Bool?

    public init(
        mangaId: Int, chapterId: Int, chapterNumber: Double?, chapterIndex: Int?, page: Int, pageCount: Int?,
        completed: Bool, updatedAt: String, hidden: Bool? = nil
    ) {
        self.mangaId = mangaId
        self.chapterId = chapterId
        self.chapterNumber = chapterNumber
        self.chapterIndex = chapterIndex
        self.page = page
        self.pageCount = pageCount
        self.completed = completed
        self.updatedAt = updatedAt
        self.hidden = hidden
    }

    public var isHidden: Bool { hidden == true }

    public var putItem: ProgressPutItem {
        ProgressPutItem(
            mangaId: mangaId, chapterId: chapterId, page: page, pageCount: pageCount, completed: completed,
            hidden: isHidden, updatedAt: updatedAt)
    }

    /// 0…1 through the current chapter.
    public var chapterFraction: Double {
        guard let n = pageCount, n > 0 else { return 0 }
        return min(1, max(0, Double(page) / Double(n)))
    }
}

public struct ReadMark: Codable, Hashable, Sendable {
    public var chapterId: Int
    public var mangaId: Int
    public var read: Bool
    public var updatedAt: String

    public init(chapterId: Int, mangaId: Int, read: Bool, updatedAt: String) {
        self.chapterId = chapterId
        self.mangaId = mangaId
        self.read = read
        self.updatedAt = updatedAt
    }
}

public struct HistoryEntry: Codable, Hashable, Sendable {
    public var mangaId: Int
    public var chapterId: Int
    public var chapterNumber: Double?
    public var page: Int
    public var pageCount: Int?
    public var readAt: String

    public init(mangaId: Int, chapterId: Int, chapterNumber: Double?, page: Int, pageCount: Int?, readAt: String) {
        self.mangaId = mangaId
        self.chapterId = chapterId
        self.chapterNumber = chapterNumber
        self.page = page
        self.pageCount = pageCount
        self.readAt = readAt
    }
}

public struct ReadOp: Codable, Hashable, Sendable {
    public var chapterIds: [Int]
    public var read: Bool
    public var updatedAt: String

    public init(chapterIds: [Int], read: Bool, updatedAt: String) {
        self.chapterIds = chapterIds
        self.read = read
        self.updatedAt = updatedAt
    }
}

/// Local changes not yet accepted by the server.
public struct PendingOps: Codable, Hashable, Sendable {
    public var library: [Int: LibraryEntry] = [:]
    public var progress: [Int: ProgressEntry] = [:]
    public var read: [ReadOp] = []
    public var historyDeletes: [Int] = []
    public var clearHistory = false

    public init() {}

    public var isEmpty: Bool {
        library.isEmpty && progress.isEmpty && read.isEmpty && historyDeletes.isEmpty && !clearHistory
    }
}

public struct SyncCursors: Codable, Hashable, Sendable {
    public var library: String?
    public var progress: String?
    public var read: String?

    public init() {}

    /// The cursor to send: a week before the last server time, because the
    /// server filters on the client's `updatedAt`, which a device that was
    /// offline can set in the past. Re-pulling overlap is harmless (LWW).
    public static func since(_ cursor: String?) -> String? {
        guard let d = ISO.date(cursor) else { return nil }
        return ISO.string(d.addingTimeInterval(-7 * 86_400))
    }
}

public enum Merge {
    /// Whether a remote value should replace the local one. Ties go to the
    /// server, which then matches what every other device sees.
    public static func remoteWins(local: String?, remote: String) -> Bool {
        guard let local else { return true }
        return !ISO.isLater(local, than: remote)
    }

    public static func library(_ local: LibraryEntry?, _ remote: RemoteLibraryItem) -> LibraryEntry? {
        guard remoteWins(local: local?.updatedAt, remote: remote.updatedAt) else { return nil }
        return LibraryEntry(
            mangaId: remote.manga.id, category: remote.category, addedAt: remote.addedAt,
            updatedAt: remote.updatedAt, deleted: remote.deleted)
    }

    public static func progress(_ local: ProgressEntry?, _ remote: RemoteProgress) -> ProgressEntry? {
        guard remoteWins(local: local?.updatedAt, remote: remote.updatedAt) else { return nil }
        let sameChapter = local?.chapterId == remote.chapterId
        return ProgressEntry(
            mangaId: remote.mangaId, chapterId: remote.chapterId, chapterNumber: remote.chapterNumber,
            chapterIndex: sameChapter ? local?.chapterIndex : nil, page: max(1, remote.page),
            pageCount: remote.pageCount, completed: remote.completed, updatedAt: remote.updatedAt,
            hidden: remote.hidden == true ? true : nil)
    }

    public static func read(_ local: ReadMark?, _ remote: RemoteReadMark) -> ReadMark? {
        guard remoteWins(local: local?.updatedAt, remote: remote.updatedAt) else { return nil }
        return ReadMark(chapterId: remote.chapterId, mangaId: remote.mangaId, read: remote.read, updatedAt: remote.updatedAt)
    }

    public static func history(_ local: HistoryEntry?, _ remote: RemoteHistory) -> HistoryEntry? {
        if let local, ISO.isLater(local.readAt, than: remote.readAt) { return nil }
        return HistoryEntry(
            mangaId: remote.manga.id, chapterId: remote.chapterId, chapterNumber: remote.chapterNumber,
            page: max(1, remote.page), pageCount: local?.chapterId == remote.chapterId ? local?.pageCount : nil,
            readAt: remote.readAt)
    }
}

public enum Reading {
    /// Chapters after the one the reader is on (the current one counts as
    /// started, not unread). No progress: every chapter is unread.
    public static func unread(latest: Double?, progress: ProgressEntry?) -> Int {
        guard let latest else { return 0 }
        guard let p = progress else { return max(0, Int(latest.rounded(.down))) }
        let at = p.chapterNumber ?? 0
        return max(0, Int((latest - at).rounded(.down)))
    }

    /// True when the series has chapters past a finished last read.
    public static func isCaughtUp(latest: Double?, progress: ProgressEntry) -> Bool {
        guard progress.completed else { return false }
        guard let latest, let n = progress.chapterNumber else { return true }
        return n >= latest
    }

    /// Continue-reading order: most recent first, finished series without
    /// anything new and ones the reader removed left out.
    public static func continueList(_ progress: [ProgressEntry], latest: (Int) -> Double?) -> [ProgressEntry] {
        progress
            .filter { !$0.isHidden && !isCaughtUp(latest: latest($0.mangaId), progress: $0) }
            .sorted { ISO.isLater($0.updatedAt, than: $1.updatedAt) }
    }

    /// Whether a chapter counts as read: an explicit mark wins, otherwise
    /// everything before the current chapter (and the current one once
    /// finished).
    public static func isRead(_ chapter: Chapter, mark: ReadMark?, progress: ProgressEntry?) -> Bool {
        if let mark { return mark.read }
        guard let p = progress else { return false }
        if let idx = p.chapterIndex {
            return chapter.index < idx || (p.completed && chapter.index == idx)
        }
        if chapter.id == p.chapterId { return p.completed }
        guard let n = chapter.number, let at = p.chapterNumber else { return false }
        return n < at || (p.completed && n == at)
    }
}

public enum Categories {
    public static let defaults = ["Reading", "Plan to read", "Completed"]

    /// Defaults first (minus any the reader deleted), then custom ones in the
    /// order they were made, then any that only exist on synced entries.
    public static func all(custom: [String], used: [String?], removed: [String] = []) -> [String] {
        var out: [String] = []
        let kept = defaults.filter { !removed.contains($0) }
        for c in kept + custom + used.compactMap({ $0 }) where !c.isEmpty && !out.contains(c) {
            out.append(c)
        }
        return out
    }
}
