import MangavaultKit
import SwiftUI

struct SearchView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    @FocusState private var focused: Bool
    @State private var showFilters = false

    private var model: SearchModel { app.search }

    var body: some View {
        @Bindable var model = app.search
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0, pinnedViews: [.sectionHeaders]) {
                ScreenTitle(title: "Search").padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 8)
                Section {
                    if model.showsIdle {
                        recents
                    } else if model.phase == .loading {
                        Text(model.slow ? "Few matches in the catalog, asking the sites directly…" : "Searching…")
                            .mvText(13, .regular, theme.ts)
                            .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 10)
                        SkeletonGrid()
                    } else {
                        results
                    }
                } header: {
                    searchBar($model.query)
                }
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.immediately)
        .background(theme.bg)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showFilters) {
            FilterSheet(initial: model.filters).environment(app)
        }
        .task { await model.loadGenres() }
        .onChange(of: app.tab) { _, t in
            if t == .search && model.showsIdle { focused = true }
        }
    }

    private func searchBar(_ query: Binding<String>) -> some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").font(.system(size: 18)).foregroundStyle(theme.ts)
                TextField("", text: query, prompt: Text("Titles, authors, genres").foregroundColor(theme.ts))
                    .font(MV.font(16))
                    .foregroundStyle(theme.tp)
                    .focused($focused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onSubmit { model.submit() }
                    .onChange(of: query.wrappedValue) { _, _ in model.queryChanged() }
                if !model.query.isEmpty {
                    Button {
                        model.clear()
                        focused = true
                    } label: {
                        Image(systemName: "xmark.circle.fill").font(.system(size: 17)).foregroundStyle(theme.ts)
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Clear")
                }
            }
            .padding(.leading, 12)
            .padding(.trailing, 8)
            .frame(height: 44)
            .background(theme.sf, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            Button {
                focused = false
                showFilters = true
            } label: {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 18))
                    .foregroundStyle(theme.tp)
                    .frame(width: 44, height: 44)
                    .background(theme.sf, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(alignment: .topTrailing) {
                        if model.filters.count > 0 {
                            Text("\(model.filters.count)")
                                .mvText(10, .bold, .white)
                                .frame(minWidth: 16, minHeight: 16)
                                .background(theme.ac, in: Capsule())
                                .padding(6)
                        }
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Filters")
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .background(theme.bg)
    }

    @ViewBuilder private var recents: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Recent searches").mvText(17, .semibold, theme.tp)
            Spacer()
            Button { app.store.clearRecents() } label: { Text("Clear").mvText(15, .regular, theme.ac) }
                .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 4)
        if app.store.recents.isEmpty {
            Text("Your searches will show up here.").mvText(15, .regular, theme.ts).padding(.horizontal, 16).padding(.vertical, 8)
        }
        ForEach(app.store.recents, id: \.self) { r in
            HStack(spacing: 12) {
                Image(systemName: "clock.arrow.circlepath").font(.system(size: 17)).foregroundStyle(theme.ts)
                Button {
                    focused = false
                    model.use(r)
                } label: {
                    Text(r).mvText(15, .regular, theme.tp).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Button {
                    app.store.removeRecent(r)
                } label: {
                    Image(systemName: "xmark").font(.system(size: 15)).foregroundStyle(theme.ts).frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Remove")
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .frame(minHeight: 48)
        }
    }

    @ViewBuilder private var results: some View {
        let label = "\(Fmt.plural(model.results.count, "result"))\(model.hasMore ? "+" : "") · \(model.filters.sort.rawValue)"
        if model.failed {
            EmptyState(
                symbol: "wifi.slash", title: "Couldn't search",
                message: "The Mangavault server didn't respond. Check your connection and try again.",
                button: "Retry", action: { model.run(debounce: false) }, top: 60)
        } else if model.results.isEmpty {
            EmptyState(
                title: model.trimmed.isEmpty ? "No titles match these filters" : "No matches for “\(model.trimmed)”",
                message: "Try a shorter title, or loosen your filters.", button: "Clear filters",
                action: { model.resetFilters() }, top: 60)
        } else {
            Text(label).mvText(13, .regular, theme.ts).padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 10)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12, alignment: .top), count: 3), spacing: 16) {
                ForEach(model.results) { m in
                    Button {
                        if !model.trimmed.isEmpty { app.store.addRecent(model.trimmed) }
                        app.open(m)
                    } label: {
                        ResultCell(manga: m)
                    }
                    .buttonStyle(.plain)
                    .onAppear { if m.id == model.results.last?.id { model.loadMore() } }
                }
            }
            .padding(.horizontal, 16)
            if model.loadingMore {
                ProgressView().tint(theme.ts).frame(maxWidth: .infinity).padding(.top, 16)
            }
            if !model.trimmed.isEmpty && model.results.count < 6 && !model.hasMore {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle").font(.system(size: 16))
                    Text("Checked every site · that's everything for “\(model.trimmed)”")
                }
                .mvText(13, .regular, theme.ts)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 16)
                .padding(.top, 20)
            }
        }
    }
}

private struct ResultCell: View {
    let manga: MangaSummary
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            GeometryReader { g in
                CoverImage(manga: manga, width: g.size.width, shade: false)
            }
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            Text(manga.title)
                .mvText(13, .semibold, theme.tp)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .padding(.top, 2)
            Text("\(manga.typeLabel) · \(manga.statusLabel)").mvText(11, .regular, theme.ts).lineLimit(1)
        }
        .contentShape(Rectangle())
    }
}

struct FilterSheet: View {
    let initial: SearchFilters
    @State private var draft = SearchFilters()
    @State private var count: (Int, Bool)?
    @State private var countKey = 0
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    private var theme: Theme { app.theme }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("Filters").mvText(17, .semibold, theme.tp)
                    Spacer()
                    Button { draft = SearchFilters() } label: { Text("Reset").mvText(15, .regular, theme.ac) }
                        .buttonStyle(.plain)
                        .frame(height: 44)
                }
                .padding(.top, 16)
                .padding(.bottom, 8)

                group("Genre", note: "Tap once to include, twice to exclude.") {
                    ForEach(app.search.genres, id: \.self) { g in
                        let inc = draft.include.contains(g)
                        let exc = draft.exclude.contains(g)
                        Button {
                            draft.cycle(g)
                        } label: {
                            HStack(spacing: 4) {
                                if inc || exc { Image(systemName: inc ? "checkmark" : "nosign").font(.system(size: 13, weight: .semibold)) }
                                Text(g).strikethrough(exc)
                            }
                            .mvText(13, .medium, inc ? theme.bg : exc ? theme.ac : theme.tp)
                            .padding(.horizontal, 12)
                            .frame(height: 34)
                            .background(inc ? theme.tp : exc ? theme.acs : theme.sr, in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
                group("Status") {
                    ForEach(SearchFilters.statuses, id: \.self) { s in
                        chip(s, on: (draft.status ?? "Any") == s) { draft.status = s == "Any" ? nil : s }
                    }
                }
                group("Type") {
                    ForEach(SearchFilters.types, id: \.self) { s in
                        chip(s, on: (draft.type ?? "Any") == s) { draft.type = s == "Any" ? nil : s }
                    }
                }
                group("Sort by") {
                    ForEach(SortOption.allCases, id: \.self) { s in
                        chip(s.rawValue, on: draft.sort == s) { draft.sort = s }
                    }
                }
                PrimaryButton(title: applyLabel, height: 50, fontSize: 16) {
                    app.search.apply(draft)
                    dismiss()
                }
                .padding(.top, 20)
                .padding(.bottom, 12)
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
        .onAppear { draft = initial }
        .task(id: draft) {
            count = nil
            try? await Task.sleep(for: .milliseconds(300))
            if Task.isCancelled { return }
            count = await app.search.count(for: draft)
        }
        .sheetStyle(theme, height: 600)
    }

    private var applyLabel: String {
        guard let c = count else { return "Show titles" }
        let (n, more) = c
        return "Show \(n)\(more ? "+" : "") title\(n == 1 && !more ? "" : "s")"
    }

    private func group<C: View>(_ title: String, note: String? = nil, @ViewBuilder chips: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).mvText(13, .regular, theme.ts).padding(.top, 12).padding(.bottom, 8)
            FlowLayout(spacing: 8) { chips() }
            if let note { Text(note).mvText(12, .regular, theme.ts).padding(.top, 6) }
        }
    }

    private func chip(_ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .mvText(13, .medium, on ? theme.bg : theme.tp)
                .padding(.horizontal, 12)
                .frame(height: 34)
                .background(on ? theme.tp : theme.sr, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Wrapping row layout for chips.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                x = 0
                y += rowH + spacing
                rowH = 0
            }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            rowH = max(rowH, size.height)
        }
        return CGSize(width: proposal.width ?? maxX, height: y + rowH)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowH + spacing
                rowH = 0
            }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowH = max(rowH, size.height)
        }
    }
}
