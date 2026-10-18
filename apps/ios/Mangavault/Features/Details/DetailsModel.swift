import Foundation
import MangavaultKit
import Observation

@Observable
@MainActor
final class DetailsModel {
    var summary: MangaSummary
    var detail: MangaDetail?
    var chapters: [Chapter] = []
    /// The backend is (maybe) re-checking every site; shown under the header.
    var refreshing = true
    var failed = false

    @ObservationIgnored private let app: AppModel

    init(summary: MangaSummary, app: AppModel) {
        self.summary = summary
        self.app = app
        detail = app.detailCache[summary.id]
        chapters = app.chapterCache[summary.id] ?? []
        if let d = detail { self.summary = d.summary }
    }

    var latest: Int {
        Int((summary.latestChapter ?? chapters.last?.number ?? 0).rounded(.down))
    }

    func load() async {
        refreshing = true
        failed = false
        let api = app.api, id = summary.id
        do {
            // Details first: asking for them is what refreshes the chapter list.
            let detail = try await api.manga(id)
            self.detail = detail
            summary = detail.summary
            app.detailCache[id] = detail
            app.store.remember(detail.summary, force: app.store.summaries[id] != nil)
            let chs = try await api.chapters(detail.summary.id)
            chapters = chs
            app.chapterCache[detail.summary.id] = chs
            if detail.summary.id != id { app.chapterCache[id] = chs }
        } catch {
            failed = detail == nil
        }
        refreshing = false
    }
}
