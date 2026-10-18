import MangavaultKit
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    @Environment(\.openURL) private var openURL
    @State private var cacheSize: Int64?

    private var store: LocalStore { app.store }
    private var s: AppSettings { store.settings }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(title: "Settings").padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 12)
                accountCard
                label("Appearance")
                Segmented(
                    options: ThemeKind.allCases.map { ($0, $0.label) }, selection: s.theme,
                    onSelect: { store.settings.theme = $0 }
                )
                .background(theme.sf, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(.horizontal, 16)

                section("Library") {
                    row("square.grid.2x2", "Grid columns", value: "\(s.libCols)", first: true) {
                        store.settings.libCols = cycle([2, 3, 4], s.libCols)
                    }
                    row("list.bullet", "Default view", value: s.libView == .grid ? "Grid" : "List") {
                        store.settings.libView = s.libView == .grid ? .list : .grid
                    }
                }
                section("Reader defaults") {
                    row("book", "Reading mode", value: s.readerDefault?.name ?? "By title type", first: true) {
                        let all: [ReaderMode?] = [nil] + ReaderMode.allCases.map { Optional($0) }
                        let i = all.firstIndex(of: s.readerDefault) ?? 0
                        store.settings.readerDefault = all[(i + 1) % all.count]
                    }
                    row("circle.lefthalf.filled", "Background", value: s.readerBackground.rawValue) {
                        store.settings.readerBackground = cycle(ReaderBackground.allCases, s.readerBackground)
                    }
                    row("arrow.up.left.and.arrow.down.right", "Fit", value: s.fit.rawValue) {
                        store.settings.fit = cycle(ReaderFit.allCases, s.fit)
                    }
                    toggle("sun.max", "Keep screen on", s.keepScreenOn) { store.settings.keepScreenOn.toggle() }
                    toggle("number", "Show page number", s.showPageNumber) { store.settings.showPageNumber.toggle() }
                    toggle("arrow.left.arrow.right", "Invert tap zones", s.invertTaps) { store.settings.invertTaps.toggle() }
                }
                section("Data") {
                    row("photo.on.rectangle", "Image cache", value: cacheSize.map(Fmt.bytes) ?? "…", first: true) {
                        Task {
                            await ImagePipeline.shared.clear()
                            cacheSize = 0
                            app.showToast("Cache cleared")
                        }
                    }
                    row("square.and.arrow.down", "Prefetch pages", value: "\(s.prefetch)") {
                        store.settings.prefetch = cycle([3, 5, 8, 10], s.prefetch)
                    }
                    toggle("antenna.radiowaves.left.and.right", "Data saver on cellular", s.dataSaver) {
                        store.settings.dataSaver.toggle()
                    }
                }
                section("About") {
                    row("bubble.left", "Send feedback", chevron: true, first: true) {
                        if let u = URL(string: "mailto:?subject=Mangavault%20feedback%20(\(AppConfig.version.replacingOccurrences(of: " ", with: "%20")))") {
                            openURL(u)
                        }
                    }
                    NavigationLink(value: Route.licenses) {
                        rowLabel("doc.text", "Open-source licenses", value: nil, chevron: true)
                    }
                    .buttonStyle(.plain)
                    rowLabel("info.circle", "Version", value: AppConfig.version, chevron: false)
                }
                Text("Mangavault \(AppConfig.version)").mvText(12, .regular, theme.ts)
                    .frame(maxWidth: .infinity).padding(20)
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(theme.bg)
        .toolbar(.hidden, for: .navigationBar)
        .task { cacheSize = await ImagePipeline.shared.diskSize() }
    }

    // MARK: Account

    private var accountCard: some View {
        HStack(spacing: 12) {
            Text(avatarText)
                .mvText(18, .bold, .white)
                .frame(width: 48, height: 48)
                .background(app.isSignedIn ? Color(hue: 0.07, saturation: 0.6, brightness: 0.75) : theme.sr, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(accTitle).mvText(15, .semibold, theme.tp)
                Text(accSub).mvText(12, .regular, theme.ts).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                if app.isSignedIn { app.signOut() } else { app.sheet = .signIn(fromFavorite: false) }
            } label: {
                Text(app.isSignedIn ? "Sign out" : "Sign in")
                    .mvText(13, .semibold, app.isSignedIn ? theme.tp : .white)
                    .padding(.horizontal, 14)
                    .frame(height: 36)
                    .background(app.isSignedIn ? theme.sr : theme.ac, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(14)
        .background(theme.sf, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal, 16)
    }

    private var accTitle: String {
        guard let a = store.account else { return "Not signed in" }
        return a.user.name ?? a.user.email ?? "Signed in"
    }

    private var avatarText: String {
        guard let a = store.account else { return "?" }
        return String((a.user.name ?? a.user.email ?? "M").prefix(1)).uppercased()
    }

    private var accSub: String {
        guard let a = store.account else { return "Favorites and progress stay on this phone" }
        guard let d = store.lastSyncedAt else { return "\(a.provider) · not synced yet" }
        if Date().timeIntervalSince(d) < 60 { return "\(a.provider) · synced just now" }
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .full
        return "\(a.provider) · synced \(f.localizedString(for: d, relativeTo: Date()))"
    }

    // MARK: Rows

    private func cycle<T: Equatable>(_ all: [T], _ cur: T) -> T {
        let i = all.firstIndex(of: cur) ?? -1
        return all[(i + 1) % all.count]
    }

    private func label(_ t: String) -> some View {
        Text(t).mvText(13, .regular, theme.ts).padding(.horizontal, 16).padding(.top, 20).padding(.bottom, 6)
    }

    private func section<C: View>(_ title: String, @ViewBuilder rows: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            label(title)
            VStack(spacing: 0) {
                rows()
            }
            .background(theme.sf, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .padding(.horizontal, 16)
        }
    }

    private func rowLabel(
        _ symbol: String, _ title: String, value: String?, chevron: Bool, toggle: Bool? = nil, first: Bool = false
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).font(.system(size: 17)).foregroundStyle(theme.ts).frame(width: 22)
            Text(title).mvText(15, .regular, theme.tp).frame(maxWidth: .infinity, alignment: .leading)
            if let value { Text(value).mvText(15, .regular, theme.ts) }
            if let toggle { MVSwitch(isOn: toggle) }
            if chevron { Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(theme.ts) }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
        .overlay(alignment: .top) {
            if !first { Rectangle().fill(theme.ln).frame(height: 0.5).padding(.leading, 48) }
        }
    }

    private func row(
        _ symbol: String, _ title: String, value: String? = nil, chevron: Bool = false, first: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) { rowLabel(symbol, title, value: value, chevron: chevron, first: first) }
            .buttonStyle(.plain)
    }

    private func toggle(_ symbol: String, _ title: String, _ on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { rowLabel(symbol, title, value: nil, chevron: false, toggle: on) }
            .buttonStyle(.plain)
            .accessibilityValue(on ? "On" : "Off")
    }
}

struct LicensesView: View {
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss

    private var text: String {
        guard let u = Bundle.main.url(forResource: "OFL", withExtension: "txt"), let s = try? String(contentsOf: u, encoding: .utf8)
        else { return "" }
        return s
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Fonts").mvText(17, .semibold, theme.tp)
                Text(text).font(.system(size: 12, design: .monospaced)).foregroundStyle(theme.ts).textSelection(.enabled)
            }
            .padding(16)
        }
        .background(theme.bg)
        .navigationTitle("Open-source licenses")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
    }
}
