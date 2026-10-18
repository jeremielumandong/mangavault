import MangavaultKit
import SwiftUI

/// The loading shimmer: a light band sweeping across the skeleton color.
struct Shimmer: View {
    var base: Color
    var highlight: Color
    var cornerRadius: CGFloat = 0
    @State private var phase: CGFloat = 1

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            Rectangle()
                .fill(base)
                .overlay {
                    LinearGradient(
                        stops: [
                            .init(color: base, location: 0.3), .init(color: highlight, location: 0.5),
                            .init(color: base, location: 0.7),
                        ], startPoint: .leading, endPoint: .trailing
                    )
                    .frame(width: w * 2)
                    .offset(x: phase * w)
                }
                .clipped()
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .onAppear {
            withAnimation(.linear(duration: 1.3).repeatForever(autoreverses: false)) { phase = -1 }
        }
        .accessibilityHidden(true)
    }
}

extension Theme {
    func shimmer(_ radius: CGFloat = 0) -> Shimmer { Shimmer(base: sk, highlight: sk2, cornerRadius: radius) }
}

struct PrimaryButton: View {
    let title: String
    var height: CGFloat = 52
    var fontSize: CGFloat = 17
    var action: () -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        Button(action: action) {
            Text(title)
                .mvText(fontSize, .semibold, .white)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .background(theme.ac, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

/// Compact pill button ("Retry", "Browse popular titles").
struct PillButton: View {
    let title: String
    var filled = true
    var action: () -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        Button(action: action) {
            Text(title)
                .mvText(15, .semibold, filled ? .white : theme.tp)
                .padding(.horizontal, 24)
                .frame(height: 44)
                .background(filled ? theme.ac : theme.sr, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

struct SectionHeader: View {
    let title: String
    var action: String?
    var onAction: (() -> Void)?
    var top: CGFloat = 24
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).mvText(17, .semibold, theme.tp)
            Spacer()
            if let action, let onAction {
                Button(action: onAction) { Text(action).mvText(15, .medium, theme.ac) }
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, top)
        .padding(.bottom, 10)
    }
}

/// Large screen title in Dela Gothic One.
struct ScreenTitle: View {
    let title: String
    @Environment(\.theme) private var theme

    var body: some View {
        Text(title)
            .font(MV.display(32))
            .foregroundStyle(theme.tp)
            .lineLimit(1)
    }
}

struct EmptyState: View {
    var symbol: String?
    var dashedCover = false
    let title: String
    let message: String
    var button: String?
    var action: (() -> Void)?
    var top: CGFloat = 100
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(spacing: 10) {
            if dashedCover {
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(theme.ts, style: StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
                    .frame(width: 72, height: 108)
                    .opacity(0.6)
                    .padding(.bottom, 8)
            } else if let symbol {
                Image(systemName: symbol).font(.system(size: 40)).foregroundStyle(theme.ts)
            }
            Text(title).mvText(20, .semibold, theme.tp).multilineTextAlignment(.center)
            Text(message).mvText(15, .regular, theme.ts).multilineTextAlignment(.center)
            if let button, let action {
                PillButton(title: button, action: action).padding(.top, 8)
            }
        }
        .padding(.horizontal, 40)
        .padding(.top, top)
        .frame(maxWidth: .infinity)
    }
}

/// The amber offline banner.
struct OfflineBanner: View {
    let text: String
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "icloud.slash").font(.system(size: 17)).foregroundStyle(theme.wn)
            Text(text).mvText(13, .regular, theme.tp).frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color(hex: 0xF5B841, alpha: 0.14), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .padding(.horizontal, 16)
        .padding(.top, 4)
    }
}

/// The 46×28 switch from the prototype.
struct MVSwitch: View {
    var isOn: Bool
    @Environment(\.theme) private var theme

    var body: some View {
        Capsule()
            .fill(isOn ? theme.ac : theme.sk2)
            .frame(width: 46, height: 28)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle().fill(.white).frame(width: 22, height: 22).padding(3)
                    .shadow(color: .black.opacity(0.3), radius: 1.5, y: 1)
            }
            .animation(.easeOut(duration: 0.2), value: isOn)
    }
}

/// Segmented control: equal buttons on a rounded track.
struct Segmented<T: Hashable>: View {
    let options: [(T, String)]
    let selection: T
    var height: CGFloat = 40
    var onSelect: (T) -> Void
    @Environment(\.theme) private var theme

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options, id: \.0) { value, label in
                Button {
                    onSelect(value)
                } label: {
                    Text(label)
                        .mvText(13, .semibold, theme.tp)
                        .frame(maxWidth: .infinity)
                        .frame(height: height)
                        .background(
                            value == selection ? theme.sr : .clear,
                            in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
    }
}

/// A row in a bottom list sheet.
struct ListSheetItem: Identifiable {
    let id = UUID()
    var symbol: String
    var label: String
    var sub: String? = nil
    var checked = false
    var destructive = false
    var highlighted = false
    var action: () -> Void
}

struct ListSheet: View {
    let title: String
    var sub: String? = nil
    let items: [ListSheetItem]
    var scrollTo: Int? = nil
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(title).mvText(17, .semibold, theme.tp).padding(.horizontal, 20).padding(.bottom, 6)
                        .padding(.top, 20)
                    if let sub {
                        Text(sub).mvText(13, .regular, theme.ts).padding(.horizontal, 20).padding(.bottom, 8)
                    }
                    ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                        Button {
                            dismiss()
                            item.action()
                        } label: {
                            HStack(spacing: 14) {
                                Image(systemName: item.symbol)
                                    .font(.system(size: 19))
                                    .foregroundStyle(item.destructive || item.highlighted ? theme.ac : theme.ts)
                                    .frame(width: 24)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(item.label)
                                        .mvText(15, item.highlighted ? .bold : .medium, item.destructive ? theme.ac : theme.tp)
                                    if let s = item.sub, !s.isEmpty { Text(s).mvText(12, .regular, theme.ts).lineLimit(1) }
                                }
                                Spacer(minLength: 0)
                                if item.checked {
                                    Image(systemName: "checkmark").font(.system(size: 17, weight: .semibold)).foregroundStyle(theme.ac)
                                }
                            }
                            .padding(.horizontal, 20)
                            .frame(minHeight: 50)
                            .background(item.highlighted ? theme.acs : .clear)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .id(i)
                    }
                }
                .padding(.bottom, 8)
            }
            .onAppear {
                if let scrollTo { proxy.scrollTo(scrollTo, anchor: .center) }
            }
        }
        .sheetStyle(theme, height: CGFloat(76 + (sub == nil ? 0 : 24) + items.count * 50))
    }
}

extension View {
    /// Bottom-sheet chrome from the prototype: surface color, 16 pt corners,
    /// a grabber, and a height that fits the content (capped).
    func sheetStyle(_ theme: Theme, height: CGFloat? = nil, large: Bool = false) -> some View {
        var detents: Set<PresentationDetent> = [.medium, .large]
        if large {
            detents = [.large]
        } else if let height {
            detents = [.height(min(height, 640)), .large]
        }
        return self
            .presentationDetents(detents)
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(16)
            .presentationBackground(theme.sf)
            .environment(\.theme, theme)
            .preferredColorScheme(theme.colorScheme)
    }
}

/// A sheet sized to its content.
struct FittedSheet<Content: View>: View {
    let theme: Theme
    @ViewBuilder var content: () -> Content
    @State private var height: CGFloat = 320

    var body: some View {
        content()
            .fixedSize(horizontal: false, vertical: true)
            .background {
                GeometryReader { g in
                    Color.clear
                        .onAppear { height = g.size.height }
                        .onChange(of: g.size.height) { _, h in height = h }
                }
            }
            .frame(maxHeight: .infinity, alignment: .top)
            .presentationDetents([.height(height)])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(16)
            .presentationBackground(theme.sf)
            .environment(\.theme, theme)
            .preferredColorScheme(theme.colorScheme)
    }
}

struct ToastView: View {
    let toast: Toast
    var onUndo: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(toast.message).mvText(14, .regular, Color(hex: 0xF2F2F5)).frame(maxWidth: .infinity, alignment: .leading)
            if toast.hasUndo {
                Button("Undo", action: onUndo)
                    .font(MV.font(14, .semibold))
                    .foregroundStyle(Color(hex: 0xFF8A8D))
                    .padding(.horizontal, 12)
                    .frame(height: 40)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .frame(minHeight: 48)
        .background(Color(hex: 0x2A2A32), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .shadow(color: .black.opacity(0.4), radius: 15, y: 10)
        .padding(.horizontal, 16)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

extension View {
    /// Shows the app's toast `bottom` points above the safe area.
    func toastHost(_ app: AppModel, bottom: CGFloat) -> some View {
        overlay(alignment: .bottom) {
            if let t = app.toast {
                ToastView(toast: t) { app.undoToast() }
                    .padding(.bottom, bottom)
                    .id(t.id)
            }
        }
    }
}

/// A row of cover cards (Home rows).
struct CoverCard: View {
    let manga: MangaSummary
    var width: CGFloat = 112
    var badge: String?
    var subtitle: String?
    var titleSize: CGFloat = 13
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            CoverImage(manga: manga, width: width)
                .overlay(alignment: .topLeading) {
                    if let badge {
                        Text(badge)
                            .mvText(11, .bold, .white)
                            .padding(.horizontal, 7)
                            .frame(height: 20)
                            .background(theme.ac, in: Capsule())
                            .padding(6)
                    }
                }
            Text(manga.title)
                .mvText(titleSize, .semibold, theme.tp)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
                .frame(width: width, alignment: .leading)
            if let subtitle {
                Text(subtitle).mvText(11, .regular, theme.ts).lineLimit(1).padding(.top, -2)
            }
        }
        .frame(width: width, alignment: .topLeading)
        .contentShape(Rectangle())
    }
}

/// Grid of skeleton covers.
struct SkeletonGrid: View {
    var count = 9
    @Environment(\.theme) private var theme

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 16) {
            ForEach(0..<count, id: \.self) { _ in
                VStack(alignment: .leading, spacing: 8) {
                    theme.shimmer(8).aspectRatio(2.0 / 3.0, contentMode: .fit)
                    RoundedRectangle(cornerRadius: 4).fill(theme.sk).frame(height: 10).padding(.trailing, 10)
                    RoundedRectangle(cornerRadius: 4).fill(theme.sk).frame(width: 50, height: 8)
                }
            }
        }
        .padding(.horizontal, 16)
    }
}
