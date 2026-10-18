import MangavaultKit
import SwiftUI
import UIKit

/// Paged modes (right to left, left to right, vertical pages) on a
/// UIPageViewController. Orientation can't change after creation, so the
/// parent rebuilds this view with `.id(mode)`.
struct PagedReader: UIViewControllerRepresentable {
    let model: ReaderModel
    /// Read in the parent's body so `updateUIViewController` runs on change.
    var position: Int
    var itemCount: Int
    var reloadToken: Int
    var background: ReaderBackground
    var fit: ReaderFit
    var guided: Bool

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIViewController(context: Context) -> UIPageViewController {
        let pvc = UIPageViewController(
            transitionStyle: .scroll,
            navigationOrientation: model.mode == .pagedV ? .vertical : .horizontal,
            options: [.interPageSpacing: 0])
        pvc.dataSource = model.guided ? nil : context.coordinator
        pvc.delegate = context.coordinator
        pvc.view.backgroundColor = model.palette.uiBackground
        context.coordinator.pager = pvc
        context.coordinator.attachGuide()
        context.coordinator.show(model.position, animated: false)
        return pvc
    }

    func updateUIViewController(_ pvc: UIPageViewController, context: Context) {
        let c = context.coordinator
        c.model = model
        pvc.view.backgroundColor = model.palette.uiBackground
        // Guided view steps with taps and swipes, so the pager's own swiping is off.
        let source: UIPageViewControllerDataSource? = model.guided ? nil : c
        if (pvc.dataSource == nil) != (source == nil) { pvc.dataSource = source }
        c.attachGuide()
        c.update()
    }

    @MainActor
    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        var model: ReaderModel
        weak var pager: UIPageViewController?
        private var style: (ReaderBackground, ReaderFit, Bool)?
        private var reloadToken = -1
        private var animating = false

        init(model: ReaderModel) { self.model = model }

        var current: ReaderPageController? { pager?.viewControllers?.first as? ReaderPageController }

        func attachGuide() {
            model.guideStep = { [weak self] dir in self?.current?.guideStep(dir) ?? false }
        }

        func update() {
            let s = model.settings
            if style.map({ $0.0 != s.readerBackground || $0.1 != model.fit || $0.2 != model.guided }) ?? true {
                style = (s.readerBackground, model.fit, model.guided)
                for vc in pager?.viewControllers ?? [] { (vc as? ReaderPageController)?.restyle() }
            }
            if let r = model.reload, r.token != reloadToken {
                reloadToken = r.token
                if let cur = current, cur.item.id == r.id { cur.reload() }
            }
            guard !animating, model.items.indices.contains(model.position) else { return }
            let target = model.items[model.position]
            guard let cur = current else {
                show(model.position, animated: false)
                return
            }
            if cur.item.id == target.id {
                // Same slot; the sequence may have been rebuilt around it.
                cur.index = model.position
                return
            }
            if let from = model.items.firstIndex(where: { $0.id == cur.item.id }), abs(from - model.position) == 1 {
                show(model.position, animated: true, forward: model.position > from)
            } else {
                show(model.position, animated: false)
            }
        }

        func show(_ index: Int, animated: Bool, forward: Bool = true) {
            guard let pager, let vc = controller(at: index) else { return }
            // RTL: "next" sits on the left, which UIKit calls reverse.
            let rtl = model.mode == .rtl
            let dir: UIPageViewController.NavigationDirection = (forward != rtl) ? .forward : .reverse
            animating = animated
            pager.setViewControllers([vc], direction: dir, animated: animated) { [weak self] _ in
                self?.animating = false
            }
        }

        private func controller(at index: Int) -> ReaderPageController? {
            guard model.items.indices.contains(index) else { return nil }
            return ReaderPageController(model: model, item: model.items[index], index: index)
        }

        private func neighbour(of vc: UIViewController, step: Int) -> UIViewController? {
            guard let p = vc as? ReaderPageController else { return nil }
            // Find the slot again: the sequence grows as chapters load.
            let i = model.items.firstIndex { $0.id == p.item.id } ?? p.index
            return controller(at: i + step)
        }

        func pageViewController(_ pvc: UIPageViewController, viewControllerBefore vc: UIViewController) -> UIViewController? {
            neighbour(of: vc, step: model.mode == .rtl ? 1 : -1)
        }

        func pageViewController(_ pvc: UIPageViewController, viewControllerAfter vc: UIViewController) -> UIViewController? {
            neighbour(of: vc, step: model.mode == .rtl ? -1 : 1)
        }

        func pageViewController(_ pvc: UIPageViewController, willTransitionTo pending: [UIViewController]) {
            if model.moreMenu { model.moreMenu = false }
        }

        func pageViewController(
            _ pvc: UIPageViewController, didFinishAnimating finished: Bool,
            previousViewControllers: [UIViewController], transitionCompleted completed: Bool
        ) {
            guard completed, let cur = current else { return }
            let i = model.items.firstIndex { $0.id == cur.item.id } ?? cur.index
            cur.index = i
            model.zoomed = false
            model.didShow(i)
        }
    }
}

/// One slot of the pager: a zoomable page, the transition page or the end.
final class ReaderPageController: UIViewController, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    let model: ReaderModel
    let item: ReaderItem
    var index: Int
    private let scroll = UIScrollView()
    private let imageView = UIImageView()
    private let shimmer = ShimmerView()
    private let fail = PageFailView()
    private var host: UIHostingController<AnyView>?
    private var task: Task<Void, Never>?
    /// Bounds and image size the page was last laid out for, so other layout passes don't
    /// jump a page the reader has panned back to the top.
    private var laidOut: (CGSize, CGSize?)?
    // Guided view: the page's panels (ending with the whole page) and which is on screen.
    private var panels: [PanelRect]?
    private var panelIndex = -1
    private var guideTask: Task<Void, Never>?
    private var guidedOn = false
    /// Set once the reader pinches or double-taps, so one finger pans again.
    private var free = false
    private var swipes: [UISwipeGestureRecognizer] = []

    init(model: ReaderModel, item: ReaderItem, index: Int) {
        self.model = model
        self.item = item
        self.index = index
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    deinit {
        task?.cancel()
        guideTask?.cancel()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = model.palette.uiBackground
        for d in [UISwipeGestureRecognizer.Direction.left, .right, .up, .down] {
            let s = UISwipeGestureRecognizer(target: self, action: #selector(swiped(_:)))
            s.direction = d
            s.isEnabled = false
            view.addGestureRecognizer(s)
            swipes.append(s)
        }
        switch item.slot {
        case .page(_, let p, let n):
            setUpPage(number: p, of: n)
        case .transition(let from, let to), .divider(let from, let to):
            embed(AnyView(TransitionPage(model: model, from: from, to: to)))
        case .end(let c):
            embed(AnyView(CaughtUpView(model: model, chapterId: c)))
        }
        applyGuided()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if model.guided, panels != nil, panelIndex < 0 { enterGuide() }
    }

    // MARK: Hosted pages

    private func embed(_ content: AnyView) {
        let model = self.model
        let wrapped = AnyView(
            GeometryReader { g in
                ZStack {
                    // Tap zones still work around the card's buttons.
                    Color.clear.contentShape(Rectangle())
                        .gesture(
                            SpatialTapGesture().onEnded { v in
                                model.handleTap(
                                    x: v.location.x / max(1, g.size.width), y: v.location.y / max(1, g.size.height))
                            })
                    content
                }
            }
        )
        let h = UIHostingController(rootView: wrapped)
        h.view.backgroundColor = .clear
        addChild(h)
        h.view.frame = view.bounds
        h.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(h.view)
        h.didMove(toParent: self)
        host = h
    }

    // MARK: Image pages

    private func setUpPage(number: Int, of count: Int) {
        scroll.delegate = self
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 4
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.contentInsetAdjustmentBehavior = .never
        // Normal deceleration gives a zoomed page a long momentum glide after a flick.
        scroll.decelerationRate = .normal
        scroll.bouncesZoom = true
        scroll.frame = view.bounds
        scroll.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(scroll)
        imageView.contentMode = .scaleToFill
        scroll.addSubview(imageView)
        for v in [shimmer, fail] as [UIView] {
            v.frame = view.bounds
            v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(v)
        }
        fail.label.text = "Page \(number) didn't load"
        fail.button.addAction(UIAction { [weak self] _ in self?.reload() }, for: .touchUpInside)
        view.accessibilityLabel = "Page \(number) of \(count)"

        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        double.delegate = self
        view.addGestureRecognizer(double)
        let single = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        single.require(toFail: double)
        single.delegate = self
        view.addGestureRecognizer(single)
        let press = UILongPressGestureRecognizer(target: self, action: #selector(pressed(_:)))
        view.addGestureRecognizer(press)
        scroll.pinchGestureRecognizer?.addTarget(self, action: #selector(pinched(_:)))
        restyle()
        load()
    }

    func restyle() {
        let pal = model.palette
        view.backgroundColor = pal.uiBackground
        shimmer.colors(pal.uiSk, pal.uiSk2)
        fail.style(pal, accent: UIColor(model.app.theme.ac))
        laidOut = nil
        if isViewLoaded { view.setNeedsLayout() }
        applyGuided()
    }

    func reload() {
        guard let info = model.pageInfo(item) else { return }
        ImagePipeline.shared.evict(info.page.url)
        panels = nil
        panelIndex = -1
        load()
    }

    private var pixelWidth: CGFloat {
        let screen = view.window?.windowScene?.screen.bounds.size ?? UIScreen.main.bounds.size
        let scale = view.window?.traitCollection.displayScale ?? UIScreen.main.scale
        // Decode large enough to zoom in a little without going soft.
        return max(screen.width, screen.height) * scale * 1.5
    }

    private func load() {
        guard let info = model.pageInfo(item) else { return }
        task?.cancel()
        let url = info.page.url
        let width = pixelWidth
        if let img = ImagePipeline.shared.cached(url, maxPixelWidth: width) {
            show(img)
            return
        }
        imageView.image = nil
        fail.isHidden = true
        shimmer.isHidden = false
        shimmer.start()
        let headers = info.headers
        task = Task { [weak self] in
            do {
                let img = try await ImagePipeline.shared.image(url, headers: headers, maxPixelWidth: width)
                self?.show(img)
            } catch {
                guard !Task.isCancelled, let self else { return }
                self.shimmer.stop()
                self.shimmer.isHidden = true
                self.fail.isHidden = false
            }
        }
    }

    private func show(_ img: UIImage) {
        imageView.image = img
        shimmer.stop()
        shimmer.isHidden = true
        fail.isHidden = true
        scroll.zoomScale = 1
        laidOut = nil
        view.setNeedsLayout()
        if model.guided { detectPanels() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard item.isPage, scroll.zoomScale == 1 else { return }
        let key = (view.bounds.size, imageView.image?.size)
        if let last = laidOut, last.0 == key.0, last.1 == key.1 { return }
        laidOut = key
        layoutImage()
    }

    /// Sizes the image for the fit setting at zoom 1.
    private func layoutImage() {
        let bounds = view.bounds.size
        guard bounds.width > 0, bounds.height > 0 else { return }
        let img = imageView.image?.size
        let aspect: CGFloat = {
            if let s = img, s.width > 0 { return s.height / s.width }
            if let a = model.pageInfo(item)?.page.aspect { return CGFloat(a) }
            return 1.45
        }()
        var size: CGSize
        switch model.fit {
        case .width:
            size = CGSize(width: bounds.width, height: bounds.width * aspect)
        case .height:
            size = CGSize(width: bounds.height / aspect, height: bounds.height)
        case .screen:
            let w = min(bounds.width, bounds.height / aspect)
            size = CGSize(width: w, height: w * aspect)
        case .original:
            let scale = view.traitCollection.displayScale
            let pw = (img?.width ?? bounds.width * scale) / max(1, scale)
            let w = min(pw, bounds.width)
            size = CGSize(width: w, height: w * aspect)
        }
        size = CGSize(width: size.width.rounded(), height: size.height.rounded())
        imageView.frame = CGRect(origin: .zero, size: size)
        scroll.contentSize = size
        center()
        // Tall pages at fit-width start at the top.
        scroll.contentOffset = CGPoint(x: -scroll.contentInset.left, y: -scroll.contentInset.top)
    }

    private func center() {
        let b = scroll.bounds.size
        let c = scroll.contentSize
        let x = max(0, (b.width - c.width) / 2)
        let y = max(0, (b.height - c.height) / 2)
        scroll.contentInset = UIEdgeInsets(top: y, left: x, bottom: y, right: x)
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        center()
        let z = scrollView.zoomScale > 1.01
        if model.zoomed != z { model.zoomed = z }
    }

    // MARK: Gestures

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        var v = touch.view
        while let x = v, x !== view {
            if x is UIControl { return false }
            v = x.superview
        }
        return true
    }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let p = g.location(in: view)
        model.handleTap(x: p.x / max(1, view.bounds.width), y: p.y / max(1, view.bounds.height))
    }

    @objc private func doubleTapped(_ g: UITapGestureRecognizer) {
        setFree()
        if scroll.zoomScale > 1.01 {
            scroll.setZoomScale(1, animated: true)
            return
        }
        let p = g.location(in: imageView)
        let s: CGFloat = 2.2
        let size = CGSize(width: scroll.bounds.width / s, height: scroll.bounds.height / s)
        scroll.zoom(to: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height), animated: true)
    }

    @objc private func pinched(_ g: UIPinchGestureRecognizer) {
        if g.state == .began { setFree() }
    }

    @objc private func swiped(_ g: UISwipeGestureRecognizer) {
        switch g.direction {
        case .left: model.swipe(dx: -1, dy: 0)
        case .right: model.swipe(dx: 1, dy: 0)
        case .up: model.swipe(dx: 0, dy: -1)
        case .down: model.swipe(dx: 0, dy: 1)
        default: break
        }
    }

    // MARK: Guided view

    /// Turns guided view's gestures on or off: while guiding, one finger swipes between panels
    /// instead of panning, until the reader zooms by hand.
    private func applyGuided() {
        let on = model.guided
        if on != guidedOn {
            guidedOn = on
            free = false
            panels = nil
            panelIndex = -1
            guideTask?.cancel()
            if on, imageView.image != nil { detectPanels() }
        }
        for s in swipes { s.isEnabled = on && !free }
        if item.isPage { scroll.isScrollEnabled = !on || free }
    }

    private func setFree() {
        guard model.guided, !free else { return }
        free = true
        applyGuided()
    }

    private func detectPanels() {
        guard model.guided, let info = model.pageInfo(item) else { return }
        guideTask?.cancel()
        let rtl = model.mode == .rtl
        guideTask = Task { [weak self] in
            let found = await PanelStore.shared.panels(info.page.url, headers: info.headers, rtl: rtl)
            guard let self, !Task.isCancelled, let found, self.model.guided else { return }
            self.panels = found
            if self.panelIndex < 0, self.view.window != nil, self.isCurrent { self.enterGuide() }
        }
    }

    private var isCurrent: Bool {
        model.items.indices.contains(model.position) && model.items[model.position].id == item.id
    }

    /// Arriving going forward starts at the first panel; going back, at the whole page.
    private func enterGuide() {
        guard let p = panels, !p.isEmpty else { return }
        panelIndex = model.movedForward ? 0 : p.count - 1
        showPanel()
    }

    /// One panel forward or back. False when there's none that way, so the page turns.
    func guideStep(_ dir: Int) -> Bool {
        guard model.guided, let p = panels, let to = GuideSteps.next(index: panelIndex, count: p.count, dir: dir)
        else { return false }
        panelIndex = to
        showPanel()
        return true
    }

    private func showPanel() {
        guard let p = panels, p.indices.contains(panelIndex) else { return }
        view.layoutIfNeeded()
        free = false
        applyGuided()
        let r = p[panelIndex]
        let b = imageView.bounds.size
        guard r != .full, b.width > 0, b.height > 0 else {
            scroll.setZoomScale(1, animated: true)
            return
        }
        let rect = CGRect(x: r.left * b.width, y: r.top * b.height, width: r.width * b.width, height: r.height * b.height)
        scroll.zoom(to: rect.insetBy(dx: -rect.width * 0.04, dy: -rect.height * 0.04), animated: true)
    }

    @objc private func pressed(_ g: UILongPressGestureRecognizer) {
        guard g.state == .began else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        model.moreMenu = false
        model.sheet = .pageMenu(item)
    }
}
