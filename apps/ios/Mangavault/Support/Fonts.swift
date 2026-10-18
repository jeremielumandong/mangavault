import SwiftUI

/// Zen Kaku Gothic New for text, Dela Gothic One for display, as in the
/// design. Both are bundled subsets (Latin, Greek, Cyrillic); CJK titles fall
/// back to the system font automatically.
enum MV {
    enum Weight {
        case regular, medium, semibold, bold, black
    }

    static func font(_ size: CGFloat, _ weight: Weight = .regular) -> Font {
        let name: String
        switch weight {
        case .regular: name = "ZenKakuGothicNew-Regular"
        case .medium: name = "ZenKakuGothicNew-Medium"
        case .semibold, .bold: name = "ZenKakuGothicNew-Bold"
        case .black: name = "ZenKakuGothicNew-Black"
        }
        return .custom(name, size: size)
    }

    static func display(_ size: CGFloat) -> Font {
        .custom("DelaGothicOne-Regular", size: size)
    }

    static func uiFont(_ size: CGFloat, _ weight: Weight = .regular) -> UIFont {
        let name: String
        switch weight {
        case .regular: name = "ZenKakuGothicNew-Regular"
        case .medium: name = "ZenKakuGothicNew-Medium"
        case .semibold, .bold: name = "ZenKakuGothicNew-Bold"
        case .black: name = "ZenKakuGothicNew-Black"
        }
        return UIFont(name: name, size: size) ?? .systemFont(ofSize: size)
    }
}

extension View {
    /// Text style shorthand: size, weight, color.
    func mvText(_ size: CGFloat, _ weight: MV.Weight = .regular, _ color: Color? = nil) -> some View {
        font(MV.font(size, weight))
            .foregroundStyle(color.map { AnyShapeStyle($0) } ?? AnyShapeStyle(HierarchicalShapeStyle.primary))
    }
}
