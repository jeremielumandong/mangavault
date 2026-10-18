import Foundation

/// A panel as fractions of the page, 0...1 on each axis.
public struct PanelRect: Hashable, Sendable {
    public var left: Double
    public var top: Double
    public var right: Double
    public var bottom: Double

    public init(left: Double, top: Double, right: Double, bottom: Double) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public var width: Double { right - left }
    public var height: Double { bottom - top }

    public static let full = PanelRect(left: 0, top: 0, right: 1, bottom: 1)
}

/// Finds the panels of a comic or manga page for guided view.
///
/// Works on a small copy of the page (about `analysisSize` px on the long
/// side). The page colour is sampled from the corners, so white and black
/// gutters both work. The page is split by recursive cuts along gutters: rows
/// of panels first, then the panels in each row, then any stacked panels
/// inside those, which also gives the reading order (left to right for
/// comics, right to left for manga). A gutter has to run clear to both ends,
/// so a white sky inside a bordered panel doesn't split it.
///
/// The result always ends with the whole page. A page with no clear panels (a
/// splash page, full-bleed art) is just the whole page. Same rules as the
/// Android app's `PanelDetector`.
public enum PanelDetector {
    public static let analysisSize = 500

    /// How close (sum of RGB differences) a pixel must be to the page colour to count as gutter.
    static let gutterDistance = 72
    static let marginDistance = 40
    /// Share of a line that must be gutter. Balloons and lettering may cross a gutter a little.
    static let lineCoverage = 0.9
    /// Each end of a gutter line must be clear too, so a panel's own border stops it.
    static let endCoverage = 0.9
    static let minGutter = 2
    /// Pieces thinner than this share of the page are lettering or noise, not panels.
    static let minPanel = 0.06
    static let maxPanels = 12
    static let maxDepth = 5

    /// `rgba` is row-major, four bytes per pixel.
    public static func detect(rgba: [UInt8], width: Int, height: Int, rtl: Bool = false) -> [PanelRect] {
        guard width > 0, height > 0, rgba.count >= width * height * 4 else { return [.full] }
        let img = Thumb(rgba: rgba, width: width, height: height)
        let bg = img.cornerColour()
        var gutter = [Bool](repeating: false, count: img.w * img.h)
        var margin = [Bool](repeating: false, count: img.w * img.h)
        for i in 0..<(img.w * img.h) {
            let d = img.distance(i, bg)
            gutter[i] = d < gutterDistance
            margin[i] = d < marginDistance
        }
        let page = Grid(w: img.w, h: img.h, on: gutter)
        guard let content = Grid(w: img.w, h: img.h, on: margin).trim(Box(x0: 0, y0: 0, x1: img.w, y1: img.h), 0.95)
        else { return [.full] }
        // Margins that eat most of the page mean the "page colour" is really the art's.
        if content.w < img.w / 2 || content.h < img.h / 2 { return [.full] }

        let minW = max(4, Int(Double(img.w) * minPanel))
        let minH = max(4, Int(Double(img.h) * minPanel))
        let found = page.cut(content, horizontalFirst: true, rtl: rtl, depth: 0, minW: minW, minH: minH)
        if found.count <= 1 || found.count > maxPanels { return [.full] }
        let w = Double(img.w)
        let h = Double(img.h)
        return found.map {
            PanelRect(left: Double($0.x0) / w, top: Double($0.y0) / h, right: Double($0.x1) / w, bottom: Double($0.y1) / h)
        } + [.full]
    }

    /// Half-open pixel box.
    struct Box: Equatable {
        var x0, y0, x1, y1: Int
        var w: Int { x1 - x0 }
        var h: Int { y1 - y0 }
    }

    struct Grid {
        let w: Int
        let h: Int
        let on: [Bool]

        private func row(_ y: Int, _ x0: Int, _ x1: Int) -> Int {
            var n = 0
            let base = y * w
            for x in x0..<max(x0, x1) where on[base + x] { n += 1 }
            return n
        }

        private func col(_ x: Int, _ y0: Int, _ y1: Int) -> Int {
            var n = 0
            for y in y0..<max(y0, y1) where on[y * w + x] { n += 1 }
            return n
        }

        /// Shrinks `b` while its outer lines are almost all on. Nil if nothing is left.
        func trim(_ b: Box, _ coverage: Double) -> Box? {
            var (x0, y0, x1, y1) = (b.x0, b.y0, b.x1, b.y1)
            while y0 < y1 && Double(row(y0, x0, x1)) >= Double(x1 - x0) * coverage { y0 += 1 }
            while y1 > y0 && Double(row(y1 - 1, x0, x1)) >= Double(x1 - x0) * coverage { y1 -= 1 }
            while x0 < x1 && Double(col(x0, y0, y1)) >= Double(y1 - y0) * coverage { x0 += 1 }
            while x1 > x0 && Double(col(x1 - 1, y0, y1)) >= Double(y1 - y0) * coverage { x1 -= 1 }
            return x1 - x0 < 2 || y1 - y0 < 2 ? nil : Box(x0: x0, y0: y0, x1: x1, y1: y1)
        }

        /// Whether the line at `at` across `from..<to` is a gutter, along rows when `horizontal`.
        private func isGutter(_ horizontal: Bool, _ at: Int, _ from: Int, _ to: Int) -> Bool {
            let len = to - from
            let end = max(2, len * 3 / 100)
            func count(_ a: Int, _ b: Int) -> Double { Double(horizontal ? row(at, a, b) : col(at, a, b)) }
            if count(from, to) < Double(len) * PanelDetector.lineCoverage { return false }
            return count(from, from + end) >= Double(end) * PanelDetector.endCoverage
                && count(to - end, to) >= Double(end) * PanelDetector.endCoverage
        }

        /// Runs of gutter lines inside `b`, as start..<end along the cut axis.
        private func gutters(_ b: Box, horizontal: Bool) -> [Range<Int>] {
            var runs: [Range<Int>] = []
            let (lo, hi) = horizontal ? (b.y0, b.y1) : (b.x0, b.x1)
            let (from, to) = horizontal ? (b.x0, b.x1) : (b.y0, b.y1)
            var start = -1
            for i in lo...hi {
                let g = i < hi && isGutter(horizontal, i, from, to)
                if g && start < 0 { start = i }
                if !g && start >= 0 {
                    if i - start >= PanelDetector.minGutter && start > lo && i < hi { runs.append(start..<i) }
                    start = -1
                }
            }
            return runs
        }

        private func split(_ b: Box, horizontal: Bool, _ runs: [Range<Int>]) -> [Box] {
            var pieces: [Box] = []
            var prev = horizontal ? b.y0 : b.x0
            for r in runs {
                pieces.append(horizontal ? Box(x0: b.x0, y0: prev, x1: b.x1, y1: r.lowerBound) : Box(x0: prev, y0: b.y0, x1: r.lowerBound, y1: b.y1))
                prev = r.upperBound
            }
            pieces.append(horizontal ? Box(x0: b.x0, y0: prev, x1: b.x1, y1: b.y1) : Box(x0: prev, y0: b.y0, x1: b.x1, y1: b.y1))
            return pieces
        }

        func cut(_ b: Box, horizontalFirst: Bool, rtl: Bool, depth: Int, minW: Int, minH: Int) -> [Box] {
            if depth >= PanelDetector.maxDepth { return [b] }
            for horizontal in [horizontalFirst, !horizontalFirst] {
                let runs = gutters(b, horizontal: horizontal)
                if runs.isEmpty { continue }
                var pieces = split(b, horizontal: horizontal, runs)
                    .compactMap { trim($0, 0.98) }
                    .filter { $0.w >= minW && $0.h >= minH }
                if !horizontal && rtl { pieces.reverse() }
                if pieces.isEmpty { return [b] }
                // A caption strip was dropped: what's left may still split the other way.
                if pieces.count == 1 {
                    return cut(pieces[0], horizontalFirst: !horizontal, rtl: rtl, depth: depth + 1, minW: minW, minH: minH)
                }
                return pieces.flatMap {
                    cut($0, horizontalFirst: !horizontal, rtl: rtl, depth: depth + 1, minW: minW, minH: minH)
                }
            }
            return [b]
        }
    }

    /// The page scaled down to at most `analysisSize` px on its long side, as RGB triples.
    struct Thumb {
        let px: [UInt8]
        let w: Int
        let h: Int

        init(rgba: [UInt8], width: Int, height: Int) {
            let long = max(width, height)
            let s = long <= PanelDetector.analysisSize ? 1 : Double(PanelDetector.analysisSize) / Double(long)
            w = max(1, Int(Double(width) * s))
            h = max(1, Int(Double(height) * s))
            var out = [UInt8](repeating: 0, count: w * h * 3)
            for y in 0..<h {
                let sy = min(height - 1, y * height / h)
                for x in 0..<w {
                    let sx = min(width - 1, x * width / w)
                    let si = (sy * width + sx) * 4
                    let di = (y * w + x) * 3
                    out[di] = rgba[si]
                    out[di + 1] = rgba[si + 1]
                    out[di + 2] = rgba[si + 2]
                }
            }
            px = out
        }

        func cornerColour() -> (Int, Int, Int) {
            var r = 0, g = 0, b = 0, n = 0
            for dx in 0...1 {
                for dy in 0...1 {
                    for (x, y) in [(dx, dy), (w - 1 - dx, dy), (dx, h - 1 - dy), (w - 1 - dx, h - 1 - dy)] {
                        let i = (min(max(y, 0), h - 1) * w + min(max(x, 0), w - 1)) * 3
                        r += Int(px[i])
                        g += Int(px[i + 1])
                        b += Int(px[i + 2])
                        n += 1
                    }
                }
            }
            return (r / n, g / n, b / n)
        }

        /// Distance of pixel `i` from `c`.
        func distance(_ i: Int, _ c: (Int, Int, Int)) -> Int {
            let o = i * 3
            return abs(Int(px[o]) - c.0) + abs(Int(px[o + 1]) - c.1) + abs(Int(px[o + 2]) - c.2)
        }
    }
}

/// Guided view: which panel comes next.
public enum GuideSteps {
    /// The panel after `index` in direction `dir`, or nil to turn the page. -1 means not started.
    public static func next(index: Int, count: Int, dir: Int) -> Int? {
        guard count > 0 else { return nil }
        let to = index < 0 ? (dir > 0 ? 0 : count - 1) : index + dir
        return (0..<count).contains(to) ? to : nil
    }
}
