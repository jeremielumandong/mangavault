import Foundation

public enum ReaderMode: String, Codable, CaseIterable, Sendable, Identifiable {
    case vertical, rtl, ltr, pagedV

    public var id: String { rawValue }

    public var name: String {
        switch self {
        case .vertical: return "Webtoon scroll"
        case .rtl: return "Right to left"
        case .ltr: return "Left to right"
        case .pagedV: return "Vertical pages"
        }
    }

    public var isPaged: Bool { self != .vertical }

    /// Manga reads right to left, western comics left to right, everything
    /// else (manhwa, manhua, webtoons) scrolls.
    public static func defaultMode(forType type: String?) -> ReaderMode {
        switch type?.lowercased() {
        case "manga": return .rtl
        case "comic": return .ltr
        default: return .vertical
        }
    }
}

public enum ReaderBackground: String, Codable, CaseIterable, Sendable {
    case black = "Black"
    case darkGray = "Dark gray"
    case white = "White"
    case sepia = "Sepia"

    public var isLight: Bool { self == .white || self == .sepia }
}

public enum ReaderFit: String, Codable, CaseIterable, Sendable {
    case width = "Width"
    case height = "Height"
    case screen = "Screen"
    case original = "Original"
}

public enum TapZones {
    /// -1 previous, +1 next, 0 toggles the controls. Zones are the outer 30%
    /// of the width (or height for vertical pages).
    public static func direction(mode: ReaderMode, x: Double, y: Double, inverted: Bool) -> Int {
        let f = mode == .pagedV ? y : x
        let zone = f < 0.3 ? -1 : f > 0.7 ? 1 : 0
        if zone == 0 { return 0 }
        var dir = mode == .rtl ? -zone : zone
        if inverted { dir = -dir }
        return dir
    }
}

public struct LoadedChapter: Hashable, Sendable {
    public var chapter: Chapter
    public var pages: [Page]
    public var headers: [String: String]
    public var prevChapterId: Int?
    public var nextChapterId: Int?

    public init(_ p: ChapterPages) {
        chapter = p.chapter
        pages = p.pages.sorted { $0.index < $1.index }
        headers = p.imageHeaders
        prevChapterId = p.prevChapterId
        nextChapterId = p.nextChapterId
    }

    public init(chapter: Chapter, pages: [Page], headers: [String: String] = [:], prev: Int? = nil, next: Int? = nil) {
        self.chapter = chapter
        self.pages = pages
        self.headers = headers
        prevChapterId = prev
        nextChapterId = next
    }
}

public enum ReaderSlot: Hashable, Sendable {
    /// `page` is 1-based.
    case page(chapterId: Int, page: Int, count: Int)
    /// Webtoon: the card between two chapters.
    case divider(from: Int, to: Int)
    /// Paged: the "Finished / Next" page between two chapters.
    case transition(from: Int, to: Int)
    /// After the latest chapter.
    case end(chapterId: Int)
}

public struct ReaderItem: Hashable, Sendable, Identifiable {
    public var slot: ReaderSlot

    public var id: String {
        switch slot {
        case .page(let c, let p, _): return "p\(c)-\(p)"
        case .divider(let a, _): return "d\(a)"
        case .transition(let a, _): return "t\(a)"
        case .end(let c): return "e\(c)"
        }
    }

    public var chapterId: Int {
        switch slot {
        case .page(let c, _, _), .divider(let c, _), .transition(let c, _), .end(let c): return c
        }
    }

    public var isPage: Bool {
        if case .page = slot { return true }
        return false
    }

    public init(_ slot: ReaderSlot) { self.slot = slot }
}

public enum ReaderSequence {
    /// Pages of each loaded chapter in order, with a divider (webtoon) or a
    /// transition page (paged) after every chapter that has a next one, and
    /// the end card after the latest.
    public static func build(_ chapters: [LoadedChapter], mode: ReaderMode) -> [ReaderItem] {
        var out: [ReaderItem] = []
        for lc in chapters {
            let n = lc.pages.count
            for p in 0..<n {
                out.append(ReaderItem(.page(chapterId: lc.chapter.id, page: p + 1, count: n)))
            }
            if let next = lc.nextChapterId {
                out.append(ReaderItem(mode == .vertical ? .divider(from: lc.chapter.id, to: next) : .transition(from: lc.chapter.id, to: next)))
            } else {
                out.append(ReaderItem(.end(chapterId: lc.chapter.id)))
            }
        }
        return out
    }

    public static func index(of chapterId: Int, page: Int, in items: [ReaderItem]) -> Int? {
        items.firstIndex {
            if case .page(let c, let p, _) = $0.slot { return c == chapterId && p == page }
            return false
        }
    }

    /// Page slots following `index`, for prefetching.
    public static func pagesAhead(of index: Int, count: Int, in items: [ReaderItem]) -> [ReaderItem] {
        guard count > 0, index + 1 < items.count else { return [] }
        return Array(items[(index + 1)...].filter(\.isPage).prefix(count))
    }
}
