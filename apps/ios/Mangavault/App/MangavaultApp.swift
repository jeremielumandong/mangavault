import MangavaultKit
import SwiftUI

@main
struct MangavaultApp: App {
    @State private var app = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .environment(\.theme, app.theme)
                .preferredColorScheme(app.theme.colorScheme)
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let theme = app.theme
        ZStack {
            theme.bg.ignoresSafeArea()
            if app.store.onboardingDone {
                tabs.transition(.opacity)
            } else {
                OnboardingView().transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.25), value: app.store.onboardingDone)
        .tint(theme.ac)
        // Global sheets; while the reader is up it presents them itself.
        .sheet(item: Binding(get: { app.reader == nil ? app.sheet : nil }, set: { app.sheet = $0 })) { sheet in
            AppSheetView(sheet: sheet).environment(app).environment(\.theme, theme)
        }
        .fullScreenCover(item: Binding(get: { app.reader }, set: { app.reader = $0 })) { req in
            ReaderView(request: req, app: app).environment(app)
        }
        .toastHost(app, bottom: 61)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { app.refreshOnForeground() }
        }
    }

    private var tabs: some View {
        let theme = app.theme
        return TabView(selection: Binding(get: { app.tab }, set: { app.go($0) })) {
            stack(.home) { HomeView() }
                .tabItem { Label("Home", systemImage: "house") }
                .tag(Tab.home)
            stack(.search) { SearchView() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .tag(Tab.search)
            stack(.library) { LibraryView() }
                .tabItem { Label("Library", systemImage: "books.vertical") }
                .tag(Tab.library)
            stack(.history) { HistoryView() }
                .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }
                .tag(Tab.history)
            stack(.settings) { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .toolbarBackground(theme.tb, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
    }

    private func stack<C: View>(_ tab: Tab, @ViewBuilder root: () -> C) -> some View {
        NavigationStack(path: app.path(tab)) {
            root()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .details(let m): DetailsView(manga: m, app: app)
                    case .licenses: LicensesView()
                    }
                }
        }
    }
}

/// Sign-in and merge, shown from the tabs or from the reader.
struct AppSheetView: View {
    let sheet: AppSheet

    var body: some View {
        switch sheet {
        case .signIn(let fromFavorite): SignInSheet(fromFavorite: fromFavorite)
        case .merge: MergeSheet()
        }
    }
}
