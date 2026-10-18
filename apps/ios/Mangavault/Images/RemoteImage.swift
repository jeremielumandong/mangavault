import MangavaultKit
import SwiftUI
import UIKit

/// A remote image drawn through `ImagePipeline` with the headers the site
/// needs. Shows `placeholder` until it loads and keeps it on failure.
struct RemoteImage<Placeholder: View>: View {
    let url: String?
    var headers: [String: String] = [:]
    /// Drawn width in points; the pipeline decodes at this size.
    var width: CGFloat
    var contentMode: ContentMode = .fill
    var onLoad: ((UIImage) -> Void)? = nil
    @ViewBuilder var placeholder: () -> Placeholder

    @State private var image: UIImage?
    @Environment(\.displayScale) private var scale

    private var pixelWidth: CGFloat { (width * scale).rounded(.up) }

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else {
                placeholder()
            }
        }
        .task(id: url) {
            guard let url, !url.isEmpty else {
                image = nil
                return
            }
            if let hit = ImagePipeline.shared.cached(url, maxPixelWidth: pixelWidth) {
                image = hit
                onLoad?(hit)
                return
            }
            image = nil
            if let img = try? await ImagePipeline.shared.image(url, headers: headers, maxPixelWidth: pixelWidth) {
                if Task.isCancelled { return }
                withAnimation(.easeOut(duration: 0.15)) { image = img }
                onLoad?(img)
            }
        }
    }
}

/// A title's cover: its image over a stable tinted gradient, 2:3.
struct CoverImage: View {
    let manga: MangaSummary
    var width: CGFloat
    var cornerRadius: CGFloat = 8
    var shade = true
    var onLoad: ((UIImage) -> Void)? = nil

    var body: some View {
        Color.clear
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .frame(width: width)
            .overlay {
                RemoteImage(url: manga.coverUrl, headers: manga.coverHeaders, width: width, onLoad: onLoad) {
                    CoverTint.gradient(id: manga.id)
                }
            }
            .overlay {
                if shade {
                    LinearGradient(
                        stops: [.init(color: .clear, location: 0.55), .init(color: .black.opacity(0.35), location: 1)],
                        startPoint: .top, endPoint: .bottom)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}

/// Average color of an image, for the Details header tint.
enum ImageColor {
    static func average(_ image: UIImage) -> UIColor? {
        guard let cg = image.cgImage else { return nil }
        var px = [UInt8](repeating: 0, count: 4)
        guard let ctx = CGContext(
            data: &px, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        ctx.interpolationQuality = .medium
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        return UIColor(red: CGFloat(px[0]) / 255, green: CGFloat(px[1]) / 255, blue: CGFloat(px[2]) / 255, alpha: 1)
    }
}
