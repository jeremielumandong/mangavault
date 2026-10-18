import MangavaultKit
import SwiftUI

struct HistoryView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    @State private var showMenu = false

    private var store: LocalStore { app.store }

    private struct DayGroup: Identifiable {
        var day: HistoryDay
        var rows: [HistoryEntry]
        var id: String { day.rawValue }
    }

    private var groups: [DayGroup] {
        let now = Date()
        let byDay = Dictionary(grouping: store.historyList) { e in
            HistoryDay.of(ISO.date(e.readAt) ?? .distantPast, now: now)
        }
        return HistoryDay.allCases.compactMap { d in byDay[d].map { DayGroup(day: d, rows: $0) } }
    }

    var body: some View {
        List {
            HStack(alignment: .bottom) {
                ScreenTitle(title: "History")
                Spacer()
                Button {
                    showMenu = true
                } label: {
                    Image(systemName: "ellipsis").font(.system(size: 20)).foregroundStyle(theme.tp).frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("More")
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .padding(.top, 4)
            .padding(.bottom, 4)
            .plainRow(theme)

            if store.history.isEmpty {
                EmptyState(
                    symbol: "clock.arrow.circlepath", title: "No reading history",
                    message: "Chapters you open will appear here.", button: "Browse popular titles",
                    action: { app.browse(sort: .popular) }, top: 110)
                    .plainRow(theme)
            }

            ForEach(groups) { g in
                Text(g.day.rawValue).mvText(13, .semibold, theme.ts)
                    .padding(.horizontal, 16).padding(.top, 18).padding(.bottom, 6)
                    .plainRow(theme)
                ForEach(g.rows, id: \.mangaId) { e in
                    HistoryRow(entry: e)
                        .plainRow(theme)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                remove(e.mangaId)
                            } label: {
                                Label("Remove", systemImage: "trash")
                            }
                            .tint(theme.ac)
                        }
                }
            }

            if !store.history.isEmpty {
                Text("Swipe a row left to remove it.").mvText(12, .regular, theme.ts)
                    .frame(maxWidth: .infinity).padding(16)
                    .plainRow(theme)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background(theme.bg)
        .environment(\.defaultMinListRowHeight, 1)
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { await app.sync.sync() }
        .sheet(isPresented: $showMenu) {
            ListSheet(
                title: "History",
                items: [
                    ListSheetItem(symbol: "trash", label: "Clear all history", destructive: true) { clearAll() },
                    ListSheetItem(symbol: "xmark", label: "Cancel") {},
                ])
        }
    }

    private func remove(_ id: Int) {
        guard let e = store.removeHistory(id) else { return }
        let s = store
        app.showToast(
            "Removed from history", undo: { s.restoreHistory([e]) }, commit: { s.commitHistoryRemoval([id]) })
    }

    private func clearAll() {
        let all = store.clearHistory()
        guard !all.isEmpty else { return }
        let s = store
        app.showToast("History cleared", undo: { s.restoreHistory(all) }, commit: { s.commitClearHistory() })
    }
}

private struct HistoryRow: View {
    let entry: HistoryEntry
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme

    private var manga: MangaSummary {
        app.store.summaries[entry.mangaId] ?? MangaSummary(id: entry.mangaId, title: "Untitled")
    }

    var body: some View {
        HStack(spacing: 12) {
            Button { app.open(manga) } label: {
                HStack(spacing: 12) {
                    CoverImage(manga: manga, width: 44, cornerRadius: 6, shade: false)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(manga.title).mvText(15, .semibold, theme.tp).lineLimit(1)
                        Text(sub).mvText(13, .regular, theme.ts).lineLimit(1)
                        Text(time).mvText(12, .regular, theme.ts)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button {
                app.read(manga, chapterId: entry.chapterId, page: entry.page)
            } label: {
                HStack(spacing: 2) {
                    Image(systemName: "play.fill").font(.system(size: 13))
                    Text("Resume")
                }
                .mvText(13, .semibold, theme.tp)
                .padding(.horizontal, 14)
                .frame(height: 36)
                .background(theme.sr, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.leading, 16)
        .padding(.trailing, 12)
        .padding(.vertical, 8)
    }

    private var sub: String {
        let ch = Fmt.chapter(number: entry.chapterNumber)
        if let n = entry.pageCount { return "\(ch) · page \(entry.page) of \(n)" }
        return "\(ch) · page \(entry.page)"
    }

    private var time: String {
        guard let d = ISO.date(entry.readAt) else { return "" }
        if Date().timeIntervalSince(d) < 60 { return "Just now" }
        return HistoryDay.timeLabel(d)
    }
}

extension View {
    /// List row with no insets, separators or system background.
    func plainRow(_ theme: Theme) -> some View {
        listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            .listRowBackground(theme.bg)
    }
}
