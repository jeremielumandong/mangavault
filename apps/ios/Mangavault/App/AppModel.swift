import AuthenticationServices
import Foundation
import MangavaultKit
import Observation
import SwiftUI

enum Tab: Hashable { case home, search, library, history, settings }

enum Route: Hashable {
    case details(MangaSummary)
    case licenses
}

/// What the reader opens on.
struct ReaderRequest: Identifiable, Equatable {
    let id = UUID()
    var manga: MangaSummary
    var chapterId: Int
    /// 1-based.
    var page: Int
    var chapters: [Chapter]?
    var showControls = false
}

enum AppSheet: Identifiable, Equatable {
    /// `fromFavorite`: "Saved to this phone. Sync it everywhere?"
    case signIn(fromFavorite: Bool)
    case merge

    var id: String {
        switch self {
        case .signIn(let f): return "signin\(f)"
        case .merge: return "merge"
        }
    }
}

struct Toast: Identifiable, Equatable {
    let id = UUID()
    var message: String
    var hasUndo: Bool

    static func == (a: Toast, b: Toast) -> Bool { a.id == b.id }
}

@Observable
@MainActor
final class AppModel {
    let api: APIClient
    let store: LocalStore
    let sync: SyncEngine
    let network = NetworkStatus()
    @ObservationIgnored let auth: AuthService

    var tab: Tab = .home
    var paths: [Tab: NavigationPath] = [:]
    var sheet: AppSheet?
    var reader: ReaderRequest?
    var toast: Toast?
    var signingIn = false
    let search: SearchModel
    let home: HomeModel

    @ObservationIgnored private var undoAction: (() -> Void)?
    @ObservationIgnored private var commitAction: (() -> Void)?
    @ObservationIgnored private var toastTask: Task<Void, Never>?
    @ObservationIgnored var chapterCache: [Int: [Chapter]] = [:]
    @ObservationIgnored var detailCache: [Int: MangaDetail] = [:]

    init() {
        api = APIClient(baseURL: AppConfig.baseURL)
        store = LocalStore()
        sync = SyncEngine(api: api, store: store)
        auth = AuthService(api: api)
        search = SearchModel(api: api)
        home = HomeModel(api: api)
        if store.account != nil {
            api.token = Keychain.get("token")
            if api.token == nil { store.signedOut() }
        }
        search.store = store
        home.store = store
        store.onChange = { [weak self] in self?.sync.scheduleSoon() }
        sync.onUnauthorized = { [weak self] in
            self?.signOut(silently: true)
            if AppConfig.openAccount {
                Task { await self?.ensureOpenSession() }
            } else {
                self?.showToast("You were signed out · local data kept")
            }
        }
        if AppConfig.openAccount { Task { await ensureOpenSession() } }
    }

    var theme: Theme { Theme.of(store.settings.theme) }
    var isSignedIn: Bool { store.account != nil && api.token != nil }

    func path(_ tab: Tab) -> Binding<NavigationPath> {
        Binding(get: { self.paths[tab] ?? NavigationPath() }, set: { self.paths[tab] = $0 })
    }

    func open(_ manga: MangaSummary) {
        store.remember([manga])
        paths[tab, default: NavigationPath()].append(Route.details(manga))
    }

    func go(_ t: Tab) {
        if tab == t { paths[t] = NavigationPath() }
        tab = t
    }

    /// "See all" and "Browse popular titles".
    func browse(sort: SortOption, genre: String? = nil) {
        var f = SearchFilters()
        f.sort = sort
        if let genre { f.include = [genre] }
        search.startBrowse(filters: f)
        paths[.search] = NavigationPath()
        tab = .search
    }

    // MARK: Reader

    func read(_ manga: MangaSummary, chapterId: Int, page: Int = 1, chapters: [Chapter]? = nil, controls: Bool = false) {
        store.remember(manga, force: true)
        reader = ReaderRequest(
            manga: manga, chapterId: chapterId, page: max(1, page), chapters: chapters ?? chapterCache[manga.id],
            showControls: controls)
    }

    // MARK: Library

    func toggleFavorite(_ manga: MangaSummary) {
        if store.isFavorite(manga.id) {
            store.removeFavorite(manga.id)
            showToast("Removed from Library", undo: { [weak self] in
                self?.store.setFavorite(manga, category: self?.store.library[manga.id]?.category ?? "Reading")
            })
        } else {
            store.setFavorite(manga, category: store.library[manga.id]?.category ?? "Reading")
            if isSignedIn || AppConfig.openAccount {
                showToast("Added to Library")
            } else {
                sheet = .signIn(fromFavorite: true)
            }
        }
    }

    // MARK: Toasts

    /// `commit` runs once the toast goes away without Undo (server deletes
    /// wait for it so Undo stays exact).
    func showToast(_ message: String, undo: (() -> Void)? = nil, commit: (() -> Void)? = nil) {
        flushToast()
        undoAction = undo
        commitAction = commit
        withAnimation(.spring(duration: 0.24)) { toast = Toast(message: message, hasUndo: undo != nil) }
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(undo == nil ? 2.6 : 4))
            guard !Task.isCancelled else { return }
            self?.flushToast()
        }
    }

    func undoToast() {
        let u = undoAction
        undoAction = nil
        commitAction = nil
        toastTask?.cancel()
        withAnimation { toast = nil }
        u?()
    }

    private func flushToast() {
        toastTask?.cancel()
        let c = commitAction
        commitAction = nil
        undoAction = nil
        if toast != nil { withAnimation { toast = nil } }
        c?()
    }

    // MARK: Accounts

    func signInApple(_ result: Result<ASAuthorization, Error>) {
        switch result {
        case .success(let auth):
            guard let cred = auth.credential as? ASAuthorizationAppleIDCredential else { return }
            signIn(provider: "Apple ID") { try await self.auth.apple(cred) }
        case .failure(let e):
            if (e as? ASAuthorizationError)?.code == .canceled { return }
            showToast("Couldn't sign in with Apple")
        }
    }

    func signInGoogle() {
        signIn(provider: "Google") { try await self.auth.google() }
    }

    func signInDev() {
        signIn(provider: "Dev") { try await self.auth.dev(name: "Test") }
    }

    private func signIn(provider: String, _ op: @escaping () async throws -> AuthResponse) {
        guard !signingIn else { return }
        signingIn = true
        Task {
            defer { signingIn = false }
            do {
                let r = try await op()
                api.token = r.token
                Keychain.set(r.token, for: "token")
                store.account = AccountInfo(user: r.user, provider: provider)
                store.cursors = SyncCursors()
                store.save()
                if store.hasUserData {
                    sheet = .merge
                } else {
                    sheet = nil
                    store.pending = PendingOps()
                    await sync.sync()
                    showToast("Signed in")
                }
                if !store.onboardingDone { store.finishOnboarding(genres: store.genres) }
            } catch GoogleOAuth.Failure.cancelled {
                return
            } catch {
                showToast("Couldn't sign in · \(error.localizedDescription)")
            }
        }
    }

    /// Open mode: take the owner session, keeping anything already on the phone.
    /// Quiet on failure; the next foreground or sync tap tries again.
    func ensureOpenSession() async {
        guard AppConfig.openAccount, !isSignedIn, !signingIn else { return }
        signingIn = true
        defer { signingIn = false }
        guard let r = try? await api.signInOpen() else { return }
        api.token = r.token
        Keychain.set(r.token, for: "token")
        store.account = AccountInfo(user: r.user, provider: "Personal")
        store.cursors = SyncCursors()
        if store.hasUserData { store.enqueueAll() } else { store.pending = PendingOps() }
        store.save()
        await sync.sync()
    }

    /// Sign in, or in open mode just connect, since there is no account to pick.
    func askSignIn(fromFavorite: Bool = false) {
        if AppConfig.openAccount {
            Task { await ensureOpenSession() }
        } else {
            sheet = .signIn(fromFavorite: fromFavorite)
        }
    }

    func mergeAndSync() {
        sheet = nil
        store.enqueueAll()
        Task {
            await sync.sync()
            showToast("Signed in · library merged and synced")
        }
    }

    func useAccountOnly() {
        sheet = nil
        store.clearUserData()
        Task {
            await sync.sync()
            showToast("Signed in")
        }
    }

    func signOut(silently: Bool = false) {
        if !silently, let token = api.token {
            // Revoke with a throwaway client: ours forgets the token right away.
            let base = api.baseURL
            Task.detached {
                let c = APIClient(baseURL: base)
                c.token = token
                try? await c.logout()
            }
        }
        api.token = nil
        Keychain.set(nil, for: "token")
        store.signedOut()
        if !silently { showToast("Signed out · local data kept") }
    }

    /// Foreground: pull other devices' changes.
    func refreshOnForeground() {
        if isSignedIn {
            Task { await sync.sync() }
        } else if AppConfig.openAccount {
            Task { await ensureOpenSession() }
        }
        ImagePipeline.shared.trimDisk()
    }
}
