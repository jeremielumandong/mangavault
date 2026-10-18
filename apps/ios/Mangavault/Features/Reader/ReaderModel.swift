import Foundation
import MangavaultKit
import Observation
import UIKit

enum ReaderSheet: Identifiable, Equatable {
    case settings, chapters
    case pageMenu(ReaderItem)

    var id: String {
        switch self {
        case .settings: return "settings"
        case .chapters: return "chapters"
        case .pageMenu(let i): return "page" + i.id
        }
    }
}

/// Ask the cell showing `id` to fetch its image again.
struct PageReload: Equatable {
    var id: String
    var token: Int
}

/// A one-shot instruction for the webtoon list to scroll to an item.
struct ScrollRequest: Equatable {
    var token = 0
    var index = 0
    /// Divider: put it mid-screen instead of at the top.
    var centered = false
}

/// Everything the reader screen shows: which chapters are loaded, the flat
/// sequence of pages/dividers, where the reader is, and the chrome state.
@Observable
@MainActor
final class ReaderModel {
    enum Phase: Equatable { case loading, ready, error }

    let app: AppModel
    var manga: MangaSummary
    var chapters: [Chapter]
    var loaded: [LoadedChapter] = []
    var items: [ReaderItem] = []
    var mode: ReaderMode
    var phase: Phase = .loading
    /// Index into `items` of what's on screen.
    var position = 0
    var chapterId: Int
    /// 1-based page within `chapterId`.
    var page: Int
    var controls: Bool
    var moreMenu = false
    var sheet: ReaderSheet?
    var nextLoading = false
    var nextFailed = false
    var scrollRequest = ScrollRequest()
    var reload: PageReload?
    var zoomed = false
    var dragging = false
    var dragPage = 1

    @ObservationIgnored private var startPage: Int
    @ObservationIgnored private var reloadToken = 0

    init(request: ReaderRequest, app: AppModel) {
        self.app = app
        manga = request.manga
        chapters = request.chapters ?? []
        chapterId = request.chapterId
        page = request.page
        startPage = request.page
        controls = request.showControls
        mode = app.store.readerMode(for: request.manga)
    }

    var settings: AppSettings { app.store.settings }
    var palette: ReaderPalette { ReaderPalette.of(settings.readerBackground) }

    // MARK: Lookup

    func loadedChapter(_ id: Int) -> LoadedChapter? { loaded.first { $0.chapter.id == id } }

    func chapter(_ id: Int) -> Chapter? {
        loadedChapter(id)?.chapter ?? chapters.first { $0.id == id }
    }

    var currentChapter: Chapter? { chapter(chapterId) }

    var pageCount: Int { loadedChapter(chapterId)?.pages.count ?? 0 }

    /// Next chapter in reading order, from the list or the pages response.
    func nextChapter(after id: Int) -> Chapter? {
        if let c = chapter(id), let n = chapters.first(where: { $0.index == c.index + 1 }) { return n }
        if let nid = loadedChapter(id)?.nextChapterId { return chapter(nid) }
        return nil
    }

    func prevChapter(before id: Int) -> Chapter? {
        if let c = chapter(id), let p = chapters.first(where: { $0.index == c.index - 1 }) { return p }
        if let pid = loadedChapter(id)?.prevChapterId { return chapter(pid) }
        return nil
    }

    func nextChapterId(after id: Int) -> Int? {
        nextChapter(after: id)?.id ?? loadedChapter(id)?.nextChapterId
    }

    func prevChapterId(before id: Int) -> Int? {
        prevChapter(before: id)?.id ?? loadedChapter(id)?.prevChapterId
    }

    /// The image for a page slot.
    func pageInfo(_ item: ReaderItem) -> (page: Page, headers: [String: String])? {
        guard case .page(let c, let p, _) = item.slot, let lc = loadedChapter(c), p >= 1, p <= lc.pages.count else {
            return nil
        }
        return (lc.pages[p - 1], lc.headers)
    }

    func chapterTitle(_ id: Int) -> String {
        guard let c = chapter(id) else { return "" }
        if let t = c.title, !t.isEmpty, c.number != nil { return "\(Fmt.chapter(c)) · \(t)" }
        return Fmt.chapter(c)
    }

    // MARK: Loading

    func start() async {
        await open(chapterId: chapterId, page: startPage, keepControls: controls)
    }

    func open(chapterId id: Int, page: Int, keepControls: Bool) async {
        phase = .loading
        chapterId = id
        self.page = max(1, page)
        controls = keepControls
        moreMenu = false
        zoomed = false
        nextFailed = false
        let api = app.api
        let mangaId = manga.id
        let needList = chapters.isEmpty
        async let list: [Chapter]? = needList ? (try? await api.chapters(mangaId)) : nil
        do {
            let p: ChapterPages
            if let cached = loadedChapter(id) {
                p = ChapterPages(
                    chapter: cached.chapter, pages: cached.pages, imageHeaders: cached.headers,
                    prevChapterId: cached.prevChapterId, nextChapterId: cached.nextChapterId)
            } else {
                p = try await api.pages(id)
            }
            if let l = await list, !l.isEmpty {
                chapters = l
                app.chapterCache[mangaId] = l
            }
            loaded = [LoadedChapter(p)]
            rebuild()
            let clamped = min(max(1, self.page), max(1, p.pages.count))
            self.page = clamped
            position = ReaderSequence.index(of: id, page: clamped, in: items) ?? 0
            phase = .ready
            requestScroll(to: position)
            didShow(position)
        } catch {
            _ = await list
            phase = .error
        }
    }

    func retry() {
        Task { await open(chapterId: chapterId, page: page, keepControls: false) }
    }

    private func loadNextIfNeeded() {
        guard let last = loaded.last, let next = last.nextChapterId, !nextLoading,
            loadedChapter(next) == nil
        else { return }
        nextLoading = true
        nextFailed = false
        let api = app.api
        Task {
            defer { nextLoading = false }
            do {
                let p = try await api.pages(next)
                guard loaded.last?.chapter.id == last.chapter.id else { return }
                loaded.append(LoadedChapter(p))
                rebuild()
                prefetch(from: position)
            } catch {
                nextFailed = true
            }
        }
    }

    func retryNext() {
        nextFailed = false
        loadNextIfNeeded()
    }

    private func rebuild() {
        items = ReaderSequence.build(loaded, mode: mode)
    }

    func requestScroll(to index: Int, centered: Bool = false) {
        scrollRequest = ScrollRequest(token: scrollRequest.token + 1, index: index, centered: centered)
    }

    // MARK: Position

    /// Called by the webtoon list and the pager as the reader moves.
    func didShow(_ index: Int) {
        guard items.indices.contains(index) else { return }
        position = index
        let item = items[index]
        switch item.slot {
        case .page(let c, let p, let n):
            if c != chapterId || p != page {
                chapterId = c
                page = p
            }
            if let ch = chapter(c) {
                app.store.recordProgress(manga: manga, chapter: ch, page: p, pageCount: n)
            }
            prefetch(from: index)
            if c == loaded.last?.chapter.id && p >= n - 3 { loadNextIfNeeded() }
        case .divider(let from, _), .transition(let from, _):
            markFinished(from)
            loadNextIfNeeded()
        case .end(let c):
            markFinished(c)
        }
    }

    private func markFinished(_ id: Int) {
        guard let lc = loadedChapter(id) else { return }
        app.store.recordProgress(manga: manga, chapter: lc.chapter, page: lc.pages.count, pageCount: lc.pages.count)
    }

    private func prefetch(from index: Int) {
        var n = settings.prefetch
        if settings.dataSaver && app.network.isExpensive { n = min(n, 2) }
        for item in ReaderSequence.pagesAhead(of: index, count: n, in: items) {
            if let info = pageInfo(item) { ImagePipeline.shared.prefetch(info.page.url, headers: info.headers) }
        }
    }

    // MARK: Actions

    func toggleControls() {
        if moreMenu {
            moreMenu = false
            return
        }
        controls.toggle()
    }

    /// Paged mode: -1 / +1 through the sequence.
    func go(_ dir: Int) {
        let i = max(0, min(items.count - 1, position + dir))
        guard i != position else { return }
        zoomed = false
        didShow(i)
    }

    func handleTap(x: Double, y: Double) {
        if moreMenu {
            moreMenu = false
            return
        }
        if zoomed {
            controls.toggle()
            return
        }
        let dir = TapZones.direction(mode: mode, x: x, y: y, inverted: settings.invertTaps)
        if dir == 0 { controls.toggle() } else { go(dir) }
    }

    func setPage(_ p: Int) {
        guard let i = ReaderSequence.index(of: chapterId, page: p, in: items) else { return }
        if mode == .vertical { requestScroll(to: i) }
        didShow(i)
    }

    func goToChapter(_ id: Int) {
        sheet = nil
        Task { await open(chapterId: id, page: 1, keepControls: true) }
    }

    func setMode(_ m: ReaderMode) {
        guard m != mode else { return }
        app.store.setReaderMode(m, for: manga.id)
        mode = m
        zoomed = false
        rebuild()
        let i = ReaderSequence.index(of: chapterId, page: max(1, page), in: items) ?? 0
        position = i
        requestScroll(to: i)
    }

    func reloadPage(_ item: ReaderItem) {
        guard let info = pageInfo(item) else { return }
        ImagePipeline.shared.evict(info.page.url)
        reloadToken += 1
        reload = PageReload(id: item.id, token: reloadToken)
    }

    func savePage(_ item: ReaderItem) {
        guard let info = pageInfo(item) else { return }
        Task {
            guard let data = try? await ImagePipeline.shared.originalData(info.page.url, headers: info.headers),
                let img = UIImage(data: data)
            else {
                app.showToast("Couldn't save this page")
                return
            }
            UIImageWriteToSavedPhotosAlbum(img, nil, nil, nil)
            app.showToast("Saved to Photos")
        }
    }

    func sharePage(_ item: ReaderItem) {
        guard let info = pageInfo(item) else { return }
        Task {
            guard let data = try? await ImagePipeline.shared.originalData(info.page.url, headers: info.headers),
                let img = UIImage(data: data)
            else { return }
            Presenter.share([img])
        }
    }

    func shareChapter() {
        Presenter.share(["\(manga.title) · \(chapterTitle(chapterId)) on Mangavault"])
    }

    func close(toDetails: Bool = false) {
        let app = self.app
        let manga = self.manga
        app.reader = nil
        if toDetails && (app.paths[app.tab]?.isEmpty ?? true) { app.open(manga) }
    }

    func favorite() {
        app.toggleFavorite(manga)
    }

    var isFavorite: Bool { app.store.isFavorite(manga.id) }
}
