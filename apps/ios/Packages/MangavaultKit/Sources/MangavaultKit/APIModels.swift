import Foundation

// Models for the v1 API (docs/api.md). Field names match the JSON.

public struct MangaSummary: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var title: String
    public var coverUrl: String?
    public var coverHeaders: [String: String]
    /// `manga` | `manhwa` | `manhua` | `comic`
    public var kind: String?
    /// `ongoing` | `completed` | `hiatus` | `cancelled`
    public var status: String?
    public var latestChapter: Double?
    public var updatedAt: String?

    enum CodingKeys: String, CodingKey {
        case id, title, coverUrl, coverHeaders, kind = "type", status, latestChapter, updatedAt
    }

    public init(
        id: Int, title: String, coverUrl: String? = nil, coverHeaders: [String: String] = [:],
        kind: String? = nil, status: String? = nil, latestChapter: Double? = nil, updatedAt: String? = nil
    ) {
        self.id = id
        self.title = title
        self.coverUrl = coverUrl
        self.coverHeaders = coverHeaders
        self.kind = kind
        self.status = status
        self.latestChapter = latestChapter
        self.updatedAt = updatedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        coverUrl = try c.decodeIfPresent(String.self, forKey: .coverUrl)
        coverHeaders = try c.decodeIfPresent([String: String].self, forKey: .coverHeaders) ?? [:]
        kind = try c.decodeIfPresent(String.self, forKey: .kind)
        status = try c.decodeIfPresent(String.self, forKey: .status)
        latestChapter = try c.decodeIfPresent(Double.self, forKey: .latestChapter)
        updatedAt = try c.decodeIfPresent(String.self, forKey: .updatedAt)
    }

    /// "Manhwa", "Ongoing", … for display.
    public var typeLabel: String { kind.map(Self.capitalized) ?? "Manga" }
    public var statusLabel: String { status.map(Self.capitalized) ?? "Unknown" }

    static func capitalized(_ s: String) -> String {
        s.prefix(1).uppercased() + s.dropFirst()
    }
}

public struct MangaDetail: Codable, Hashable, Sendable {
    public var summary: MangaSummary
    public var altTitles: [String]
    public var authors: [String]
    public var artists: [String]
    public var description: String?
    public var genres: [String]
    public var chapterCount: Int
    public var detailsFetchedAt: String?

    enum CodingKeys: String, CodingKey {
        case altTitles, authors, artists, description, genres, chapterCount, detailsFetchedAt
    }

    public init(
        summary: MangaSummary, altTitles: [String] = [], authors: [String] = [], artists: [String] = [],
        description: String? = nil, genres: [String] = [], chapterCount: Int = 0, detailsFetchedAt: String? = nil
    ) {
        self.summary = summary
        self.altTitles = altTitles
        self.authors = authors
        self.artists = artists
        self.description = description
        self.genres = genres
        self.chapterCount = chapterCount
        self.detailsFetchedAt = detailsFetchedAt
    }

    public init(from decoder: Decoder) throws {
        summary = try MangaSummary(from: decoder)
        let c = try decoder.container(keyedBy: CodingKeys.self)
        altTitles = try c.decodeIfPresent([String].self, forKey: .altTitles) ?? []
        authors = try c.decodeIfPresent([String].self, forKey: .authors) ?? []
        artists = try c.decodeIfPresent([String].self, forKey: .artists) ?? []
        description = try c.decodeIfPresent(String.self, forKey: .description)
        genres = try c.decodeIfPresent([String].self, forKey: .genres) ?? []
        chapterCount = try c.decodeIfPresent(Int.self, forKey: .chapterCount) ?? 0
        detailsFetchedAt = try c.decodeIfPresent(String.self, forKey: .detailsFetchedAt)
    }

    public func encode(to encoder: Encoder) throws {
        try summary.encode(to: encoder)
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(altTitles, forKey: .altTitles)
        try c.encode(authors, forKey: .authors)
        try c.encode(artists, forKey: .artists)
        try c.encodeIfPresent(description, forKey: .description)
        try c.encode(genres, forKey: .genres)
        try c.encode(chapterCount, forKey: .chapterCount)
        try c.encodeIfPresent(detailsFetchedAt, forKey: .detailsFetchedAt)
    }

    /// "Chugong · art by DUBU", or just the authors when they drew it too.
    public var creditLine: String {
        let art = artists.filter { !authors.contains($0) }
        let a = authors.joined(separator: ", ")
        if art.isEmpty { return a }
        if a.isEmpty { return "Art by " + art.joined(separator: ", ") }
        return a + " · art by " + art.joined(separator: ", ")
    }
}

public struct Chapter: Codable, Hashable, Sendable, Identifiable {
    public var id: Int
    public var mangaId: Int
    public var number: Double?
    public var title: String?
    public var volume: String?
    public var publishedAt: String?
    /// Position in reading order, 0 = first chapter.
    public var index: Int

    public init(
        id: Int, mangaId: Int, number: Double?, title: String? = nil, volume: String? = nil,
        publishedAt: String? = nil, index: Int
    ) {
        self.id = id
        self.mangaId = mangaId
        self.number = number
        self.title = title
        self.volume = volume
        self.publishedAt = publishedAt
        self.index = index
    }
}

public struct Page: Codable, Hashable, Sendable {
    public var index: Int
    public var url: String
    public var width: Int?
    public var height: Int?

    public init(index: Int, url: String, width: Int? = nil, height: Int? = nil) {
        self.index = index
        self.url = url
        self.width = width
        self.height = height
    }

    /// height / width when the source gave both.
    public var aspect: Double? {
        guard let w = width, let h = height, w > 0, h > 0 else { return nil }
        return Double(h) / Double(w)
    }
}

public struct ChapterPages: Codable, Hashable, Sendable {
    public var chapter: Chapter
    public var pages: [Page]
    public var imageHeaders: [String: String]
    public var prevChapterId: Int?
    public var nextChapterId: Int?

    public init(
        chapter: Chapter, pages: [Page], imageHeaders: [String: String] = [:],
        prevChapterId: Int? = nil, nextChapterId: Int? = nil
    ) {
        self.chapter = chapter
        self.pages = pages
        self.imageHeaders = imageHeaders
        self.prevChapterId = prevChapterId
        self.nextChapterId = nextChapterId
    }
}

public struct MangaList: Codable, Sendable {
    public var items: [MangaSummary]
    public var page: Int
    public var hasMore: Bool

    public init(items: [MangaSummary], page: Int = 1, hasMore: Bool = false) {
        self.items = items
        self.page = page
        self.hasMore = hasMore
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = try c.decode([MangaSummary].self, forKey: .items)
        page = try c.decodeIfPresent(Int.self, forKey: .page) ?? 1
        hasMore = try c.decodeIfPresent(Bool.self, forKey: .hasMore) ?? false
    }

    enum CodingKeys: String, CodingKey { case items, page, hasMore }
}

public struct HomeFeed: Codable, Sendable {
    public var popular: [MangaSummary]
    public var latest: [MangaSummary]
    public var newTitles: [MangaSummary]

    enum CodingKeys: String, CodingKey { case popular, latest, newTitles = "new" }

    public init(popular: [MangaSummary] = [], latest: [MangaSummary] = [], newTitles: [MangaSummary] = []) {
        self.popular = popular
        self.latest = latest
        self.newTitles = newTitles
    }

    public var isEmpty: Bool { popular.isEmpty && latest.isEmpty && newTitles.isEmpty }
}

struct Items<T: Decodable>: Decodable {
    var items: [T]
}

public struct SyncPage<T: Decodable & Sendable>: Decodable, Sendable {
    public var items: [T]
    public var serverTime: String
}

public struct User: Codable, Hashable, Sendable {
    public var id: Int
    public var name: String?
    public var email: String?

    public init(id: Int, name: String?, email: String?) {
        self.id = id
        self.name = name
        self.email = email
    }
}

public struct AuthResponse: Codable, Sendable {
    public var token: String
    public var user: User
}

struct MeResponse: Decodable {
    var user: User
}

public struct RemoteLibraryItem: Codable, Sendable {
    public var manga: MangaSummary
    public var category: String?
    public var addedAt: String
    public var updatedAt: String
    public var deleted: Bool
    public var unreadCount: Int?
}

public struct RemoteProgress: Codable, Sendable, Hashable {
    public var mangaId: Int
    public var chapterId: Int
    public var chapterNumber: Double?
    public var page: Int
    public var pageCount: Int?
    public var completed: Bool
    public var updatedAt: String
}

public struct RemoteReadMark: Codable, Sendable {
    public var chapterId: Int
    public var mangaId: Int
    public var read: Bool
    public var updatedAt: String
}

public struct RemoteHistory: Codable, Sendable {
    public var manga: MangaSummary
    public var chapterId: Int
    public var chapterNumber: Double?
    public var page: Int
    public var readAt: String
}

public struct ContinueItem: Codable, Sendable {
    public var manga: MangaSummary
    public var progress: RemoteProgress
    public var chapter: Chapter
}

public struct UpdateItem: Codable, Sendable {
    public var manga: MangaSummary
    public var newChapters: Int
}

// Request bodies

public struct ProgressPutItem: Codable, Sendable, Hashable {
    public var mangaId: Int
    public var chapterId: Int
    public var page: Int
    public var pageCount: Int?
    public var completed: Bool
    public var updatedAt: String

    public init(mangaId: Int, chapterId: Int, page: Int, pageCount: Int?, completed: Bool, updatedAt: String) {
        self.mangaId = mangaId
        self.chapterId = chapterId
        self.page = page
        self.pageCount = pageCount
        self.completed = completed
        self.updatedAt = updatedAt
    }
}

struct ProgressPutBody: Encodable {
    var items: [ProgressPutItem]
}

struct LibraryPutBody: Encodable {
    var category: String?
    var updatedAt: String
}

struct ReadPutBody: Encodable {
    var chapterIds: [Int]
    var read: Bool
    var updatedAt: String
}

struct AcceptedResponse: Decodable {
    var accepted: Int
}

struct ReadIdsResponse: Decodable {
    var chapterIds: [Int]
}

struct ErrorBody: Decodable {
    struct Inner: Decodable {
        var code: String
        var message: String
    }
    var error: Inner
}
