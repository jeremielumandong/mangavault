import MangavaultKit
import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: alpha)
    }

    init(white255 w: Double, alpha: Double) {
        self.init(.sRGB, red: w / 255, green: w / 255, blue: w / 255, opacity: alpha)
    }
}

enum ThemeKind: String, Codable, CaseIterable, Identifiable {
    case dark, light, oled

    var id: String { rawValue }

    var label: String {
        switch self {
        case .dark: return "Dark"
        case .light: return "Light"
        case .oled: return "OLED Black"
        }
    }
}

/// The prototype's THEMES tokens.
struct Theme {
    var bg: Color
    var sf: Color
    var sr: Color
    var tp: Color
    var ts: Color
    var ac: Color
    var ok: Color
    var wn: Color
    var ln: Color
    var sk: Color
    var sk2: Color
    var tb: Color
    var acs: Color
    var isLight: Bool

    static let dark = Theme(
        bg: Color(hex: 0x0E0E11), sf: Color(hex: 0x17171C), sr: Color(hex: 0x202027), tp: Color(hex: 0xF2F2F5),
        ts: Color(hex: 0xA0A0AB), ac: Color(hex: 0xFF5A5F), ok: Color(hex: 0x3DD68C), wn: Color(hex: 0xF5B841),
        ln: Color(white255: 255, alpha: 0.08), sk: Color(hex: 0x1C1C22), sk2: Color(hex: 0x2A2A32),
        tb: Color(.sRGB, red: 20 / 255, green: 20 / 255, blue: 25 / 255, opacity: 0.82),
        acs: Color(hex: 0xFF5A5F, alpha: 0.14), isLight: false)

    static let light = Theme(
        bg: Color(hex: 0xFAFAFB), sf: Color(hex: 0xFFFFFF), sr: Color(hex: 0xF1F1F4), tp: Color(hex: 0x121216),
        ts: Color(hex: 0x5C5C66), ac: Color(hex: 0xE8484D), ok: Color(hex: 0x1FA463), wn: Color(hex: 0xC98A0B),
        ln: Color(white255: 0, alpha: 0.09), sk: Color(hex: 0xECECF0), sk2: Color(hex: 0xF7F7F9),
        tb: Color(hex: 0xFAFAFB, alpha: 0.86), acs: Color(hex: 0xE8484D, alpha: 0.12), isLight: true)

    static let oled = Theme(
        bg: Color(hex: 0x000000), sf: Color(hex: 0x0D0D10), sr: Color(hex: 0x18181D), tp: Color(hex: 0xF2F2F5),
        ts: Color(hex: 0xA0A0AB), ac: Color(hex: 0xFF5A5F), ok: Color(hex: 0x3DD68C), wn: Color(hex: 0xF5B841),
        ln: Color(white255: 255, alpha: 0.09), sk: Color(hex: 0x121216), sk2: Color(hex: 0x1E1E24),
        tb: Color(hex: 0x000000, alpha: 0.85), acs: Color(hex: 0xFF5A5F, alpha: 0.16), isLight: false)

    static func of(_ kind: ThemeKind) -> Theme {
        switch kind {
        case .dark: return .dark
        case .light: return .light
        case .oled: return .oled
        }
    }

    var colorScheme: ColorScheme { isLight ? .light : .dark }
}

private struct ThemeKey: EnvironmentKey {
    static let defaultValue = Theme.dark
}

extension EnvironmentValues {
    var theme: Theme {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }
}

/// Placeholder colors for a title before its cover loads, stable per id.
enum CoverTint {
    static func hue(for id: Int) -> Double {
        var x = UInt64(bitPattern: Int64(id)) &* 0x9E37_79B9_7F4A_7C15
        x ^= x >> 29
        return Double(x % 360)
    }

    static func gradient(id: Int) -> LinearGradient {
        let h = hue(for: id) / 360
        return LinearGradient(
            colors: [
                Color(hue: h, saturation: 0.42, brightness: 0.62),
                Color(hue: (h + 40 / 360).truncatingRemainder(dividingBy: 1), saturation: 0.5, brightness: 0.28),
            ],
            startPoint: UnitPoint(x: 0.25, y: 0), endPoint: UnitPoint(x: 0.75, y: 1))
    }

    /// Top of the Details header: the cover's own color, darkened (or
    /// lightened in the light theme).
    static func header(from color: UIColor?, id: Int, light: Bool) -> Color {
        var h: CGFloat = hue(for: id) / 360, s: CGFloat = 0.4, b: CGFloat = 0, a: CGFloat = 0
        if let color {
            var hh: CGFloat = 0, ss: CGFloat = 0
            if color.getHue(&hh, saturation: &ss, brightness: &b, alpha: &a), ss > 0.08 {
                h = hh
                s = min(ss, 0.55)
            } else {
                s = 0.05
            }
        }
        return light
            ? Color(hue: h, saturation: min(s, 0.18), brightness: 0.95)
            : Color(hue: h, saturation: s, brightness: 0.36)
    }
}

/// Reader colors for each background, as in the prototype.
struct ReaderPalette {
    var bg: Color
    var fg: Color
    var fg2: Color
    var card: Color
    var sk: Color
    var sk2: Color
    var uiBackground: UIColor
    var uiSk: UIColor
    var uiSk2: UIColor
    var uiFg: UIColor

    static func of(_ b: ReaderBackground) -> ReaderPalette {
        let light = b.isLight
        let bgHex: UInt32
        switch b {
        case .black: bgHex = 0x000000
        case .darkGray: bgHex = 0x1C1C20
        case .white: bgHex = 0xFFFFFF
        case .sepia: bgHex = 0xF1E6CF
        }
        let skHex: UInt32 = light ? (b == .sepia ? 0xE6DAC1 : 0xECECEF) : 0x141417
        let sk2Hex: UInt32 = light ? (b == .sepia ? 0xF1E6CF : 0xF7F7F9) : 0x22222A
        let fgHex: UInt32 = light ? 0x1A1A1E : 0xE6E6EA
        return ReaderPalette(
            bg: Color(hex: bgHex), fg: Color(hex: fgHex), fg2: Color(hex: light ? 0x5C5C66 : 0x8E8E98),
            card: light ? Color(white255: 0, alpha: 0.06) : Color(white255: 255, alpha: 0.07),
            sk: Color(hex: skHex), sk2: Color(hex: sk2Hex), uiBackground: UIColor(hex: bgHex),
            uiSk: UIColor(hex: skHex), uiSk2: UIColor(hex: sk2Hex), uiFg: UIColor(hex: fgHex))
    }
}

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }
}
