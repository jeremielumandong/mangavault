import Foundation

/// Timestamps on the wire: RFC 3339 UTC with milliseconds, like the backend.
public enum ISO {
    private static let millis: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    public static func string(_ date: Date) -> String { millis.string(from: date) }

    public static func now() -> String { string(Date()) }

    public static func date(_ s: String?) -> Date? {
        guard let s, !s.isEmpty else { return nil }
        if let d = millis.date(from: s) ?? plain.date(from: s) { return d }
        // Bare dates ("2024-01-02") from older rows.
        if s.count == 10 { return plain.date(from: s + "T00:00:00Z") }
        return nil
    }

    /// True when `a` is strictly later than `b`. Unparseable values lose.
    public static func isLater(_ a: String, than b: String) -> Bool {
        switch (date(a), date(b)) {
        case let (x?, y?): return x > y
        case (_?, nil): return true
        case (nil, _?): return false
        case (nil, nil): return a > b
        }
    }
}

public enum Fmt {
    /// "112", "112.5". Nil numbers (one-shots) read as "?".
    public static func number(_ n: Double?) -> String {
        guard let n else { return "?" }
        if n.rounded() == n, abs(n) < 1e9 { return String(Int(n)) }
        var s = String(format: "%.2f", n)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }

    /// "Ch. 112"
    public static func chapter(_ c: Chapter) -> String {
        if c.number == nil, let t = c.title, !t.isEmpty { return t }
        return "Ch. " + number(c.number)
    }

    public static func chapter(number n: Double?) -> String { "Ch. " + number(n) }

    /// "Oct 10" this year, else "Oct 10, 2024".
    public static func shortDate(_ iso: String?, now: Date = Date(), calendar: Calendar = .current) -> String? {
        guard let d = ISO.date(iso) else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.timeZone = calendar.timeZone
        let sameYear = calendar.component(.year, from: d) == calendar.component(.year, from: now)
        f.setLocalizedDateFormatFromTemplate(sameYear ? "MMMd" : "MMMdyyyy")
        return f.string(from: d)
    }

    /// Chapter row subtitle: "Vol. 12 · The Ash Garden · Oct 10".
    public static func chapterSub(_ c: Chapter, now: Date = Date()) -> String {
        var parts: [String] = []
        if let v = c.volume, !v.isEmpty { parts.append("Vol. " + v) }
        if let t = c.title, !t.isEmpty, c.number != nil { parts.append(t) }
        if let d = shortDate(c.publishedAt, now: now) { parts.append(d) }
        return parts.joined(separator: " · ")
    }

    /// 12345 → "12,345"
    public static func count(_ n: Int) -> String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .decimal
        return f.string(from: NSNumber(value: n)) ?? "\(n)"
    }

    public static func plural(_ n: Int, _ one: String, _ many: String? = nil) -> String {
        "\(n) " + (n == 1 ? one : (many ?? one + "s"))
    }

    public static func bytes(_ n: Int64) -> String {
        let f = ByteCountFormatter()
        f.countStyle = .file
        f.allowedUnits = [.useMB, .useGB]
        return n == 0 ? "0 MB" : f.string(fromByteCount: n)
    }
}

/// Ranges for the Jump sheet: 10, 20 or 50 chapters per bucket, at most 12.
public func jumpBuckets(latest: Int) -> [ClosedRange<Int>] {
    guard latest > 0 else { return [] }
    let size = latest > 200 ? 50 : latest > 60 ? 20 : 10
    var out: [ClosedRange<Int>] = []
    var a = 1
    while a <= latest && out.count < 12 {
        out.append(a...min(latest, a + size - 1))
        a += size
    }
    return out
}

public enum HistoryDay: String, CaseIterable, Sendable {
    case today = "Today"
    case yesterday = "Yesterday"
    case thisWeek = "This week"
    case earlier = "Earlier"

    public static func of(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> HistoryDay {
        if calendar.isDate(date, inSameDayAs: now) { return .today }
        if let y = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: y) {
            return .yesterday
        }
        let start = calendar.startOfDay(for: now)
        if let weekAgo = calendar.date(byAdding: .day, value: -6, to: start), date >= weekAgo { return .thisWeek }
        return .earlier
    }

    /// "11:48 PM" for today and yesterday, the weekday this week, "Oct 2" before.
    public static func timeLabel(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.timeZone = calendar.timeZone
        switch of(date, now: now, calendar: calendar) {
        case .today, .yesterday: f.dateFormat = "h:mm a"
        case .thisWeek: f.dateFormat = "EEEE"
        case .earlier:
            let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
            f.setLocalizedDateFormatFromTemplate(sameYear ? "MMMd" : "MMMdyyyy")
        }
        return f.string(from: date)
    }
}
