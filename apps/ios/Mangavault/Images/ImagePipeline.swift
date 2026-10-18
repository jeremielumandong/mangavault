import CryptoKit
import Foundation
import ImageIO
import UIKit

/// Loads remote images with per-request headers (sites check the Referer),
/// keeps decoded, downsampled images in memory and the original bytes on
/// disk, and dedupes concurrent requests for the same URL.
final class ImagePipeline: @unchecked Sendable {
    static let shared = ImagePipeline()

    enum Failure: Error {
        case badURL
        case status(Int)
        case undecodable
    }

    private let memory = NSCache<NSString, UIImage>()
    private let session: URLSession
    private let diskURL: URL
    private let io = DispatchQueue(label: "mv.images.disk", qos: .utility)
    private let lock = NSLock()
    private var inflight: [String: Task<Data, Error>] = [:]
    private var memoryKeys: [String: Set<NSString>] = [:]
    /// Trim the disk cache back to this size.
    var diskLimit: Int64 = 1_500_000_000

    init() {
        memory.totalCostLimit = 180 * 1024 * 1024
        let config = URLSessionConfiguration.default
        config.urlCache = nil
        config.timeoutIntervalForRequest = 30
        config.httpMaximumConnectionsPerHost = 6
        session = URLSession(configuration: config)
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        diskURL = caches.appendingPathComponent("images", isDirectory: true)
        try? FileManager.default.createDirectory(at: diskURL, withIntermediateDirectories: true)
    }

    private func key(_ url: String) -> String {
        SHA256.hash(data: Data(url.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private func memoryKey(_ url: String, _ maxWidth: CGFloat) -> NSString {
        "\(Int(maxWidth))|\(url)" as NSString
    }

    /// A decoded image already in memory, for drawing without a flash.
    func cached(_ url: String, maxPixelWidth: CGFloat) -> UIImage? {
        memory.object(forKey: memoryKey(url, maxPixelWidth))
    }

    /// `maxPixelWidth` is the width the image will be drawn at, in pixels.
    func image(_ url: String, headers: [String: String], maxPixelWidth: CGFloat) async throws -> UIImage {
        let mkey = memoryKey(url, maxPixelWidth)
        if let img = memory.object(forKey: mkey) { return img }
        let data = try await self.data(url, headers: headers)
        try Task.checkCancellation()
        let img = try await Task.detached(priority: .userInitiated) {
            try ImagePipeline.decode(data, maxPixelWidth: maxPixelWidth)
        }.value
        let cost = Int(img.size.width * img.size.height * img.scale * img.scale * 4)
        memory.setObject(img, forKey: mkey, cost: cost)
        lock.withLock { _ = memoryKeys[url, default: []].insert(mkey) }
        return img
    }

    /// Fetches bytes into the disk cache ahead of time.
    func prefetch(_ url: String, headers: [String: String]) {
        Task.detached(priority: .utility) { [self] in
            _ = try? await self.data(url, headers: headers)
        }
    }

    /// Drops one URL from both caches (Reload page).
    func evict(_ url: String) {
        let keys = lock.withLock { memoryKeys.removeValue(forKey: url) ?? [] }
        for k in keys { memory.removeObject(forKey: k) }
        let file = diskURL.appendingPathComponent(key(url))
        try? FileManager.default.removeItem(at: file)
    }

    func originalData(_ url: String, headers: [String: String]) async throws -> Data {
        try await data(url, headers: headers)
    }

    private func data(_ url: String, headers: [String: String]) async throws -> Data {
        let file = diskURL.appendingPathComponent(key(url))
        if let d = try? Data(contentsOf: file, options: .mappedIfSafe), !d.isEmpty {
            io.async { try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path) }
            return d
        }
        let session = self.session
        let task: Task<Data, Error> = lock.withLock {
            if let t = inflight[url] { return t }
            let t = Task<Data, Error> {
                guard let u = URL(string: url) else { throw Failure.badURL }
                var req = URLRequest(url: u)
                for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
                req.setValue("image/avif,image/webp,image/*,*/*;q=0.8", forHTTPHeaderField: "Accept")
                let (d, res) = try await session.data(for: req)
                let status = (res as? HTTPURLResponse)?.statusCode ?? 200
                guard (200..<300).contains(status) else { throw Failure.status(status) }
                try? d.write(to: file, options: .atomic)
                return d
            }
            inflight[url] = t
            return t
        }
        defer { lock.withLock { inflight[url] = nil } }
        return try await task.value
    }

    /// Decodes at the drawn size: a 720×16000 webtoon strip shown 1179 px
    /// wide stays sharp, a 2000 px cover shown at 336 px costs a fraction.
    static func decode(_ data: Data, maxPixelWidth: CGFloat) throws -> UIImage {
        let opts = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let src = CGImageSourceCreateWithData(data as CFData, opts) else { throw Failure.undecodable }
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        let pw = (props?[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue ?? 0
        let ph = (props?[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue ?? 0
        var maxSide = max(pw, ph)
        if maxPixelWidth > 0, pw > maxPixelWidth {
            maxSide = max(pw, ph) * Double(maxPixelWidth) / pw
        }
        let thumbOpts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, Int(maxSide.rounded())),
        ]
        if maxSide > 0, let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, thumbOpts as CFDictionary) {
            return UIImage(cgImage: cg)
        }
        guard let img = UIImage(data: data) else { throw Failure.undecodable }
        return img
    }

    // MARK: Disk usage

    func diskSize() async -> Int64 {
        let dir = diskURL
        return await withCheckedContinuation { cont in
            io.async {
                cont.resume(returning: Self.size(of: dir))
            }
        }
    }

    private static func size(of dir: URL) -> Int64 {
        let keys: [URLResourceKey] = [.totalFileAllocatedSizeKey, .fileSizeKey]
        guard let e = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: keys) else { return 0 }
        var total: Int64 = 0
        for case let u as URL in e {
            let v = try? u.resourceValues(forKeys: Set(keys))
            total += Int64(v?.totalFileAllocatedSize ?? v?.fileSize ?? 0)
        }
        return total
    }

    func clear() async {
        memory.removeAllObjects()
        lock.withLock { memoryKeys.removeAll() }
        let dir = diskURL
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            io.async {
                try? FileManager.default.removeItem(at: dir)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                cont.resume()
            }
        }
    }

    /// Oldest files go first once the cache passes `diskLimit`.
    func trimDisk() {
        let dir = diskURL, limit = diskLimit
        io.async {
            let keys: [URLResourceKey] = [.contentModificationDateKey, .totalFileAllocatedSizeKey]
            guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)
            else { return }
            var items = files.compactMap { u -> (URL, Date, Int64)? in
                guard let v = try? u.resourceValues(forKeys: Set(keys)) else { return nil }
                return (u, v.contentModificationDate ?? .distantPast, Int64(v.totalFileAllocatedSize ?? 0))
            }
            var total = items.reduce(0) { $0 + $1.2 }
            guard total > limit else { return }
            items.sort { $0.1 < $1.1 }
            for (u, _, size) in items where total > limit * 8 / 10 {
                try? FileManager.default.removeItem(at: u)
                total -= size
            }
        }
    }
}
