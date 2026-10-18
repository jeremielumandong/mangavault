import MangavaultKit
import SwiftUI
import UIKit

/// The reader: the webtoon strip or the pager, with the controls overlay,
/// the more menu and the reader's sheets on top.
struct ReaderView: View {
    @State private var model: ReaderModel
    @State private var slider: Double = 1

    init(request: ReaderRequest, app: AppModel) {
        _model = State(initialValue: ReaderModel(request: request, app: app))
    }

    private let barText = Color(hex: 0xF2F2F5)
    private let barSub = Color(hex: 0xA0A0AB)

    var body: some View {
        let app = model.app
        let s = model.settings
        ZStack {
            model.palette.bg.ignoresSafeArea()
            content.ignoresSafeArea()

            if s.dim && s.brightness < 100 {
                Color.black.opacity((100 - s.brightness) / 100 * 0.8)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }

            if model.phase == .ready && !model.controls {
                indicators
            }

            VStack(spacing: 0) {
                topBar
                Spacer(minLength: 0)
                if model.phase == .ready { bottomBar }
            }
            .opacity(model.controls || model.phase != .ready ? 1 : 0)
            .allowsHitTesting(model.controls || model.phase != .ready)
            .animation(.easeOut(duration: 0.15), value: model.controls)

            if model.moreMenu { moreMenu }
        }
        .statusBarHidden(!model.controls && model.phase == .ready)
        .persistentSystemOverlays(model.controls ? .automatic : .hidden)
        .environment(\.theme, app.theme)
        .task { await model.start() }
        .onAppear {
            slider = Double(model.page)
            UIApplication.shared.isIdleTimerDisabled = s.keepScreenOn
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .onChange(of: s.keepScreenOn) { _, on in UIApplication.shared.isIdleTimerDisabled = on }
        .onChange(of: model.page) { _, p in if !model.dragging { slider = Double(p) } }
        .onChange(of: model.chapterId) { _, _ in if !model.dragging { slider = Double(model.page) } }
        .sheet(item: $model.sheet) { sheet in
            readerSheet(sheet).environment(\.theme, app.theme).environment(app)
        }
        .background {
            // Sign-in (from "Add to favorites") while the reader is up.
            Color.clear.sheet(item: Binding(get: { app.sheet }, set: { app.sheet = $0 })) { sheet in
                AppSheetView(sheet: sheet).environment(app).environment(\.theme, app.theme)
            }
        }
        .toastHost(app, bottom: 150)
    }

    // MARK: Content

    @ViewBuilder private var content: some View {
        let s = model.settings
        switch model.phase {
        case .loading:
            ReaderLoadingView(palette: model.palette)
                .contentShape(Rectangle())
                .onTapGesture { model.toggleControls() }
        case .error:
            ReaderErrorView(model: model)
        case .ready:
            if model.mode == .vertical {
                WebtoonReader(
                    model: model, itemCount: model.items.count, scrollToken: model.scrollRequest.token,
                    reloadToken: model.reload?.token ?? 0, background: s.readerBackground, pageGap: s.pageGap)
            } else {
                PagedReader(
                    model: model, position: model.position, itemCount: model.items.count,
                    reloadToken: model.reload?.token ?? 0, background: s.readerBackground, fit: model.fit,
                    guided: s.guided
                )
                .id(model.mode)
            }
        }
    }

    /// Thin progress line on top and the page pill, while the controls are hidden.
    private var indicators: some View {
        let n = max(1, model.pageCount)
        let onPage = model.items.indices.contains(model.position) && model.items[model.position].isPage
        return ZStack {
            VStack {
                GeometryReader { g in
                    Rectangle().fill(model.app.theme.ac)
                        .frame(width: g.size.width * CGFloat(min(model.page, n)) / CGFloat(n), height: 2)
                }
                .frame(height: 2)
                Spacer()
            }
            if model.settings.showPageNumber && onPage {
                VStack {
                    Spacer()
                    Text("\(model.page) / \(n)")
                        .font(MV.font(11, .semibold)).monospacedDigit()
                        .foregroundStyle(Color(hex: 0xE6E6EA))
                        .padding(.horizontal, 10)
                        .frame(height: 22)
                        .background(Color.black.opacity(0.55), in: Capsule())
                        .padding(.bottom, 24)
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    // MARK: Bars

    private var barBackground: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial)
            Color(hex: 0x0C0C0E, alpha: 0.6)
        }
        .environment(\.colorScheme, .dark)
    }

    private func barButton(_ symbol: String, _ label: String, size: CGFloat = 22, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: size, weight: .medium)).foregroundStyle(barText)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var topBar: some View {
        HStack(spacing: 2) {
            barButton("chevron.backward", "Back", size: 22) { model.close() }
            VStack(alignment: .leading, spacing: 0) {
                Text(model.manga.title).font(MV.font(15, .semibold)).foregroundStyle(barText).lineLimit(1)
                Text(model.chapterTitle(model.chapterId)).font(MV.font(12, .regular)).foregroundStyle(barSub).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            barButton("list.bullet", "Chapters") {
                model.moreMenu = false
                model.sheet = .chapters
            }
            .disabled(model.chapters.isEmpty && model.loaded.isEmpty)
            barButton(model.moreMenu ? "xmark" : "ellipsis", "More") { model.moreMenu.toggle() }
        }
        .padding(.horizontal, 4)
        .frame(height: 52)
        .background(barBackground.ignoresSafeArea(edges: .top))
    }

    private var bottomBar: some View {
        let n = model.pageCount
        let prev = model.prevChapter(before: model.chapterId)
        let next = model.nextChapter(after: model.chapterId)
        let shown = model.dragging ? model.dragPage : model.page
        return VStack(spacing: 8) {
            HStack(spacing: 12) {
                Text("\(shown) / \(max(1, n))")
                    .font(MV.font(13, .semibold)).monospacedDigit()
                    .foregroundStyle(barText)
                    .frame(minWidth: 52, alignment: .leading)
                pageSlider(count: n)
            }
            .padding(.horizontal, 4)
            HStack(spacing: 8) {
                chapterButton(
                    prev.map(Fmt.chapter) ?? "First", symbol: "backward.end.fill", leading: true, enabled: prev != nil
                ) {
                    if let prev { model.goToChapter(prev.id) }
                }
                Button {
                    model.moreMenu = false
                    model.sheet = .settings
                } label: {
                    Image(systemName: "slider.horizontal.3").font(.system(size: 19, weight: .medium)).foregroundStyle(barText)
                        .frame(width: 56, height: 44)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Reader settings")
                chapterButton(
                    next.map(Fmt.chapter) ?? "Latest", symbol: "forward.end.fill", leading: false, enabled: next != nil
                ) {
                    if let next { model.goToChapter(next.id) }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 12)
        .padding(.bottom, 4)
        .background(barBackground.ignoresSafeArea(edges: .bottom))
    }

    private func chapterButton(
        _ title: String, symbol: String, leading: Bool, enabled: Bool, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if leading { Image(systemName: symbol).font(.system(size: 14)) }
                Text(title).font(MV.font(13, .semibold)).lineLimit(1)
                if !leading { Image(systemName: symbol).font(.system(size: 14)) }
            }
            .foregroundStyle(barText)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .opacity(enabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private func pageSlider(count n: Int) -> some View {
        let rtl = model.mode == .rtl
        return Slider(
            value: $slider, in: 1...Double(max(2, n)), step: 1,
            onEditingChanged: { editing in
                if editing {
                    model.dragPage = Int(slider.rounded())
                    model.dragging = true
                } else {
                    model.dragging = false
                    model.setPage(Int(slider.rounded()))
                }
            }
        )
        .tint(model.app.theme.ac)
        .disabled(n < 2)
        .environment(\.layoutDirection, rtl ? .rightToLeft : .leftToRight)
        .onChange(of: slider) { _, v in model.dragPage = Int(v.rounded()) }
        .frame(height: 28)
        .overlay(alignment: .bottomLeading) {
            if model.dragging && n > 1 {
                GeometryReader { g in
                    let t = CGFloat(model.dragPage - 1) / CGFloat(n - 1)
                    let x = (rtl ? 1 - t : t) * (g.size.width - 24) + 12
                    thumbnail
                        .position(x: x, y: -62)
                }
                .allowsHitTesting(false)
            }
        }
    }

    private var thumbnail: some View {
        let lc = model.loadedChapter(model.chapterId)
        let p = model.dragPage
        let page = lc.flatMap { (p >= 1 && p <= $0.pages.count) ? $0.pages[p - 1] : nil }
        return VStack(spacing: 4) {
            RemoteImage(url: page?.url, headers: lc?.headers ?? [:], width: 64) {
                model.palette.sk
            }
            .frame(width: 64, height: 96)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 6, style: .continuous).stroke(.white, lineWidth: 2))
            .shadow(color: .black.opacity(0.5), radius: 10, y: 6)
            Text("\(p)").font(MV.font(12, .bold)).foregroundStyle(.white)
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(.black, in: RoundedRectangle(cornerRadius: 6))
        }
    }

    private var moreMenu: some View {
        ZStack(alignment: .topTrailing) {
            Color.clear.contentShape(Rectangle())
                .onTapGesture { model.moreMenu = false }
            VStack(spacing: 0) {
                menuRow("square.and.arrow.up", "Share chapter", first: true) { model.shareChapter() }
                menuRow(model.isFavorite ? "heart.fill" : "heart", model.isFavorite ? "In your Library" : "Add to favorites") {
                    model.favorite()
                }
            }
            .frame(width: 220)
            .background(Color(hex: 0x26262E), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.5), radius: 20, y: 12)
            .padding(.top, 50)
            .padding(.trailing, 10)
            .transition(.opacity)
        }
    }

    private func menuRow(_ symbol: String, _ title: String, first: Bool = false, action: @escaping () -> Void) -> some View {
        Button {
            model.moreMenu = false
            action()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: symbol).font(.system(size: 17)).frame(width: 22)
                Text(title).font(MV.font(15, .regular))
                Spacer(minLength: 0)
            }
            .foregroundStyle(barText)
            .padding(.horizontal, 14)
            .frame(height: 46)
            .contentShape(Rectangle())
            .overlay(alignment: .top) {
                if !first { Rectangle().fill(Color.white.opacity(0.08)).frame(height: 0.5) }
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: Sheets

    @ViewBuilder private func readerSheet(_ sheet: ReaderSheet) -> some View {
        switch sheet {
        case .settings:
            ReaderSettingsSheet(model: model)
        case .chapters:
            chapterList
        case .pageMenu(let item):
            let p: Int = {
                if case .page(_, let p, _) = item.slot { return p }
                return model.page
            }()
            ListSheet(
                title: "Page \(p)",
                items: [
                    ListSheetItem(symbol: "square.and.arrow.down", label: "Save image") { model.savePage(item) },
                    ListSheetItem(symbol: "square.and.arrow.up", label: "Share page") { model.sharePage(item) },
                    ListSheetItem(symbol: "arrow.clockwise", label: "Reload page") { model.reloadPage(item) },
                ])
        }
    }

    private var chapterList: some View {
        let store = model.app.store
        let all = model.chapters.isEmpty ? model.loaded.map(\.chapter) : model.chapters
        let list = all.sorted { $0.index > $1.index }
        let current = model.chapterId
        let items = list.map { c in
            let cur = c.id == current
            return ListSheetItem(
                symbol: cur ? "play.circle.fill" : store.isRead(c) ? "checkmark.circle.fill" : "circle",
                label: Fmt.chapter(c), sub: Fmt.chapterSub(c), highlighted: cur
            ) { model.goToChapter(c.id) }
        }
        return ListSheet(
            title: model.manga.title, sub: Fmt.plural(list.count, "chapter"), items: items,
            scrollTo: list.firstIndex { $0.id == current })
    }
}

/// Mode, background, fit, brightness and toggles. The mode is kept per
/// title; the rest are the reader defaults from Settings.
struct ReaderSettingsSheet: View {
    let model: ReaderModel

    var body: some View {
        let theme = model.app.theme
        let store = model.app.store
        let s = store.settings
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("Reader settings").mvText(17, .semibold, theme.tp)
                    Spacer()
                    Text("Mode is saved for this title").mvText(12, .regular, theme.ts)
                }
                .padding(.top, 22)

                label("Mode", theme).padding(.top, 6)
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                    ForEach(ReaderMode.allCases) { m in
                        let on = m == model.mode
                        Button {
                            model.setMode(m)
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: icon(m)).font(.system(size: 17)).foregroundStyle(theme.tp).frame(width: 22)
                                Text(m.name).mvText(13, .semibold, theme.tp).lineLimit(1).minimumScaleFactor(0.8)
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 12)
                            .frame(height: 48)
                            .background(on ? theme.acs : theme.sr, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12, style: .continuous)
                                    .stroke(on ? theme.ac : .clear, lineWidth: 1.5))
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }

                label("Background", theme)
                HStack(spacing: 12) {
                    ForEach(ReaderBackground.allCases, id: \.self) { b in
                        let on = b == s.readerBackground
                        Button {
                            store.settings.readerBackground = b
                        } label: {
                            VStack(spacing: 6) {
                                Circle().fill(ReaderPalette.of(b).bg).frame(width: 44, height: 44)
                                    .overlay(Circle().stroke(Color.gray.opacity(0.4), lineWidth: 1))
                                    .overlay(Circle().stroke(on ? theme.ac : .clear, lineWidth: 2).padding(-4))
                                Text(b.rawValue).mvText(12, .regular, theme.tp)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }

                label("Fit", theme)
                Segmented(
                    options: ReaderFit.allCases.map { ($0, $0.rawValue) }, selection: s.fit,
                    onSelect: { store.settings.fit = $0 }
                )
                .background(theme.bg, in: RoundedRectangle(cornerRadius: 12, style: .continuous))

                label("Brightness", theme)
                HStack(spacing: 10) {
                    Image(systemName: "sun.min").foregroundStyle(theme.ts)
                    Slider(
                        value: Binding(get: { store.settings.brightness }, set: { store.settings.brightness = $0 }),
                        in: 20...100
                    )
                    .tint(theme.ac)
                    Image(systemName: "sun.max").foregroundStyle(theme.ts)
                }

                VStack(spacing: 0) {
                    toggle("Guided view (panel by panel)", s.guided, theme, first: true) { store.settings.guided.toggle() }
                    toggle("Page gap (webtoon)", s.pageGap, theme) { store.settings.pageGap.toggle() }
                    toggle("Dim overlay", s.dim, theme) { store.settings.dim.toggle() }
                    toggle("Keep screen on", s.keepScreenOn, theme) { store.settings.keepScreenOn.toggle() }
                    toggle("Show page number", s.showPageNumber, theme) { store.settings.showPageNumber.toggle() }
                    toggle("Invert tap zones", s.invertTaps, theme) { store.settings.invertTaps.toggle() }
                }
                .background(theme.sr, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(.top, 14)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 20)
        }
        .scrollIndicators(.hidden)
        .presentationDetents([.fraction(0.54), .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(16)
        .presentationBackground(theme.sf)
        .presentationBackgroundInteraction(.enabled(upThrough: .fraction(0.54)))
        .environment(\.theme, theme)
        .preferredColorScheme(theme.colorScheme)
    }

    private func icon(_ m: ReaderMode) -> String {
        switch m {
        case .vertical: return "rectangle.split.1x2"
        case .rtl: return "book"
        case .ltr: return "book.closed"
        case .pagedV: return "arrow.up.arrow.down"
        }
    }

    private func label(_ t: String, _ theme: Theme) -> some View {
        Text(t).mvText(13, .regular, theme.ts).padding(.top, 16).padding(.bottom, 8)
    }

    private func toggle(_ title: String, _ on: Bool, _ theme: Theme, first: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).mvText(15, .regular, theme.tp)
                Spacer()
                MVSwitch(isOn: on)
            }
            .padding(.horizontal, 14)
            .frame(height: 50)
            .contentShape(Rectangle())
            .overlay(alignment: .top) { if !first { Rectangle().fill(theme.ln).frame(height: 0.5) } }
        }
        .buttonStyle(.plain)
        .accessibilityValue(on ? "On" : "Off")
    }
}
