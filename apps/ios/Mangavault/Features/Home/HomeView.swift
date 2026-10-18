import MangavaultKit
import Observation
import SwiftUI

@Observable
@MainActor
final class HomeModel {
    enum Phase { case loading, error, content }

    var phase: Phase = .loading
    var feed = HomeFeed()
    var more: [MangaSummary] = []
    var moreGenre = "Romance"
    @ObservationIgnored weak var store: LocalStore?
    @ObservationIgnored private let api: APIClient
    @ObservationIgnored private var loadedAt: Date?

    private static var cacheURL: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("home.json")
    }

    init(api: APIClient) {
        self.api = api
        if let d = try? Data(contentsOf: Self.cacheURL), let f = try? JSONDecoder().decode(HomeFeed.self, from: d) {
            feed = f
            phase = .content
        }
    }

    func load(force: Bool = false) async {
        if !force, let loadedAt, Date().timeIntervalSince(loadedAt) < 300 { return }
        if feed.isEmpty { phase = .loading }
        moreGenre = store?.genres.first ?? "Romance"
        let genre = moreGenre
        do {
            async let f = api.home()
            async let m = api.browse(MangaQuery(sort: "popular", genres: [genre], limit: 20))
            let feed = try await f
            self.feed = feed
            more = (try? await m)?.items ?? more
            phase = .content
            loadedAt = Date()
            store?.remember(feed.popular + feed.latest + feed.newTitles + more)
            if let d = try? JSONEncoder().encode(feed) { try? d.write(to: Self.cacheURL, options: .atomic) }
        } catch {
            if feed.isEmpty { phase = .error }
        }
    }
}

struct HomeView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme

    private var model: HomeModel { app.home }
    private var store: LocalStore { app.store }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if app.network.isOffline && model.phase == .content {
                    OfflineBanner(text: "You're offline. Downloaded and cached chapters still open.")
                }
                header
                switch model.phase {
                case .loading: skeleton
                case .error:
                    EmptyState(
                        symbol: "wifi.slash", title: "Couldn't load Home",
                        message: "The Mangavault server didn't respond. Check your connection and try again.",
                        button: "Retry", action: { Task { await model.load(force: true) } }, top: 120)
                case .content:
                    if isEmpty {
                        EmptyState(
                            dashedCover: true, title: "Nothing to continue yet",
                            message: "Start a series and it'll wait for you here, one tap from where you left off.",
                            button: "Browse popular titles", action: { app.browse(sort: .popular) }, top: 110)
                    } else {
                        content
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(theme.bg)
        .refreshable {
            await model.load(force: true)
            await app.sync.sync()
        }
        .task { await model.load() }
        .toolbar(.hidden, for: .navigationBar)
    }

    private var isEmpty: Bool { model.feed.isEmpty && continueItems.isEmpty }

    private var header: some View {
        HStack {
            (Text("manga").foregroundColor(theme.tp) + Text("vault").foregroundColor(theme.ac))
                .font(MV.display(24))
            Spacer()
            Button {
                app.go(.settings)
            } label: {
                Image(systemName: "person")
                    .font(.system(size: 19))
                    .foregroundStyle(theme.tp)
                    .frame(width: 44, height: 44)
                    .background(theme.sf, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Account")
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 4)
        .frame(minHeight: 52)
    }

    // MARK: Skeleton

    private var skeleton: some View {
        VStack(alignment: .leading, spacing: 0) {
            theme.shimmer(6).frame(width: 140, height: 16).padding(.horizontal, 16).padding(.top, 20).padding(.bottom, 10)
            HStack(spacing: 12) {
                ForEach(0..<2, id: \.self) { _ in theme.shimmer(12).frame(width: 300, height: 110) }
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipped()
            ForEach(0..<3, id: \.self) { _ in
                RoundedRectangle(cornerRadius: 6).fill(theme.sk).frame(width: 120, height: 16)
                    .padding(.horizontal, 16).padding(.top, 24).padding(.bottom, 10)
                HStack(spacing: 12) {
                    ForEach(0..<4, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            theme.shimmer(8).frame(width: 112, height: 168)
                            RoundedRectangle(cornerRadius: 4).fill(theme.sk).frame(width: 100, height: 10)
                            RoundedRectangle(cornerRadius: 4).fill(theme.sk).frame(width: 67, height: 10)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .clipped()
            }
        }
    }

    // MARK: Content

    private var continueItems: [ProgressEntry] {
        Reading.continueList(Array(store.progress.values)) { store.summaries[$0]?.latestChapter }
            .filter { store.summaries[$0.mangaId] != nil }
            .prefix(5)
            .map { $0 }
    }

    private struct Updated: Identifiable {
        var manga: MangaSummary
        var count: Int
        var id: Int { manga.id }
    }

    /// Favorites with chapters newer than the last read.
    private var updated: [Updated] {
        store.favorites.compactMap { e -> Updated? in
            guard let m = store.summaries[e.mangaId], let p = store.progress[e.mangaId] else { return nil }
            let n = Reading.unread(latest: m.latestChapter, progress: p)
            guard n > 0, let up = m.updatedAt, ISO.isLater(up, than: p.updatedAt) else { return nil }
            return Updated(manga: m, count: n)
        }
        .sorted { ($0.manga.updatedAt ?? "") > ($1.manga.updatedAt ?? "") }
    }

    @ViewBuilder private var content: some View {
        let cont = continueItems
        if !cont.isEmpty {
            SectionHeader(title: "Continue reading", top: 20)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 12) {
                    ForEach(cont, id: \.mangaId) { p in
                        if let m = store.summaries[p.mangaId] { ContinueCard(manga: m, progress: p) }
                    }
                }
                .scrollTargetLayout()
                .padding(.horizontal, 16)
            }
            .scrollTargetBehavior(.viewAligned)
            .scrollIndicators(.hidden)
        }
        let upd = updated
        if !upd.isEmpty {
            SectionHeader(title: "Updated favorites", action: "Library", onAction: { app.go(.library) })
            row(upd.map(\.manga)) { m in "\(upd.first { $0.id == m.id }?.count ?? 0) new" }
        }
        discoverRow("Popular", model.feed.popular, sort: .popular)
        discoverRow("Latest updates", model.feed.latest, sort: .latest)
        discoverRow("New titles", model.feed.newTitles, sort: .new)
        discoverRow("More \(model.moreGenre)", model.more, sort: .popular, genre: model.moreGenre)
    }

    @ViewBuilder
    private func discoverRow(_ title: String, _ items: [MangaSummary], sort: SortOption, genre: String? = nil) -> some View {
        if !items.isEmpty {
            SectionHeader(title: title, action: "See all", onAction: { app.browse(sort: sort, genre: genre) })
            row(items) { _ in nil }
        }
    }

    private func row(_ items: [MangaSummary], badge: @escaping (MangaSummary) -> String?) -> some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 12) {
                ForEach(items) { m in
                    Button {
                        app.open(m)
                    } label: {
                        CoverCard(manga: m, badge: badge(m))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
    }
}

struct ContinueCard: View {
    let manga: MangaSummary
    let progress: ProgressEntry
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme

    var body: some View {
        Button {
            app.read(manga, chapterId: progress.chapterId, page: progress.page)
        } label: {
            HStack(spacing: 12) {
                CoverImage(manga: manga, width: 60, cornerRadius: 6, shade: false)
                VStack(alignment: .leading, spacing: 4) {
                    Text(manga.title).mvText(15, .semibold, theme.tp).lineLimit(2).multilineTextAlignment(.leading)
                    Text(sub).mvText(12, .regular, theme.ts).lineLimit(1)
                    GeometryReader { g in
                        Capsule().fill(theme.sr)
                            .overlay(alignment: .leading) {
                                Capsule().fill(theme.ac).frame(width: g.size.width * progress.chapterFraction)
                            }
                    }
                    .frame(height: 3)
                    .padding(.top, 4)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "play.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(theme.ac, in: Circle())
                    .accessibilityLabel("Resume")
            }
            .padding(10)
            .frame(width: 300, height: 110)
            .background(theme.sf, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button {
                app.open(manga)
            } label: {
                Label("Series details", systemImage: "info.circle")
            }
            Button(role: .destructive) {
                removeFromContinue()
            } label: {
                Label("Remove from Continue Reading", systemImage: "minus.circle")
            }
        }
        .accessibilityAction(named: "Remove from Continue Reading") { removeFromContinue() }
    }

    /// Hides the card until the series is read again; the place in it is kept.
    private func removeFromContinue() {
        let id = manga.id
        let store = app.store
        store.setContinueHidden(id, true)
        app.showToast("Removed from Continue Reading", undo: { store.setContinueHidden(id, false) })
    }

    private var sub: String {
        let ch = Fmt.chapter(number: progress.chapterNumber)
        if let n = progress.pageCount { return "\(ch) · page \(progress.page) of \(n)" }
        return "\(ch) · page \(progress.page)"
    }
}
