import MangavaultKit
import SwiftUI

private struct LibItem: Identifiable {
    var entry: LibraryEntry
    var manga: MangaSummary
    var progress: ProgressEntry?
    var unread: Int
    var hasNew: Bool
    var id: Int { manga.id }
}

struct LibraryView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    @State private var category = "All"
    @State private var showSort = false
    @State private var showNewCategory = false

    private var store: LocalStore { app.store }
    private var settings: AppSettings { store.settings }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if app.network.isOffline {
                    OfflineBanner(text: "Offline. Showing your saved library; changes sync when you're back.")
                }
                HStack(alignment: .bottom) {
                    ScreenTitle(title: "Library")
                    Spacer()
                    Button {
                        if !app.isSignedIn { app.askSignIn() } else { Task { await app.sync.sync() } }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: syncSymbol).font(.system(size: 13))
                            Text(syncLabel)
                        }
                        .mvText(12, .regular, theme.ts)
                        .padding(.bottom, 6)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 16)
                .padding(.top, 4)
                .padding(.bottom, 4)
                categoryTabs
                controls
                content
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(theme.bg)
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { await app.sync.sync() }
        .sheet(isPresented: $showSort) { sortSheet }
        .sheet(isPresented: $showNewCategory) {
            NewCategorySheet(hint: "Categories sync with your account.") { name in
                store.addCategory(name)
                category = name
                app.showToast("Added “\(name)”")
            }
            .environment(app)
        }
    }

    // MARK: Header bits

    private var syncSymbol: String {
        if app.network.isOffline { return "icloud.slash" }
        if !app.isSignedIn { return "iphone" }
        switch app.sync.status {
        case .failed: return "exclamationmark.icloud"
        case .syncing: return "arrow.triangle.2.circlepath.icloud"
        default: return "checkmark.icloud"
        }
    }

    private var syncLabel: String {
        if app.network.isOffline { return "Waiting to sync" }
        if !app.isSignedIn { return "On this device" }
        switch app.sync.status {
        case .syncing: return "Syncing…"
        case .failed: return "Sync failed · tap to retry"
        default:
            guard let d = store.lastSyncedAt else { return "Not synced yet" }
            if Date().timeIntervalSince(d) < 60 { return "Synced just now" }
            let f = RelativeDateTimeFormatter()
            f.unitsStyle = .short
            return "Synced \(f.localizedString(for: d, relativeTo: Date()))"
        }
    }

    private var categoryTabs: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 20) {
                ForEach(["All"] + store.categories, id: \.self) { c in
                    let on = category == c
                    Button {
                        category = c
                    } label: {
                        HStack(spacing: 6) {
                            Text(c).mvText(15, .semibold, on ? theme.tp : theme.ts)
                            Text("\(count(c))").mvText(12, .medium, theme.ts)
                        }
                        .frame(height: 40)
                        .overlay(alignment: .bottom) {
                            Rectangle().fill(on ? theme.ac : .clear).frame(height: 2)
                        }
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    showNewCategory = true
                } label: {
                    Image(systemName: "plus").font(.system(size: 17)).foregroundStyle(theme.ts).frame(width: 32, height: 40)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("New category")
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
        .padding(.top, 4)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.ln).frame(height: 0.5) }
    }

    private func count(_ c: String) -> Int {
        c == "All" ? store.favorites.count : store.favorites.filter { $0.category == c }.count
    }

    private var controls: some View {
        HStack(spacing: 8) {
            Button {
                showSort = true
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "line.3.horizontal.decrease").font(.system(size: 14))
                    Text(settings.libSort.rawValue)
                }
                .mvText(13, .medium, theme.tp)
                .padding(.horizontal, 12)
                .frame(height: 32)
                .background(theme.sf, in: Capsule())
            }
            .buttonStyle(.plain)
            if settings.libUnread {
                Text("Unread").mvText(13, .medium, theme.ac).padding(.horizontal, 12).frame(height: 32)
                    .background(theme.acs, in: Capsule())
            }
            Spacer()
            if settings.libView == .grid {
                HStack(spacing: 0) {
                    ForEach([2, 3, 4], id: \.self) { n in
                        Button {
                            store.settings.libCols = n
                        } label: {
                            Text("\(n)").mvText(12, .semibold, theme.tp).frame(width: 30, height: 28)
                                .background(settings.libCols == n ? theme.sr : .clear, in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(2)
                .background(theme.sf, in: Capsule())
            }
            Button {
                store.settings.libView = settings.libView == .grid ? .list : .grid
            } label: {
                Image(systemName: settings.libView == .grid ? "list.bullet" : "square.grid.2x2")
                    .font(.system(size: 16)).foregroundStyle(theme.tp)
                    .frame(width: 44, height: 32)
                    .background(theme.sf, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Toggle view")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    // MARK: Items

    private var items: [LibItem] {
        var out: [LibItem] = []
        for e in store.favorites where category == "All" || e.category == category {
            let m = store.summaries[e.mangaId] ?? MangaSummary(id: e.mangaId, title: "")
            let p = store.progress[e.mangaId]
            let unread = Reading.unread(latest: m.latestChapter, progress: p)
            let hasNew: Bool = {
                guard unread > 0, let up = m.updatedAt else { return false }
                return ISO.isLater(up, than: store.history[m.id]?.readAt ?? e.addedAt)
            }()
            out.append(LibItem(entry: e, manga: m, progress: p, unread: unread, hasNew: hasNew))
        }
        if settings.libUnread { out = out.filter { $0.unread > 0 } }
        switch settings.libSort {
        case .lastRead:
            out.sort { (store.history[$0.id]?.readAt ?? "") > (store.history[$1.id]?.readAt ?? "") }
        case .lastUpdated:
            out.sort { ($0.manga.updatedAt ?? "") > ($1.manga.updatedAt ?? "") }
        case .title:
            out.sort { $0.manga.title.localizedCaseInsensitiveCompare($1.manga.title) == .orderedAscending }
        case .dateAdded:
            out.sort { $0.entry.addedAt > $1.entry.addedAt }
        }
        return out
    }

    @ViewBuilder private var content: some View {
        let list = items
        if list.isEmpty {
            if app.isSignedIn, case .failed = app.sync.status, store.favorites.isEmpty {
                EmptyState(
                    symbol: "exclamationmark.arrow.triangle.2.circlepath", title: "Couldn't sync your library",
                    message: "The Mangavault server didn't answer. Your favorites on this phone are untouched.",
                    button: "Retry", action: { Task { await app.sync.sync() } }, top: 90)
            } else if app.isSignedIn, app.sync.status == .syncing, store.favorites.isEmpty {
                SkeletonGrid()
            } else {
                EmptyState(
                    symbol: "heart", title: store.favorites.isEmpty ? "Your library is empty" : "Nothing here yet",
                    message: "Tap the heart on any title and it lands here with new-chapter badges.",
                    button: "Browse popular titles", action: { app.browse(sort: .popular) }, top: 80)
            }
        } else if settings.libView == .grid {
            let cols = settings.libCols
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12, alignment: .top), count: cols), spacing: 16) {
                ForEach(list) { item in
                    Button { app.open(item.manga) } label: { GridCell(item: item, titleSize: cols == 4 ? 11 : cols == 3 ? 13 : 15) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        } else {
            LazyVStack(spacing: 0) {
                ForEach(list) { item in
                    Button { app.open(item.manga) } label: { ListRow(item: item) }
                        .buttonStyle(.plain)
                }
            }
        }
    }

    private var sortSheet: some View {
        ListSheet(
            title: "Sort and filter",
            items: LibrarySort.allCases.map { s in
                ListSheetItem(symbol: "arrow.up.arrow.down", label: s.rawValue, checked: settings.libSort == s) {
                    store.settings.libSort = s
                }
            } + [
                ListSheetItem(symbol: "envelope.badge", label: "Unread only", checked: settings.libUnread) {
                    store.settings.libUnread.toggle()
                }
            ])
    }
}

private struct GridCell: View {
    let item: LibItem
    let titleSize: CGFloat
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            GeometryReader { g in
                CoverImage(manga: item.manga, width: g.size.width, shade: false)
                    .overlay(alignment: .topLeading) {
                        if item.unread > 0 {
                            Text("\(item.unread)")
                                .mvText(11, .bold, .white)
                                .padding(.horizontal, 6)
                                .frame(minWidth: 20, minHeight: 20)
                                .background(Color.black.opacity(0.65), in: Capsule())
                                .padding(6)
                                .accessibilityLabel("\(item.unread) unread chapters")
                        }
                    }
                    .overlay(alignment: .topTrailing) {
                        if item.hasNew {
                            Circle().fill(theme.ac).frame(width: 10, height: 10)
                                .overlay(Circle().stroke(Color.black.opacity(0.45), lineWidth: 2))
                                .padding(7)
                                .accessibilityLabel("New chapters")
                        }
                    }
                    .overlay(alignment: .bottom) {
                        Rectangle().fill(Color.black.opacity(0.45)).frame(height: 3)
                            .overlay(alignment: .leading) {
                                Rectangle().fill(theme.ac).frame(width: g.size.width * fraction)
                            }
                            .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 8, bottomTrailingRadius: 8))
                    }
            }
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            Text(item.manga.title).mvText(titleSize, .semibold, theme.tp).lineLimit(2).multilineTextAlignment(.leading)
        }
        .contentShape(Rectangle())
    }

    private var fraction: CGFloat {
        guard let p = item.progress else { return 0 }
        if p.completed { return 1 }
        guard let latest = item.manga.latestChapter, latest > 0, let n = p.chapterNumber else { return 0 }
        return CGFloat(min(1, n / latest))
    }
}

private struct ListRow: View {
    let item: LibItem
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 12) {
            CoverImage(manga: item.manga, width: 52, cornerRadius: 6, shade: false)
                .overlay(alignment: .topTrailing) {
                    if item.hasNew {
                        Circle().fill(theme.ac).frame(width: 10, height: 10)
                            .overlay(Circle().stroke(theme.bg, lineWidth: 2))
                            .offset(x: 3, y: -3)
                    }
                }
            VStack(alignment: .leading, spacing: 3) {
                Text(item.manga.title).mvText(15, .semibold, theme.tp).lineLimit(2).multilineTextAlignment(.leading)
                Text(sub).mvText(12, .regular, theme.ts).lineLimit(1)
                GeometryReader { g in
                    Capsule().fill(theme.sr).overlay(alignment: .leading) {
                        Capsule().fill(theme.ac).frame(width: g.size.width * fraction)
                    }
                }
                .frame(height: 3)
                .padding(.top, 3)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if item.unread > 0 {
                Text("\(item.unread)").mvText(11, .bold, theme.ac)
                    .padding(.horizontal, 7)
                    .frame(minWidth: 24, minHeight: 24)
                    .background(theme.acs, in: Capsule())
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }

    private var latestText: String { Fmt.number(item.manga.latestChapter) }

    private var sub: String {
        guard let p = item.progress else { return "Not started · \(latestText) chapters" }
        if p.completed && item.unread == 0 { return "Completed · \(latestText) chapters" }
        return "\(Fmt.chapter(number: p.chapterNumber)) of \(latestText)"
    }

    private var fraction: CGFloat {
        guard let p = item.progress else { return 0 }
        if p.completed && item.unread == 0 { return 1 }
        guard let latest = item.manga.latestChapter, latest > 0, let n = p.chapterNumber else { return 0 }
        return CGFloat(min(1, n / latest))
    }
}
