import XCTest
@testable import MangavaultKit

/// Same pages as the Android app's PanelDetectorTest.
final class PanelDetectorTests: XCTestCase {
    typealias RGB = (UInt8, UInt8, UInt8)
    let white: RGB = (255, 255, 255)
    let black: RGB = (0, 0, 0)
    let grey: RGB = (128, 128, 128)

    /// A w×h page in `paper` with bordered grey panels.
    struct Page {
        let w: Int
        let h: Int
        var px: [UInt8]

        init(_ w: Int, _ h: Int, _ paper: RGB) {
            self.w = w
            self.h = h
            px = [UInt8](repeating: 255, count: w * h * 4)
            fill(0, 0, w, h, paper)
        }

        mutating func fill(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, _ c: RGB) {
            for y in y0..<y1 {
                for x in x0..<x1 {
                    let i = (y * w + x) * 4
                    px[i] = c.0
                    px[i + 1] = c.1
                    px[i + 2] = c.2
                }
            }
        }

        mutating func panel(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, border: RGB = (0, 0, 0), inside: RGB = (128, 128, 128)) {
            fill(x0, y0, x1, y1, border)
            fill(x0 + 3, y0 + 3, x1 - 3, y1 - 3, inside)
        }

        func detect(rtl: Bool = false) -> [PanelRect] { PanelDetector.detect(rgba: px, width: w, height: h, rtl: rtl) }
    }

    func assertNear(_ e: PanelRect, _ a: PanelRect, file: StaticString = #filePath, line: UInt = #line) {
        let t = 0.02
        XCTAssert(
            abs(e.left - a.left) < t && abs(e.top - a.top) < t && abs(e.right - a.right) < t && abs(e.bottom - a.bottom) < t,
            "expected \(e), got \(a)", file: file, line: line)
    }

    func testGridReadsRowsThenLeftToRight() {
        var p = Page(400, 600, white)
        p.panel(20, 20, 380, 200)
        p.panel(20, 220, 190, 400)
        p.panel(210, 220, 380, 400)
        p.panel(20, 420, 380, 580)
        let panels = p.detect()
        XCTAssertEqual(panels.count, 5)
        guard panels.count == 5 else { return }
        assertNear(PanelRect(left: 0.05, top: 0.033, right: 0.95, bottom: 0.333), panels[0])
        assertNear(PanelRect(left: 0.05, top: 0.367, right: 0.475, bottom: 0.667), panels[1])
        assertNear(PanelRect(left: 0.525, top: 0.367, right: 0.95, bottom: 0.667), panels[2])
        assertNear(PanelRect(left: 0.05, top: 0.7, right: 0.95, bottom: 0.967), panels[3])
        XCTAssertEqual(panels.last, .full)
    }

    func testRightToLeftForManga() {
        var p = Page(400, 600, white)
        p.panel(20, 20, 190, 290)
        p.panel(210, 20, 380, 290)
        p.panel(20, 310, 380, 580)
        let panels = p.detect(rtl: true)
        XCTAssertEqual(panels.count, 4)
        guard panels.count == 4 else { return }
        XCTAssertGreaterThan(panels[0].left, 0.5)
        XCTAssertLessThan(panels[1].left, 0.1)
        XCTAssertGreaterThan(panels[2].top, 0.5)
    }

    func testTallPanelBesideStackedPanels() {
        var p = Page(400, 600, white)
        p.panel(20, 20, 190, 580)
        p.panel(210, 20, 380, 290)
        p.panel(210, 310, 380, 580)
        let panels = p.detect()
        XCTAssertEqual(panels.count, 4)
        guard panels.count == 4 else { return }
        XCTAssert(panels[0].left < 0.1 && panels[0].bottom > 0.9)
        XCTAssert(panels[1].left > 0.5 && panels[1].bottom < 0.5)
        XCTAssert(panels[2].left > 0.5 && panels[2].top > 0.5)
    }

    func testWhiteSkyInsideAPanelDoesNotSplitIt() {
        var p = Page(400, 600, white)
        p.panel(20, 20, 380, 290)
        p.fill(23, 120, 377, 160, white)
        p.panel(20, 310, 380, 580)
        let panels = p.detect()
        XCTAssertEqual(panels.count, 3)
        guard panels.count == 3 else { return }
        assertNear(PanelRect(left: 0.05, top: 0.033, right: 0.95, bottom: 0.483), panels[0])
    }

    func testBlackGutters() {
        var p = Page(400, 600, black)
        p.panel(20, 20, 380, 290, border: white, inside: grey)
        p.panel(20, 310, 380, 580, border: white, inside: grey)
        let panels = p.detect()
        XCTAssertEqual(panels.count, 3)
        guard panels.count == 3 else { return }
        XCTAssert(panels[0].bottom < 0.5 && panels[1].top > 0.5)
    }

    func testSplashPageIsJustTheWholePage() {
        var p = Page(400, 600, grey)
        p.fill(100, 100, 300, 500, black)
        XCTAssertEqual(p.detect(), [.full])
    }

    func testLargePagesAreScaledDown() {
        var p = Page(1600, 2400, white)
        p.panel(80, 80, 1520, 1160)
        p.panel(80, 1240, 1520, 2320)
        let panels = p.detect()
        XCTAssertEqual(panels.count, 3)
        guard panels.count == 3 else { return }
        assertNear(PanelRect(left: 0.05, top: 0.033, right: 0.95, bottom: 0.483), panels[0])
    }

    func testGuideSteps() {
        XCTAssertEqual(GuideSteps.next(index: -1, count: 4, dir: 1), 0)
        XCTAssertEqual(GuideSteps.next(index: -1, count: 4, dir: -1), 3)
        XCTAssertEqual(GuideSteps.next(index: 1, count: 4, dir: 1), 2)
        XCTAssertNil(GuideSteps.next(index: 3, count: 4, dir: 1))
        XCTAssertNil(GuideSteps.next(index: 0, count: 4, dir: -1))
        XCTAssertNil(GuideSteps.next(index: -1, count: 0, dir: 1))
    }
}
