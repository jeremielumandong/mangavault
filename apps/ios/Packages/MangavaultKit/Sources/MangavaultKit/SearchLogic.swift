import Foundation

public enum SortOption: String, CaseIterable, Codable, Sendable {
    case relevance = "Relevance"
    case popular = "Popular"
    case latest = "Latest update"
    case new = "New"
    case title = "Title"

    /// The API's `sort` value. Relevance is the search default and means
    /// "popular" when browsing.
    public func apiValue(searching: Bool) -> String? {
        switch self {
        case .relevance: return searching ? nil : "popular"
        case .popular: return "popular"
        case .latest: return "latest"
        case .new: return "new"
        case .title: return "title"
        }
    }
}

public struct SearchFilters: Equatable, Codable, Sendable {
    public var include: [String] = []
    public var exclude: [String] = []
    /// Display values ("Ongoing"); nil means Any.
    public var status: String?
    public var type: String?
    public var sort: SortOption = .relevance

    public init() {}

    public static let statuses = ["Any", "Ongoing", "Completed", "Hiatus", "Cancelled"]
    public static let types = ["Any", "Manga", "Manhwa", "Manhua", "Comic"]
    /// Shown until /v1/genres answers.
    public static let fallbackGenres = [
        "Action", "Romance", "Fantasy", "Drama", "Comedy", "Slice of life", "Horror", "Mystery", "Sci-fi",
        "Martial arts", "Historical", "School",
    ]

    public var count: Int {
        (status == nil ? 0 : 1) + (type == nil ? 0 : 1) + include.count + exclude.count + (sort == .relevance ? 0 : 1)
    }

    /// Include → exclude → off.
    public mutating func cycle(_ genre: String) {
        if let i = include.firstIndex(of: genre) {
            include.remove(at: i)
            exclude.append(genre)
        } else if let i = exclude.firstIndex(of: genre) {
            exclude.remove(at: i)
        } else {
            include.append(genre)
        }
    }

    public func query(searching: Bool, page: Int = 1, limit: Int = 30) -> MangaQuery {
        MangaQuery(
            sort: sort.apiValue(searching: searching), genres: include, excludeGenres: exclude,
            status: status?.lowercased(), type: type?.lowercased(), page: page, limit: limit)
    }
}
