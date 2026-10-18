import MangavaultKit
import UIKit
import XCTest

@testable import Mangavault

final class AppTests: XCTestCase {
    func testSettingsDecodeFillsMissingFields() throws {
        // An older save with only a couple of fields keeps them and gets
        // defaults for the rest instead of failing.
        let json = #"{"theme":"light","prefetch":8,"somethingNew":true}"#
        let s = try JSONDecoder().decode(AppSettings.self, from: Data(json.utf8))
        XCTAssertEqual(s.theme, .light)
        XCTAssertEqual(s.prefetch, 8)
        XCTAssertEqual(s.readerBackground, .black)
        XCTAssertTrue(s.keepScreenOn)
        XCTAssertNil(s.readerDefault)
    }

    func testSettingsRoundTrip() throws {
        var s = AppSettings()
        s.readerDefault = .rtl
        s.fit = .screen
        s.brightness = 60
        let back = try JSONDecoder().decode(AppSettings.self, from: JSONEncoder().encode(s))
        XCTAssertEqual(back, s)
    }

    func testReaderPaletteContrast() {
        XCTAssertNotEqual(ReaderPalette.of(.white).uiFg, ReaderPalette.of(.black).uiFg)
        XCTAssertEqual(UIColor(hex: 0xFF0000).cgColor.components?.first ?? 0, 1, accuracy: 0.001)
    }

    func testDecodeDownsamplesToDrawnWidth() throws {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 1200), format: {
            let f = UIGraphicsImageRendererFormat()
            f.scale = 1
            return f
        }())
        let png = renderer.pngData { ctx in
            UIColor.red.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 400, height: 1200))
        }
        let img = try ImagePipeline.decode(png, maxPixelWidth: 100)
        let w = img.size.width * img.scale
        let h = img.size.height * img.scale
        XCTAssertEqual(w, 100, accuracy: 1)
        XCTAssertEqual(h / w, 3, accuracy: 0.05)

        let full = try ImagePipeline.decode(png, maxPixelWidth: 2000)
        XCTAssertEqual(full.size.width * full.scale, 400, accuracy: 1)
    }

    func testAPIBaseURLIsConfigured() {
        // Whatever Config.local.xcconfig set, the app builds an https /v1 URL.
        XCTAssertTrue(AppConfig.baseURL.absoluteString.hasSuffix("/v1"))
        XCTAssertFalse(AppConfig.apiDomain.isEmpty)
    }
}
