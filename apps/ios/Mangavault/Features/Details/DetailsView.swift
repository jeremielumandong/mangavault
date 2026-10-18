import MangavaultKit
import SwiftUI

private enum DetailsSheet: Identifiable {
    case category, newCategory, jump
    case chapter(Chapter)

    var id: String {
        switch self {
        case .category: return "category"
        case .newCategory: return "newCategory"
        case .jump: return "jump"
        case .chapter(let c): return "chapter\(c.id)"
        }
    }
}

struct DetailsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var model: DetailsModel
    @State private var sheet: DetailsSheet?
    @State private var altOpen = false
    @State private var synMore = false
    @State private var newestFirst = true
    @State private var flash: Int?
    @State private var scrollTarget: Int?
    @State private var coverColor: UIColor?

    init(manga: MangaSummary, app: AppModel) {
        _model = State(initialValue: DetailsModel(summary: manga, app: app))
    }

    private var store: LocalStore { app.store }
    private var m: MangaSummary { model.summary }
    private var progress: ProgressEntry? { store.progress[m.id] }
    private var isFav: Bool { store.isFavorite(m.id) }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    header
                    actions
                    chaptersHeader
                    chapterRows
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .onChange(of: scrollTarget) { _, id in
                guard let id else { return }
                withAnimation { proxy.scrollTo(id, anchor: UnitPoint(x: 0.5, y: 0.3)) }
                scrollTarget = nil
            }
        }
        .background(theme.bg)
        .ignoresSafeArea(edges: .top)
        .overlay(alignment: .topLeading) { backButton }
        .toolbar(.hidden, for: .navigationBar)
        .task { await model.load() }
        .refreshable { await model.load() }
        .sheet(item: $sheet) { s in sheetView(s) }
    }

    // MARK: Header

    private var backButton: some View {
        Button {
            dismiss()
        } label: {
            Image(systemName: "chevron.left")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(.ultraThinMaterial, in: Circle())
                .background(Color.black.opacity(0.35), in: Circle())
                .environment(\.colorScheme, .dark)
        }
        .buttonStyle(.plain)
        .padding(.leading, 8)
        .padding(.top, 4)
        .accessibilityLabel("Back")
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .bottom, spacing: 16) {
                CoverImage(manga: m, width: 120, shade: false) { img in
                    let c = ImageColor.average(img)
                    withAnimation(.easeOut(duration: 0.3)) { coverColor = c }
                }
                .shadow(color: .black.opacity(0.35), radius: 15, y: 12)
                VStack(alignment: .leading, spacing: 6) {
                    Text(m.title).mvText(22, .black, theme.tp).fixedSize(horizontal: false, vertical: true)
                    if let credit = model.detail?.creditLine, !credit.isEmpty {
                        Text(credit).mvText(13, .regular, theme.ts)
                    }
                    Text(meta).mvText(13, .regular, theme.ts)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if let alt = model.detail?.altTitles, !alt.isEmpty {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { altOpen.toggle() }
                } label: {
                    HStack(spacing: 2) {
                        Text("Also known as")
                        Image(systemName: altOpen ? "chevron.up" : "chevron.down").font(.system(size: 12, weight: .semibold))
                    }
                    .mvText(13, .regular, theme.ts)
                    .frame(minHeight: 24)
                }
                .buttonStyle(.plain)
                if altOpen {
                    Text(alt.joined(separator: " · ")).mvText(13, .regular, theme.tp).padding(.top, -6)
                }
            }
            if let genres = model.detail?.genres, !genres.isEmpty {
                FlowLayout(spacing: 6) {
                    ForEach(genres, id: \.self) { g in
                        Text(g).mvText(12, .medium, theme.tp)
                            .padding(.horizontal, 12)
                            .frame(height: 28)
                            .background(theme.sr, in: Capsule())
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, safeTop + 52)
        .padding(.bottom, 16)
        .background {
            LinearGradient(
                colors: [CoverTint.header(from: coverColor, id: m.id, light: theme.isLight), theme.bg],
                startPoint: .top, endPoint: .bottom)
        }
    }

    private var safeTop: CGFloat {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.first?.windows.first?.safeAreaInsets.top ?? 54
    }

    private var chapterTotal: Int {
        if !model.chapters.isEmpty { return model.chapters.count }
        if let n = model.detail?.chapterCount, n > 0 { return n }
        return model.latest
    }

    private var meta: String {
        "\(m.typeLabel) · \(m.statusLabel) · \(Fmt.plural(chapterTotal, "chapter"))"
    }

    // MARK: Actions

    private var currentChapter: Chapter? {
        guard let p = progress else { return nil }
        return model.chapters.first { $0.id == p.chapterId }
    }

    private var actions: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(action: primary) {
                VStack(spacing: 0) {
                    Text(primaryLabel.0).mvText(17, .semibold, .white)
                    Text(primaryLabel.1).mvText(12, .regular, Color.white.opacity(0.85))
                }
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .background(theme.ac, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .opacity(model.chapters.isEmpty ? 0.6 : 1)
            }
            .buttonStyle(.plain)
            .disabled(model.chapters.isEmpty)

            HStack(spacing: 8) {
                Button {
                    if isFav { sheet = .category } else { app.toggleFavorite(m) }
                } label: {
                    tile(symbol: isFav ? "heart.fill" : "heart", label: isFav ? (store.library[m.id]?.category ?? "In Library") : "Favorite",
                         color: isFav ? theme.ac : theme.tp)
                }
                .buttonStyle(.plain)
                ShareLink(item: "\(m.title) · on Mangavault") {
                    tile(symbol: "square.and.arrow.up", label: "Share", color: theme.tp)
                }
                .buttonStyle(.plain)
            }

            if let syn = model.detail?.description, !syn.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(syn)
                        .mvText(15, .regular, theme.tp)
                        .lineLimit(synMore ? nil : 4)
                        .lineSpacing(3)
                    Button {
                        withAnimation(.easeOut(duration: 0.2)) { synMore.toggle() }
                    } label: {
                        Text(synMore ? "Less" : "More").mvText(15, .medium, theme.ac).frame(minHeight: 28)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.top, 4)
            }
        }
        .padding(.horizontal, 16)
    }

    private func tile(symbol: String, label: String, color: Color) -> some View {
        VStack(spacing: 2) {
            Image(systemName: symbol).font(.system(size: 20))
            Text(label).font(MV.font(12, .medium)).lineLimit(1)
        }
        .foregroundStyle(color)
        .frame(maxWidth: .infinity)
        .frame(height: 56)
        .background(theme.sf, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var primaryLabel: (String, String) {
        let first = model.chapters.first
        if let p = progress {
            if p.completed && (currentChapter.map { $0.id == model.chapters.last?.id } ?? Reading.isCaughtUp(latest: m.latestChapter, progress: p)) {
                return ("Read again from \(first.map(Fmt.chapter) ?? "Ch. 1")", "Finished · \(Fmt.plural(chapterTotal, "chapter"))")
            }
            if p.completed, let cur = currentChapter, let next = model.chapters.first(where: { $0.index == cur.index + 1 }) {
                return ("Continue \(Fmt.chapter(next))", "Next chapter")
            }
            let pages = p.pageCount.map { "Page \(p.page) of \($0)" } ?? "Page \(p.page)"
            return ("Continue \(Fmt.chapter(number: p.chapterNumber))", pages)
        }
        return ("Read \(first.map(Fmt.chapter) ?? "Ch. 1")", "\(Fmt.plural(chapterTotal, "chapter")) available")
    }

    private func primary() {
        guard let first = model.chapters.first else { return }
        if let p = progress {
            if p.completed, let cur = currentChapter {
                if let next = model.chapters.first(where: { $0.index == cur.index + 1 }) {
                    app.read(m, chapterId: next.id, chapters: model.chapters)
                } else {
                    app.read(m, chapterId: first.id, chapters: model.chapters)
                }
                return
            }
            app.read(m, chapterId: p.chapterId, page: p.page, chapters: model.chapters)
        } else {
            app.read(m, chapterId: first.id, chapters: model.chapters)
        }
    }

    // MARK: Chapters

    private var chaptersHeader: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                Text(Fmt.plural(chapterTotal, "chapter")).mvText(17, .semibold, theme.tp).frame(maxWidth: .infinity, alignment: .leading)
                pill("number", "Jump") { sheet = .jump }
                pill("arrow.up.arrow.down", newestFirst ? "Newest" : "Oldest") { newestFirst.toggle() }
                    .accessibilityLabel("Sort chapters")
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .padding(.top, 24)
            .padding(.bottom, 6)
            HStack(spacing: 6) {
                Image(systemName: freshSymbol).font(.system(size: 12, weight: .semibold)).foregroundStyle(freshColor)
                    .symbolEffect(.pulse, isActive: model.refreshing)
                Text(freshText).mvText(12, .regular, theme.ts)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
    }

    private var freshText: String {
        if model.refreshing { return "Checking every site for new chapters…" }
        if model.failed || (model.detail == nil && !model.chapters.isEmpty) {
            return "Couldn't reach the server · showing saved chapters"
        }
        return "Up to date · long-press a chapter to mark it read"
    }

    private var freshSymbol: String {
        model.refreshing ? "arrow.triangle.2.circlepath" : model.failed ? "exclamationmark.circle" : "checkmark"
    }

    private var freshColor: Color {
        model.refreshing ? theme.ts : model.failed ? theme.wn : theme.ok
    }

    private func pill(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: symbol).font(.system(size: 14))
                Text(label)
            }
            .mvText(13, .medium, theme.tp)
            .padding(.horizontal, 12)
            .frame(height: 36)
            .background(theme.sf, in: Capsule())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder private var chapterRows: some View {
        if model.chapters.isEmpty {
            if model.failed {
                EmptyState(
                    symbol: "wifi.slash", title: "Couldn't load chapters",
                    message: "The Mangavault server didn't respond. Check your connection and try again.",
                    button: "Retry", action: { Task { await model.load() } }, top: 40)
            } else {
                ForEach(0..<8, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 6) {
                        theme.shimmer(4).frame(width: 90, height: 14)
                        RoundedRectangle(cornerRadius: 4).fill(theme.sk).frame(width: 180, height: 10)
                    }
                    .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                    .padding(.horizontal, 16)
                    .overlay(alignment: .top) { Rectangle().fill(theme.ln).frame(height: 0.5) }
                }
            }
        } else {
            let list = newestFirst ? Array(model.chapters.reversed()) : model.chapters
            let cur = currentChapter
            let newCutoff = isFav ? progress?.updatedAt : nil
            ForEach(list) { c in
                ChapterRow(
                    chapter: c, current: cur?.id == c.id && progress?.completed != true, progress: progress,
                    read: store.isRead(c),
                    isNew: newCutoff.map { cut in
                        (cur.map { c.index > $0.index } ?? false) && ISO.isLater(c.publishedAt ?? "", than: cut)
                    } ?? false,
                    flash: flash == c.id
                )
                .id(c.id)
                .contentShape(Rectangle())
                .onTapGesture {
                    let page = (cur?.id == c.id && progress?.completed != true) ? (progress?.page ?? 1) : 1
                    app.read(m, chapterId: c.id, page: page, chapters: model.chapters)
                }
                .onLongPressGesture(minimumDuration: 0.5) {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    sheet = .chapter(c)
                }
            }
        }
    }

    // MARK: Sheets

    @ViewBuilder private func sheetView(_ s: DetailsSheet) -> some View {
        switch s {
        case .category:
            ListSheet(
                title: "Library category", sub: m.title,
                items: store.categories.map { c in
                    ListSheetItem(symbol: "folder", label: c, checked: store.library[m.id]?.category == c) {
                        store.setFavorite(m, category: c)
                        app.showToast("Moved to \(c)")
                    }
                } + [
                    ListSheetItem(symbol: "folder.badge.plus", label: "New category…") {
                        Task {
                            try? await Task.sleep(for: .milliseconds(350))
                            sheet = .newCategory
                        }
                    },
                    ListSheetItem(symbol: "heart.slash", label: "Remove from Library", destructive: true) {
                        app.toggleFavorite(m)
                    },
                ])
        case .newCategory:
            NewCategorySheet(hint: "\(m.title) moves into it. Categories sync with your account.") { name in
                store.addCategory(name)
                if isFav { store.setFavorite(m, category: name) }
                app.showToast("Added “\(name)”")
            }
            .environment(app)
        case .jump:
            JumpSheet(latest: model.latest) { n in jump(to: n) }
                .environment(app)
        case .chapter(let c):
            let read = store.isRead(c)
            ListSheet(
                title: "\(Fmt.chapter(c))\(c.title.map { " · \($0)" } ?? "")",
                items: [
                    ListSheetItem(symbol: "play.fill", label: "Read from start") {
                        app.read(m, chapterId: c.id, chapters: model.chapters)
                    },
                    ListSheetItem(symbol: read ? "minus.circle" : "checkmark", label: read ? "Mark as unread" : "Mark as read") {
                        store.setRead([c.id], mangaId: m.id, read: !read)
                    },
                    ListSheetItem(symbol: "checkmark.circle", label: "Mark all previous as read") {
                        markPrevious(c)
                    },
                ])
        }
    }

    private func markPrevious(_ c: Chapter) {
        let ids = model.chapters.filter { $0.index <= c.index }.map(\.id)
        var old: [Int: ReadMark?] = [:]
        for id in ids { old[id] = .some(store.readMarks[id]) }
        store.setRead(ids, mangaId: m.id, read: true)
        let first = model.chapters.first.map(Fmt.chapter) ?? "Ch. 1"
        let range = "\(first.replacingOccurrences(of: "Ch. ", with: ""))–\(Fmt.number(c.number))"
        let s = store, id = m.id
        app.showToast("Chapters \(range) marked read", undo: {
            s.restoreReadMarks(old, mangaId: id)
        })
    }

    private func jump(to n: Int) {
        guard !model.chapters.isEmpty else { return }
        let target = model.chapters.min { a, b in
            abs((a.number ?? Double(a.index + 1)) - Double(n)) < abs((b.number ?? Double(b.index + 1)) - Double(n))
        }
        guard let t = target else { return }
        scrollTarget = t.id
        withAnimation(.easeOut(duration: 0.3)) { flash = t.id }
        Task {
            try? await Task.sleep(for: .seconds(1.4))
            withAnimation(.easeOut(duration: 0.3)) { if flash == t.id { flash = nil } }
        }
    }
}

private struct ChapterRow: View {
    let chapter: Chapter
    let current: Bool
    let progress: ProgressEntry?
    let read: Bool
    let isNew: Bool
    let flash: Bool
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 8) {
                    Text(Fmt.chapter(chapter)).mvText(15, .semibold, theme.tp)
                    if isNew {
                        Text("NEW").mvText(10, .bold, .white).kerning(0.4)
                            .padding(.horizontal, 5).frame(height: 16)
                            .background(theme.ac, in: RoundedRectangle(cornerRadius: 4))
                    }
                }
                let sub = Fmt.chapterSub(chapter)
                if !sub.isEmpty { Text(sub).mvText(13, .regular, theme.ts).lineLimit(1) }
                if current, let p = progress {
                    HStack(spacing: 8) {
                        GeometryReader { g in
                            Capsule().fill(theme.sr).overlay(alignment: .leading) {
                                Capsule().fill(theme.ac).frame(width: g.size.width * p.chapterFraction)
                            }
                        }
                        .frame(height: 3)
                        Text(p.pageCount.map { "p. \(p.page)/\($0)" } ?? "p. \(p.page)").mvText(12, .semibold, theme.ac)
                    }
                    .padding(.top, 4)
                }
            }
            .opacity(read && !current ? 0.45 : 1)
            .frame(maxWidth: .infinity, alignment: .leading)
            if current {
                Image(systemName: "bookmark.fill").font(.system(size: 17)).foregroundStyle(theme.ac)
            } else if read {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 17)).foregroundStyle(theme.ts)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(minHeight: 64)
        .background(flash ? theme.acs : current ? theme.sf : .clear)
        .overlay(alignment: .top) { Rectangle().fill(theme.ln).frame(height: 0.5) }
    }
}

struct JumpSheet: View {
    let latest: Int
    var onJump: (Int) -> Void
    @State private var value = ""
    @FocusState private var focused: Bool
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let theme = app.theme
        FittedSheet(theme: theme) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Jump to chapter").mvText(17, .semibold, theme.tp).padding(.top, 20)
                HStack(spacing: 8) {
                    TextField("", text: $value, prompt: Text("1 – \(max(1, latest))").foregroundColor(theme.ts))
                        .keyboardType(.numberPad)
                        .font(MV.font(17))
                        .foregroundStyle(theme.tp)
                        .focused($focused)
                        .padding(.horizontal, 14)
                        .frame(height: 48)
                        .background(theme.sr, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .onChange(of: value) { _, v in
                            let digits = v.filter(\.isNumber)
                            if digits != v { value = digits }
                        }
                    Button {
                        go(Int(value) ?? 1)
                    } label: {
                        Text("Go").mvText(15, .semibold, .white).padding(.horizontal, 22).frame(height: 48)
                            .background(theme.ac, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                    ForEach(jumpBuckets(latest: latest), id: \.lowerBound) { b in
                        Button {
                            go(b.lowerBound)
                        } label: {
                            Text("\(b.lowerBound)–\(b.upperBound)").mvText(13, .medium, theme.tp).monospacedDigit()
                                .frame(maxWidth: .infinity).frame(height: 44)
                                .background(theme.sr, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
    }

    private func go(_ n: Int) {
        dismiss()
        onJump(max(1, min(max(1, latest), n)))
    }
}

struct NewCategorySheet: View {
    var hint: String
    var onAdd: (String) -> Void
    @State private var name = ""
    @FocusState private var focused: Bool
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let theme = app.theme
        FittedSheet(theme: theme) {
            VStack(alignment: .leading, spacing: 12) {
                Text("New category").mvText(17, .semibold, theme.tp).padding(.top, 20)
                HStack(spacing: 8) {
                    TextField("", text: $name, prompt: Text("e.g. Weekly reads").foregroundColor(theme.ts))
                        .font(MV.font(17))
                        .foregroundStyle(theme.tp)
                        .focused($focused)
                        .submitLabel(.done)
                        .onSubmit(add)
                        .padding(.horizontal, 14)
                        .frame(height: 48)
                        .background(theme.sr, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    Button(action: add) {
                        Text("Add").mvText(15, .semibold, .white).padding(.horizontal, 22).frame(height: 48)
                            .background(theme.ac, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                Text(hint).mvText(13, .regular, theme.ts)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
        .onAppear { focused = true }
    }

    private func add() {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        dismiss()
        onAdd(n)
    }
}
