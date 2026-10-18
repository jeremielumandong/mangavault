import MangavaultKit
import SwiftUI
import UIKit

/// Webtoon mode: one continuous strip across chapters. A collection view
/// rather than a SwiftUI list so tall images, cell reuse and keeping the
/// scroll position steady while pages above resize stay under control.
struct WebtoonReader: UIViewRepresentable {
    let model: ReaderModel
    /// What the view depends on, read in the parent's body so SwiftUI
    /// calls `updateUIView` when any of it changes.
    var itemCount: Int
    var scrollToken: Int
    var reloadToken: Int
    var background: ReaderBackground
    var pageGap: Bool

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> UICollectionView {
        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection = .vertical
        layout.minimumInteritemSpacing = 0
        layout.minimumLineSpacing = 0
        let cv = UICollectionView(frame: .zero, collectionViewLayout: layout)
        cv.contentInsetAdjustmentBehavior = .never
        cv.showsVerticalScrollIndicator = false
        cv.alwaysBounceVertical = true
        cv.register(PageCell.self, forCellWithReuseIdentifier: PageCell.reuse)
        cv.register(HostCell.self, forCellWithReuseIdentifier: HostCell.reuse)
        let c = context.coordinator
        cv.dataSource = c
        cv.delegate = c
        cv.prefetchDataSource = c
        c.collectionView = cv

        let tap = UITapGestureRecognizer(target: c, action: #selector(Coordinator.tapped(_:)))
        tap.delegate = c
        tap.cancelsTouchesInView = false
        cv.addGestureRecognizer(tap)
        let press = UILongPressGestureRecognizer(target: c, action: #selector(Coordinator.pressed(_:)))
        press.delegate = c
        cv.addGestureRecognizer(press)
        c.sync(from: model)
        return cv
    }

    func updateUIView(_ cv: UICollectionView, context: Context) {
        context.coordinator.model = model
        context.coordinator.sync(from: model)
    }

    @MainActor
    final class Coordinator: NSObject, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout,
        UICollectionViewDataSourcePrefetching, UIGestureRecognizerDelegate
    {
        var model: ReaderModel
        weak var collectionView: UICollectionView?
        private var items: [ReaderItem] = []
        private var ids: [String] = []
        /// height / width by image URL, learned as images load.
        private var aspects: [String: CGFloat] = [:]
        private var scrollToken = -1
        private var reloadToken = -1
        private var paletteKey: ReaderBackground?
        private var gap: Bool?
        private var lastReported = -1
        private var dragStartY: CGFloat = 0
        private var pendingScroll: ScrollRequest?

        init(model: ReaderModel) { self.model = model }

        // MARK: Model → view

        func sync(from model: ReaderModel) {
            guard let cv = collectionView else { return }
            let s = model.settings
            if paletteKey != s.readerBackground {
                paletteKey = s.readerBackground
                cv.backgroundColor = model.palette.uiBackground
                cv.reconfigureItems(at: cv.indexPathsForVisibleItems)
            }
            if gap != s.pageGap {
                gap = s.pageGap
                (cv.collectionViewLayout as? UICollectionViewFlowLayout)?.minimumLineSpacing = s.pageGap ? 8 : 0
            }

            let newIds = model.items.map(\.id)
            if newIds != ids {
                if !ids.isEmpty && newIds.count > ids.count && Array(newIds.prefix(ids.count)) == ids {
                    let added = (ids.count..<newIds.count).map { IndexPath(item: $0, section: 0) }
                    items = model.items
                    ids = newIds
                    UIView.performWithoutAnimation { cv.insertItems(at: added) }
                } else {
                    items = model.items
                    ids = newIds
                    lastReported = -1
                    cv.reloadData()
                }
            }

            if let r = model.reload, r.token != reloadToken {
                reloadToken = r.token
                if let i = ids.firstIndex(of: r.id) {
                    cv.reconfigureItems(at: [IndexPath(item: i, section: 0)])
                }
            }

            if model.scrollRequest.token != scrollToken {
                scrollToken = model.scrollRequest.token
                pendingScroll = model.scrollRequest
                DispatchQueue.main.async { [weak self] in self?.applyPendingScroll() }
            }
        }

        private func applyPendingScroll() {
            guard let cv = collectionView, let r = pendingScroll else { return }
            guard cv.bounds.width > 0 else {
                DispatchQueue.main.async { [weak self] in self?.applyPendingScroll() }
                return
            }
            pendingScroll = nil
            guard items.indices.contains(r.index) else { return }
            cv.layoutIfNeeded()
            lastReported = r.index
            cv.scrollToItem(at: IndexPath(item: r.index, section: 0), at: r.centered ? .centeredVertically : .top, animated: false)
        }

        // MARK: Data source

        func collectionView(_ cv: UICollectionView, numberOfItemsInSection section: Int) -> Int { items.count }

        func collectionView(_ cv: UICollectionView, cellForItemAt ip: IndexPath) -> UICollectionViewCell {
            let item = items[ip.item]
            let model = self.model
            switch item.slot {
            case .page(_, let p, _):
                let cell = cv.dequeueReusableCell(withReuseIdentifier: PageCell.reuse, for: ip) as! PageCell
                if let info = model.pageInfo(item) {
                    let url = info.page.url
                    cell.configure(
                        url: url, headers: info.headers, number: p, palette: model.palette,
                        pixelWidth: cv.bounds.width * cv.traitCollection.displayScale,
                        accent: UIColor(model.app.theme.ac)
                    ) { [weak self] aspect in
                        // Never relayout from inside cellForItemAt.
                        DispatchQueue.main.async { self?.learned(aspect: aspect, url: url, id: item.id) }
                    }
                }
                return cell
            case .divider(let from, let to), .transition(let from, let to):
                let cell = cv.dequeueReusableCell(withReuseIdentifier: HostCell.reuse, for: ip) as! HostCell
                cell.contentConfiguration = UIHostingConfiguration { DividerCard(model: model, from: from, to: to) }
                    .margins(.all, 0)
                cell.backgroundColor = .clear
                return cell
            case .end(let c):
                let cell = cv.dequeueReusableCell(withReuseIdentifier: HostCell.reuse, for: ip) as! HostCell
                cell.contentConfiguration = UIHostingConfiguration { CaughtUpView(model: model, chapterId: c) }
                    .margins(.all, 0)
                cell.backgroundColor = .clear
                return cell
            }
        }

        func collectionView(_ cv: UICollectionView, prefetchItemsAt ips: [IndexPath]) {
            for ip in ips where items.indices.contains(ip.item) {
                if let info = model.pageInfo(items[ip.item]) {
                    ImagePipeline.shared.prefetch(info.page.url, headers: info.headers)
                }
            }
        }

        // MARK: Layout

        private func aspect(of item: ReaderItem) -> CGFloat {
            guard let info = model.pageInfo(item) else { return 1.45 }
            if let a = aspects[info.page.url] { return a }
            if let a = info.page.aspect { return CGFloat(a) }
            return 1.45
        }

        func collectionView(_ cv: UICollectionView, layout: UICollectionViewLayout, sizeForItemAt ip: IndexPath) -> CGSize {
            let w = cv.bounds.width
            guard items.indices.contains(ip.item) else { return CGSize(width: w, height: 1) }
            let item = items[ip.item]
            switch item.slot {
            case .page: return CGSize(width: w, height: max(1, (w * aspect(of: item)).rounded()))
            case .divider, .transition: return CGSize(width: w, height: 140)
            case .end: return CGSize(width: w, height: max(560, cv.bounds.height * 0.75))
            }
        }

        /// An image loaded with a different shape than guessed: resize its
        /// cell, and if it sits above the viewport shift the offset by the
        /// same amount so what's on screen doesn't jump.
        private func learned(aspect: CGFloat, url: String, id: String) {
            guard aspect.isFinite, aspect > 0, let cv = collectionView, let i = ids.firstIndex(of: id) else { return }
            let old = self.aspect(of: items[i])
            aspects[url] = aspect
            guard abs(old - aspect) > 0.004 else { return }
            let ip = IndexPath(item: i, section: 0)
            let above = (cv.layoutAttributesForItem(at: ip)?.frame.minY ?? 0) < cv.contentOffset.y - 1
            let delta = ((cv.bounds.width * aspect).rounded() - (cv.bounds.width * old).rounded())
            UIView.performWithoutAnimation {
                let ctx = UICollectionViewFlowLayoutInvalidationContext()
                ctx.invalidateFlowLayoutDelegateMetrics = true
                cv.collectionViewLayout.invalidateLayout(with: ctx)
                cv.layoutIfNeeded()
                if above { cv.contentOffset.y += delta }
            }
        }

        // MARK: Scrolling

        func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
            dragStartY = scrollView.contentOffset.y
            if model.moreMenu { model.moreMenu = false }
        }

        func scrollViewDidScroll(_ sv: UIScrollView) {
            guard let cv = collectionView, sv.isTracking || sv.isDragging || sv.isDecelerating else { return }
            if model.controls && sv.isDragging && abs(sv.contentOffset.y - dragStartY) > 90 {
                model.controls = false
            }
            var index: Int?
            if sv.contentOffset.y >= sv.contentSize.height - sv.bounds.height - 2 {
                index = items.count - 1
            } else {
                let y = sv.contentOffset.y + sv.bounds.height * 0.4
                index = cv.indexPathForItem(at: CGPoint(x: sv.bounds.midX, y: y))?.item
                    ?? cv.indexPathForItem(at: CGPoint(x: sv.bounds.midX, y: y + 8))?.item
            }
            if let i = index, i >= 0, i != lastReported {
                lastReported = i
                model.didShow(i)
            }
        }

        // MARK: Gestures

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            var v = touch.view
            while let x = v, !(x is UICollectionView) {
                if x is UIControl || x is HostCell { return false }
                v = x.superview
            }
            return true
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }

        @objc func tapped(_ g: UITapGestureRecognizer) {
            guard model.sheet == nil else { return }
            model.toggleControls()
        }

        @objc func pressed(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let cv = collectionView,
                let ip = cv.indexPathForItem(at: g.location(in: cv)), items.indices.contains(ip.item)
            else { return }
            let item = items[ip.item]
            guard item.isPage else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            model.moreMenu = false
            model.sheet = .pageMenu(item)
        }
    }
}

/// Cells hosting SwiftUI (dividers, the end card). Gestures skip them so
/// their buttons work.
final class HostCell: UICollectionViewCell {
    static let reuse = "host"
}

/// The loading band, in UIKit for cells.
final class ShimmerView: UIView {
    private let gradient = CAGradientLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.addSublayer(gradient)
        gradient.startPoint = CGPoint(x: 0, y: 0.5)
        gradient.endPoint = CGPoint(x: 1, y: 0.5)
        gradient.locations = [0.3, 0.5, 0.7]
        isUserInteractionEnabled = false
        clipsToBounds = true
    }

    required init?(coder: NSCoder) { fatalError() }

    func colors(_ base: UIColor, _ hi: UIColor) {
        backgroundColor = base
        gradient.colors = [base.cgColor, hi.cgColor, base.cgColor]
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        gradient.frame = CGRect(x: -w, y: 0, width: w * 3, height: bounds.height)
        start()
    }

    func start() {
        guard !isHidden, gradient.animation(forKey: "shimmer") == nil, bounds.width > 0 else { return }
        let a = CABasicAnimation(keyPath: "transform.translation.x")
        a.fromValue = bounds.width
        a.toValue = -bounds.width
        a.duration = 1.3
        a.repeatCount = .infinity
        gradient.add(a, forKey: "shimmer")
    }

    func stop() { gradient.removeAllAnimations() }
}

/// "Page N didn't load · Tap to retry".
final class PageFailView: UIView {
    let label = UILabel()
    let button = UIButton(type: .system)
    private let icon = UIImageView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        icon.image = UIImage(systemName: "photo.badge.exclamationmark") ?? UIImage(systemName: "exclamationmark.triangle")
        icon.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 34)
        icon.alpha = 0.7
        label.font = MV.uiFont(15, .semibold)
        var conf = UIButton.Configuration.filled()
        conf.cornerStyle = .capsule
        conf.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 22, bottom: 0, trailing: 22)
        conf.attributedTitle = AttributedString("Tap to retry", attributes: AttributeContainer([.font: MV.uiFont(15, .semibold)]))
        button.configuration = conf
        let stack = UIStackView(arrangedSubviews: [icon, label, button])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
            button.heightAnchor.constraint(equalToConstant: 44),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    func style(_ palette: ReaderPalette, accent: UIColor) {
        backgroundColor = palette.uiSk
        icon.tintColor = palette.uiFg
        label.textColor = palette.uiFg
        button.configuration?.baseBackgroundColor = accent
        button.configuration?.baseForegroundColor = .white
    }
}

/// One webtoon page: shimmer while loading, the image, or the retry card.
final class PageCell: UICollectionViewCell {
    static let reuse = "page"
    private let imageView = UIImageView()
    private let shimmer = ShimmerView()
    private let fail = PageFailView()
    private var task: Task<Void, Never>?
    private var url: String?
    private var headers: [String: String] = [:]
    private var pixelWidth: CGFloat = 0
    private var onAspect: ((CGFloat) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleToFill
        imageView.clipsToBounds = true
        for v in [shimmer, imageView, fail] as [UIView] {
            v.frame = contentView.bounds
            v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            contentView.addSubview(v)
        }
        fail.button.addAction(UIAction { [weak self] _ in self?.load() }, for: .touchUpInside)
        isAccessibilityElement = true
    }

    required init?(coder: NSCoder) { fatalError() }

    override func prepareForReuse() {
        super.prepareForReuse()
        task?.cancel()
        task = nil
        url = nil
        imageView.image = nil
    }

    func configure(
        url: String, headers: [String: String], number: Int, palette: ReaderPalette, pixelWidth: CGFloat,
        accent: UIColor, onAspect: @escaping (CGFloat) -> Void
    ) {
        contentView.backgroundColor = palette.uiBackground
        shimmer.colors(palette.uiSk, palette.uiSk2)
        fail.style(palette, accent: accent)
        fail.label.text = "Page \(number) didn't load"
        accessibilityLabel = "Page \(number)"
        self.url = url
        self.headers = headers
        self.pixelWidth = pixelWidth
        self.onAspect = onAspect
        load()
    }

    private func load() {
        guard let url else { return }
        task?.cancel()
        if let img = ImagePipeline.shared.cached(url, maxPixelWidth: pixelWidth) {
            show(img)
            return
        }
        imageView.image = nil
        imageView.isHidden = true
        fail.isHidden = true
        shimmer.isHidden = false
        shimmer.start()
        let headers = self.headers
        let width = pixelWidth
        task = Task { [weak self] in
            do {
                let img = try await ImagePipeline.shared.image(url, headers: headers, maxPixelWidth: width)
                guard let self, self.url == url else { return }
                self.show(img)
            } catch {
                guard !Task.isCancelled, let self, self.url == url else { return }
                self.shimmer.stop()
                self.shimmer.isHidden = true
                self.fail.isHidden = false
            }
        }
    }

    private func show(_ img: UIImage) {
        imageView.image = img
        imageView.isHidden = false
        shimmer.stop()
        shimmer.isHidden = true
        fail.isHidden = true
        if img.size.width > 0 { onAspect?(img.size.height / img.size.width) }
    }
}
