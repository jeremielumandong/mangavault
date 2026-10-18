import CoreGraphics
import Foundation
import ImageIO
import MangavaultKit

/// Guided view panels per page, found once from a small decode of the bytes
/// the image pipeline already keeps on disk.
actor PanelStore {
    static let shared = PanelStore()

    private var cache: [String: [PanelRect]] = [:]
    private var order: [String] = []

    /// Nil when the image couldn't be loaded; that isn't cached, so a reload tries again.
    func panels(_ url: String, headers: [String: String], rtl: Bool) async -> [PanelRect]? {
        let key = "\(rtl)|\(url)"
        if let p = cache[key] { return p }
        guard let data = try? await ImagePipeline.shared.originalData(url, headers: headers),
            let img = Self.pixels(data)
        else { return nil }
        let p = PanelDetector.detect(rgba: img.rgba, width: img.width, height: img.height, rtl: rtl)
        if cache[key] == nil { order.append(key) }
        cache[key] = p
        if order.count > 200 { cache[order.removeFirst()] = nil }
        return p
    }

    /// RGBA bytes of the image scaled to the detector's analysis size.
    static func pixels(_ data: Data) -> (rgba: [UInt8], width: Int, height: Int)? {
        let srcOpts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let src = CGImageSourceCreateWithData(data as CFData, srcOpts) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: PanelDetector.analysisSize,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        let w = cg.width
        let h = cg.height
        guard w > 0, h > 0 else { return nil }
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let drawn = px.withUnsafeMutableBytes { buf -> Bool in
            guard
                let ctx = CGContext(
                    data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
            else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return drawn ? (px, w, h) : nil
    }
}
