import AuthenticationServices
import MangavaultKit
import SwiftUI

struct SignInSheet: View {
    let fromFavorite: Bool
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let theme = app.theme
        FittedSheet(theme: theme) {
            VStack(alignment: .leading, spacing: 10) {
                Text(fromFavorite ? "Saved to this phone. Sync it everywhere?" : "Sign in to Mangavault")
                    .mvText(22, .semibold, theme.tp)
                    .padding(.top, 24)
                Text("Sync favorites, reading progress and history across your devices. Reading never needs an account.")
                    .mvText(15, .regular, theme.ts)
                    .padding(.bottom, 8)
                SignInWithAppleButton(.signIn) { req in
                    req.requestedScopes = [.fullName, .email]
                } onCompletion: { result in
                    app.signInApple(result)
                }
                .signInWithAppleButtonStyle(theme.isLight ? .black : .white)
                .frame(height: 50)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                Button {
                    app.signInGoogle()
                } label: {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(
                                AngularGradient(
                                    stops: [
                                        .init(color: Color(hex: 0xEA4335), location: 0), .init(color: Color(hex: 0xEA4335), location: 0.25),
                                        .init(color: Color(hex: 0xFBBC05), location: 0.25), .init(color: Color(hex: 0xFBBC05), location: 0.5),
                                        .init(color: Color(hex: 0x34A853), location: 0.5), .init(color: Color(hex: 0x34A853), location: 0.75),
                                        .init(color: Color(hex: 0x4285F4), location: 0.75), .init(color: Color(hex: 0x4285F4), location: 1),
                                    ], center: .center, startAngle: .degrees(-90), endAngle: .degrees(270)))
                            .frame(width: 18, height: 18)
                        Text("Sign in with Google").mvText(16, .semibold, theme.tp)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(theme.sr, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(theme.ln, lineWidth: 0.5))
                }
                .buttonStyle(.plain)

                #if DEBUG
                    Button {
                        app.signInDev()
                    } label: {
                        Text("Dev sign-in (debug builds)").mvText(14, .medium, theme.ts).frame(maxWidth: .infinity).frame(height: 36)
                    }
                    .buttonStyle(.plain)
                #endif

                Button {
                    dismiss()
                } label: {
                    Text("Not now").mvText(15, .regular, theme.ts).frame(maxWidth: .infinity).frame(height: 44)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 8)
            .overlay {
                if app.signingIn {
                    ProgressView().tint(theme.tp).frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(theme.sf.opacity(0.6))
                }
            }
        }
    }
}

struct MergeSheet: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        let theme = app.theme
        let store = app.store
        let rows: [(String, String)] = [
            ("Favorites", Fmt.plural(store.favorites.count, "title")),
            ("Reading progress", Fmt.plural(store.progress.count, "title")),
            ("History", Fmt.plural(store.history.count, "entry", "entries")),
        ]
        FittedSheet(theme: theme) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: "icloud.and.arrow.up").font(.system(size: 32)).foregroundStyle(theme.ok).padding(.top, 24)
                Text("Add this phone's reading to your account?").mvText(22, .semibold, theme.tp)
                VStack(spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                        HStack {
                            Text(r.0).mvText(15, .regular, theme.tp)
                            Spacer()
                            Text(r.1).mvText(15, .regular, theme.ts)
                        }
                        .frame(height: 40)
                        .overlay(alignment: .top) { if i > 0 { Rectangle().fill(theme.ln).frame(height: 0.5) } }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 4)
                .background(theme.sr, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                Text("If something changed in both places, the most recent change wins.").mvText(13, .regular, theme.ts)
                PrimaryButton(title: "Merge and sync", height: 50, fontSize: 16) { app.mergeAndSync() }
                Button {
                    app.useAccountOnly()
                } label: {
                    Text("Use account data only").mvText(15, .regular, theme.ts).frame(maxWidth: .infinity).frame(height: 44)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 8)
        }
        .interactiveDismissDisabled()
    }
}
