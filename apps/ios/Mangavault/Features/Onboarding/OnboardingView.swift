import MangavaultKit
import SwiftUI

struct OnboardingView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    @State private var step = 1
    @State private var picked: [String] = []

    var body: some View {
        ZStack {
            theme.bg.ignoresSafeArea()
            if step == 1 { welcome.transition(.opacity) } else { genres.transition(.opacity) }
        }
        .animation(.easeOut(duration: 0.25), value: step)
        .task {
            await app.home.load()
            await app.search.loadGenres()
        }
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 0) {
            let covers = Array(app.home.feed.popular.prefix(9))
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                ForEach(0..<9, id: \.self) { i in
                    Group {
                        if i < covers.count {
                            GeometryReader { g in CoverImage(manga: covers[i], width: g.size.width, shade: false) }
                        } else {
                            RoundedRectangle(cornerRadius: 8).fill(CoverTint.gradient(id: i * 7 + 3))
                        }
                    }
                    .aspectRatio(2.0 / 3.0, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .offset(y: i % 3 == 1 ? -18 : 0)
                }
            }
            .frame(maxHeight: .infinity)

            VStack(alignment: .leading, spacing: 10) {
                (Text("manga").foregroundColor(theme.tp) + Text("vault").foregroundColor(theme.ac)).font(MV.display(16))
                Text("Tap a chapter. Start reading.").font(MV.display(30)).foregroundStyle(theme.tp)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Manga, manhwa and webtoons in one library. No account needed.").mvText(15, .regular, theme.ts)
            }
            .padding(.top, 28)

            VStack(spacing: 8) {
                PrimaryButton(title: "Get started") { step = 2 }
                if !AppConfig.openAccount {
                    Button {
                        app.sheet = .signIn(fromFavorite: false)
                    } label: {
                        Text("I already have an account").mvText(15, .medium, theme.tp).frame(maxWidth: .infinity).frame(height: 44)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 24)
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 20)
    }

    private var genres: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                HStack(spacing: 6) {
                    Capsule().fill(theme.sr).frame(width: 20, height: 4)
                    Capsule().fill(theme.ac).frame(width: 20, height: 4)
                }
                Spacer()
                Button { finish() } label: { Text("Skip").mvText(15, .medium, theme.ts).frame(height: 44) }
                    .buttonStyle(.plain)
            }
            .frame(height: 44)
            Text("What do you like to read?").mvText(28, .bold, theme.tp).padding(.top, 16)
            Text("We'll use this to fill Home. Change it any time.").mvText(15, .regular, theme.ts).padding(.top, 8)
            ScrollView {
                FlowLayout(spacing: 8) {
                    ForEach(app.search.genres, id: \.self) { g in
                        let on = picked.contains(g)
                        Button {
                            if on { picked.removeAll { $0 == g } } else { picked.append(g) }
                        } label: {
                            Text(g).mvText(15, .medium, on ? .white : theme.tp)
                                .padding(.horizontal, 16)
                                .frame(height: 40)
                                .background(on ? theme.ac : theme.sf, in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.top, 24)
            }
            .scrollIndicators(.hidden)
            PrimaryButton(title: picked.isEmpty ? "Continue" : "Continue with \(Fmt.plural(picked.count, "genre"))") { finish() }
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 20)
    }

    private func finish() {
        app.store.finishOnboarding(genres: picked)
        Task { await app.home.load(force: true) }
    }
}
