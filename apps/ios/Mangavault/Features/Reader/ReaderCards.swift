import MangavaultKit
import SwiftUI

/// Webtoon: the card between two chapters.
struct DividerCard: View {
    let model: ReaderModel
    let from: Int
    let to: Int

    var body: some View {
        let pal = model.palette
        let ac = model.app.theme.ac
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("End of \(chapterLabel(from))").font(MV.font(12, .regular)).foregroundStyle(pal.fg2)
                Text("Next: \(model.chapterTitle(to).isEmpty ? "next chapter" : model.chapterTitle(to))")
                    .font(MV.font(15, .semibold)).foregroundStyle(pal.fg).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if model.nextFailed && model.loadedChapter(to) == nil {
                Button("Retry") { model.retryNext() }
                    .font(MV.font(14, .semibold))
                    .foregroundStyle(ac)
                    .frame(height: 44)
            } else if model.nextLoading {
                ProgressView().tint(pal.fg2)
            } else {
                Image(systemName: "arrow.down").font(.system(size: 20, weight: .semibold)).foregroundStyle(ac)
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 92)
        .background(pal.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.vertical, 24)
        .frame(height: 140)
        .accessibilityElement(children: .combine)
    }

    private func chapterLabel(_ id: Int) -> String {
        model.chapter(id).map(Fmt.chapter) ?? "chapter"
    }
}

/// Paged: "Finished / Next" between two chapters.
struct TransitionPage: View {
    let model: ReaderModel
    let from: Int
    let to: Int

    var body: some View {
        let pal = model.palette
        let theme = model.app.theme
        let ready = model.loadedChapter(to) != nil
        VStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 4) {
                Text("FINISHED").font(MV.font(12, .regular)).kerning(1).foregroundStyle(pal.fg2)
                Text(model.chapterTitle(from)).font(MV.font(20, .semibold)).foregroundStyle(pal.fg)
            }
            Rectangle().fill(pal.card).frame(height: 1)
            VStack(alignment: .leading, spacing: 4) {
                Text("NEXT").font(MV.font(12, .regular)).kerning(1).foregroundStyle(theme.ac)
                Text(model.chapterTitle(to).isEmpty ? "Next chapter" : model.chapterTitle(to))
                    .font(MV.font(20, .semibold)).foregroundStyle(pal.fg)
                HStack(spacing: 6) {
                    if model.nextFailed && !ready {
                        Circle().fill(theme.wn).frame(width: 8, height: 8)
                        Text("Couldn't load the next chapter")
                        Button("Retry") { model.retryNext() }.foregroundStyle(theme.ac).font(MV.font(13, .semibold))
                    } else {
                        Circle().fill(ready ? theme.ok : pal.fg2).frame(width: 8, height: 8)
                        Text(ready ? "First pages ready" : "Getting pages…")
                    }
                }
                .font(MV.font(13, .regular))
                .foregroundStyle(pal.fg2)
                .padding(.top, 4)
            }
            Text(hint).font(MV.font(13, .regular)).foregroundStyle(pal.fg2).padding(.top, 12)
        }
        .padding(.horizontal, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }

    private var hint: String {
        switch model.mode {
        case .rtl: return "Tap the left edge or swipe to continue"
        case .ltr: return "Tap the right edge or swipe to continue"
        default: return "Swipe up to continue"
        }
    }
}

/// After the latest chapter.
struct CaughtUpView: View {
    let model: ReaderModel
    let chapterId: Int

    var body: some View {
        let pal = model.palette
        let theme = model.app.theme
        VStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill").font(.system(size: 48)).foregroundStyle(theme.ok)
            Text("You're caught up").font(MV.font(22, .semibold)).foregroundStyle(pal.fg)
            Text(sub).font(MV.font(15, .regular)).foregroundStyle(pal.fg2).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            VStack(spacing: 8) {
                if !model.isFavorite {
                    Button { model.favorite() } label: {
                        Text("Add to favorites").font(MV.font(15, .semibold)).foregroundStyle(.white)
                            .frame(maxWidth: .infinity).frame(height: 48)
                            .background(theme.ac, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                Button { model.close(toDetails: true) } label: {
                    Text("Back to details").font(MV.font(15, .semibold)).foregroundStyle(pal.fg)
                        .frame(maxWidth: .infinity).frame(height: 48)
                        .background(pal.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 12)
        }
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var sub: String {
        let ch = model.chapter(chapterId).map(Fmt.chapter) ?? "This"
        return "\(ch) is the latest chapter. We'll badge it in your Library when the next one lands."
    }
}

/// The whole-screen "couldn't load this chapter" state.
struct ReaderErrorView: View {
    let model: ReaderModel

    var body: some View {
        let pal = model.palette
        let theme = model.app.theme
        let offline = model.app.network.isOffline
        VStack(spacing: 10) {
            Image(systemName: offline ? "wifi.slash" : "icloud.slash").font(.system(size: 44)).foregroundStyle(pal.fg.opacity(0.7))
            Text(title).font(MV.font(20, .semibold)).foregroundStyle(pal.fg).multilineTextAlignment(.center)
            Text(
                offline
                    ? "You're offline and this chapter isn't cached yet. Your place is saved."
                    : "None of the sites that carry this chapter answered, and it isn't cached yet. Your place is saved."
            )
            .font(MV.font(15, .regular)).foregroundStyle(pal.fg2).multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            VStack(spacing: 8) {
                Button { model.retry() } label: {
                    Text("Retry").font(MV.font(15, .semibold)).foregroundStyle(.white)
                        .frame(maxWidth: .infinity).frame(height: 48)
                        .background(theme.ac, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
                Button { model.close(toDetails: true) } label: {
                    Text("Back to details").font(MV.font(15, .semibold)).foregroundStyle(pal.fg)
                        .frame(maxWidth: .infinity).frame(height: 48)
                        .background(pal.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 12)
        }
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(pal.bg)
    }

    private var title: String {
        guard let c = model.currentChapter else { return "Couldn't load this chapter" }
        return "Couldn't load \(Fmt.chapter(c))"
    }
}

/// Loading: a shimmering page.
struct ReaderLoadingView: View {
    let palette: ReaderPalette

    var body: some View {
        GeometryReader { g in
            VStack(spacing: 0) {
                Shimmer(base: palette.sk, highlight: palette.sk2)
                    .frame(height: min(g.size.height, g.size.width * 1.45))
                Spacer(minLength: 0)
            }
        }
        .ignoresSafeArea()
        .accessibilityLabel("Loading chapter")
    }
}
