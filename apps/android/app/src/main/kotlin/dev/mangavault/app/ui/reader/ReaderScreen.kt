package dev.mangavault.app.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mangavault.app.ui.LocalContainer
import dev.mangavault.app.ui.Navigator
import dev.mangavault.app.ui.account.findActivity
import dev.mangavault.app.ui.components.IconBtn
import dev.mangavault.app.ui.components.ListSheet
import dev.mangavault.app.ui.components.MvIcon
import dev.mangavault.app.ui.components.PrimaryButton
import dev.mangavault.app.ui.components.SheetItem
import dev.mangavault.app.ui.components.shimmer
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.core.model.Chapter
import dev.mangavault.core.store.UserOps
import dev.mangavault.core.util.Format
import dev.mangavault.core.util.ReaderModes
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val BAR = Color(0xF0121216)
private val BAR_FG = Color(0xFFF2F2F5)
private val BAR_FG2 = Color(0xFFA0A0AB)
private val BAR_BUTTON = Color(0x14FFFFFF)

private sealed interface RSheet {
    data object None : RSheet
    data object Chapters : RSheet
    data object Settings : RSheet
    data class PageMenu(val item: ReaderItem.PageItem) : RSheet
}

@Composable
fun ReaderScreen(nav: Navigator, mangaId: Long, chapterId: Long, page: Int) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val vm: ReaderViewModel = viewModel(key = "reader-$mangaId-$chapterId-$page") {
        ReaderViewModel(c, ctx.applicationContext, mangaId, chapterId, page)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by c.prefs.flow.collectAsStateWithLifecycle()
    val data by c.user.flow.collectAsStateWithLifecycle()
    val rp = prefs.reader
    val palette = ReaderPalette.of(rp.background)
    val mode = ReaderModes.resolve(prefs.modes[mangaId], rp.defaultMode, ui.manga?.type)
    val scope = rememberCoroutineScope()

    var controls by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<RSheet>(RSheet.None) }
    val retryKeys = remember { mutableStateMapOf<String, Int>() }
    val items = ui.items
    var current by remember(ui.session) { mutableIntStateOf(ui.indexOf(ui.startChapterId, ui.startPage)) }
    val item = items.getOrNull(current)

    LaunchedEffect(item) { if (item != null) vm.onPosition(item) }

    SystemChrome(controls || sheet != RSheet.None, rp.keepOn, if (rp.dim || rp.brightness >= 100) null else rp.brightness)

    val actions = rememberPageActions()

    // Jump requests from the slider and chapter buttons, carried out by whichever mode is showing.
    var jump by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        when {
            ui.error != null && ui.segments.isEmpty() -> ChapterError(
                ui, palette, onRetry = vm::retry, onBack = { nav.backToDetails(mangaId) },
            )
            ui.loading || items.isEmpty() -> Box(Modifier.fillMaxSize().shimmer(palette.sk, palette.sk2))
            mode == ReaderModes.VERTICAL -> key(ui.session, mode) {
                Webtoon(
                    ui, palette, rp.gap, retryKeys, startIndex = current,
                    controls = { controls }, setControls = { controls = it; if (!it) more = false },
                    onCurrent = { current = it }, jump = jump, onJumped = { jump = null },
                    onLongPress = { sheet = RSheet.PageMenu(it) },
                    onMore = { if (more) { more = false; true } else false },
                    endActions = EndActions(
                        favorite = data.isFavorite(mangaId),
                        onFavorite = { vm.toggleFavorite(); c.toasts.show("Added to Library") },
                        onBack = { nav.backToDetails(mangaId) },
                        onRetryNext = vm::loadNext,
                    ),
                    chapterFor = ui::chapterLabelFor,
                )
            }
            else -> key(ui.session, mode) {
                Paged(
                    ui, palette, mode, rp.fit, rp.invert, retryKeys, startIndex = current,
                    onTapCenter = { controls = !controls; if (!controls) more = false },
                    onCurrent = { current = it }, jump = jump, onJumped = { jump = null },
                    onLongPress = { sheet = RSheet.PageMenu(it) },
                    onMore = { if (more) { more = false; true } else false },
                    endActions = EndActions(
                        favorite = data.isFavorite(mangaId),
                        onFavorite = { vm.toggleFavorite(); c.toasts.show("Added to Library") },
                        onBack = { nav.backToDetails(mangaId) },
                        onRetryNext = vm::loadNext,
                    ),
                    chapterFor = ui::chapterLabelFor,
                )
            }
        }

        // Brightness as a dim layer, as in the prototype; with dim off the window brightness is used.
        if (rp.dim && rp.brightness < 100) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (100 - rp.brightness) / 100f * 0.8f)))
        }

        val seg = item?.seg
        val pageNo = item?.pageNumber ?: 1
        val count = seg?.count ?: 1
        if (!controls && seg != null) {
            Box(Modifier.align(Alignment.TopStart).fillMaxWidth(pageNo / count.toFloat()).height(2.dp).background(Mv.colors.ac))
            if (rp.showNum) {
                Box(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp)
                        .height(22.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x8C000000)).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("$pageNo / $count", color = Color(0xFFE6E6EA), fontSize = 11.sp, fontWeight = SemiBold) }
            }
        }

        AnimatedVisibility(
            visible = controls && ui.error == null,
            enter = fadeIn(tween(150)), exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (seg != null) {
                    ControlsTop(
                        title = ui.manga?.title ?: "",
                        sub = Format.chapterWithTitle(seg.chapter),
                        onBack = nav::back,
                        onChapters = { more = false; sheet = RSheet.Chapters },
                        onMore = { more = !more },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    ControlsBottom(
                        seg = seg, page = pageNo, rtl = mode == ReaderModes.RTL, palette = palette,
                        prev = ui.chapterLabelFor(seg.data.prevChapterId),
                        prevId = seg.data.prevChapterId,
                        next = ui.chapterLabelFor(seg.data.nextChapterId),
                        nextId = seg.data.nextChapterId,
                        onSlide = { p -> jump = ui.indexOf(seg.chapter.id, p) },
                        onPrev = { id -> vm.open(id, 1) },
                        onNext = { id ->
                            val i = items.indexOfFirst { it is ReaderItem.PageItem && it.seg.chapter.id == id }
                            if (i >= 0) jump = i else vm.open(id, 1)
                        },
                        onSettings = { more = false; sheet = RSheet.Settings },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                if (more) {
                    MoreMenu(
                        onShare = {
                            more = false
                            val ch = seg?.chapter
                            PageActions.shareText(ctx, listOfNotNull(ui.manga?.title, ch?.let { Format.chapterWithTitle(it) }).joinToString(" · "))
                        },
                        onReport = {
                            more = false
                            val p = item as? ReaderItem.PageItem
                            if (p != null) {
                                PageActions.evict(ctx, p.page)
                                retryKeys[p.key] = (retryKeys[p.key] ?: 0) + 1
                            }
                            // No report endpoint exists yet, so this only reloads the page.
                            c.toasts.show("Reloading this page. Reporting isn't available yet.")
                        },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
    }

    // Sheets
    when (val s = sheet) {
        RSheet.None -> Unit
        RSheet.Settings -> ReaderSettingsSheet(
            mode = mode, prefs = rp,
            onMode = { m -> c.prefs.update { it.copy(modes = it.modes + (mangaId to m)) } },
            onPrefs = { f -> c.prefs.update { it.copy(reader = f(it.reader)) } },
            onDismiss = { sheet = RSheet.None },
        )
        RSheet.Chapters -> {
            val list = ui.chapters.ifEmpty { ui.segments.map { it.chapter } }.reversed()
            val cur = item?.seg?.chapter?.id
            ListSheet(
                ui.manga?.title ?: "Chapters", { sheet = RSheet.None },
                list.map { ch ->
                    val on = ch.id == cur
                    val read = UserOps.isRead(data, ch)
                    SheetItem(
                        if (on) Icons.Rounded.PlayCircle else if (read) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        Format.chapterLabel(ch),
                        { sheet = RSheet.None; vm.open(ch.id, 1) },
                        sub = Format.chapterSub(ch).ifEmpty { null },
                        highlight = on, bold = on, iconTint = if (on) Mv.colors.ac else Mv.colors.ts,
                    )
                },
                sub = Format.plural(list.size, "chapter"),
                initialIndex = list.indexOfFirst { it.id == cur }.coerceAtLeast(0),
            )
        }
        is RSheet.PageMenu -> PageMenuSheet(
            s.item,
            onSave = { sheet = RSheet.None; actions.save(s.item) },
            onShare = { sheet = RSheet.None; actions.share(s.item, ui.manga?.title) },
            onReload = {
                sheet = RSheet.None
                PageActions.evict(ctx, s.item.page)
                retryKeys[s.item.key] = (retryKeys[s.item.key] ?: 0) + 1
            },
            onDismiss = { sheet = RSheet.None },
        )
    }
}

private class EndActions(
    val favorite: Boolean,
    val onFavorite: () -> Unit,
    val onBack: () -> Unit,
    val onRetryNext: () -> Unit,
)

/** Immersive bars while reading, keep-screen-on, and window brightness. */
@Composable
private fun SystemChrome(showBars: Boolean, keepOn: Boolean, brightness: Int?) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    DisposableEffect(showBars) {
        val ctrl = WindowCompat.getInsetsController(window, view)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showBars) ctrl.show(WindowInsetsCompat.Type.systemBars()) else ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.isAppearanceLightStatusBars = false
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(brightness) {
        window.attributes = window.attributes.apply {
            screenBrightness = brightness?.let { it / 100f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Webtoon

@Composable
private fun Webtoon(
    ui: ReaderUi,
    palette: ReaderPalette,
    gap: Boolean,
    retryKeys: Map<String, Int>,
    startIndex: Int,
    controls: () -> Boolean,
    setControls: (Boolean) -> Unit,
    onCurrent: (Int) -> Unit,
    jump: Int?,
    onJumped: () -> Unit,
    onLongPress: (ReaderItem.PageItem) -> Unit,
    onMore: () -> Boolean,
    endActions: EndActions,
    chapterFor: (Long?) -> Chapter?,
) {
    val items = ui.items
    val currentItems by rememberUpdatedState(items)
    val longPress by rememberUpdatedState(onLongPress)
    val list = remember { LazyListState(firstVisibleItemIndex = startIndex) }
    val zoom = remember {
        // Zoomed in, vertical pans scroll the strip (in its unscaled pixels) and run past its ends.
        ZoomState().apply { scrollList = { dy -> -list.dispatchRawDelta(-dy / scale) * scale } }
    }
    val zoomed by remember { derivedStateOf { zoom.zoomed } }
    val scope = rememberCoroutineScope()
    val hideAfter = with(LocalDensity.current) { 90.dp.toPx() }

    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val line = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) * 0.4f
            info.visibleItemsInfo.lastOrNull { it.offset <= line }?.index
        }.filterNotNull().distinctUntilChanged().collect { onCurrent(it) }
    }
    LaunchedEffect(jump) {
        val j = jump ?: return@LaunchedEffect
        list.scrollToItem(j.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        onJumped()
    }
    // Scrolling 90dp with the controls up hides them, as in the prototype.
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (controls()) {
                    acc += available.y
                    if (abs(acc) > hideAfter) {
                        acc = 0f
                        setControls(false)
                    }
                } else acc = 0f
                return Offset.Zero
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .clipToBounds()
            .zoomable(zoom, panUnzoomed = false)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (!onMore()) setControls(!controls()) },
                    onDoubleTap = { at -> scope.launch { zoom.toggle(at) } },
                    onLongPress = { at ->
                        val hit = list.layoutInfo.visibleItemsInfo.firstOrNull { at.y >= it.offset && at.y < it.offset + it.size }
                        (hit?.let { currentItems.getOrNull(it.index) } as? ReaderItem.PageItem)?.let(longPress)
                    },
                )
            },
    ) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().zoomLayer(zoom).nestedScroll(hideOnScroll),
            userScrollEnabled = !zoomed,
        ) {
            items(items.size, key = { i -> keyOf(items[i]) }, contentType = { i -> items[i]::class }) { i ->
                when (val x = items[i]) {
                    is ReaderItem.PageItem -> PageImage(
                        x.page, x.pageNumber, x.seg.data.imageHeaders, palette, retryKeys[x.key] ?: 0,
                        Modifier.fillMaxWidth().padding(bottom = if (gap) 8.dp else 0.dp),
                        ContentScale.FillWidth, fallbackRatio = 0.6f,
                    )
                    is ReaderItem.Transition -> Column {
                        WebtoonDivider(x, palette, chapterFor(x.nextId), endActions.onRetryNext)
                        if (x.next == null && !x.failed) {
                            LaunchedEffect(x.nextId) { endActions.onRetryNext() }
                            Box(Modifier.fillMaxWidth().height(480.dp).shimmer(palette.sk, palette.sk2))
                        }
                    }
                    is ReaderItem.End -> CaughtUp(x, palette, endActions, Modifier.fillMaxWidth().height(560.dp))
                }
            }
        }
    }
}

private fun keyOf(item: ReaderItem): String = when (item) {
    is ReaderItem.PageItem -> item.key
    is ReaderItem.Transition -> "t:${item.seg.chapter.id}"
    is ReaderItem.End -> "e:${item.seg.chapter.id}"
}

@Composable
private fun WebtoonDivider(t: ReaderItem.Transition, palette: ReaderPalette, next: Chapter?, onRetry: () -> Unit) {
    val nextLabel = next?.let { "Next: " + Format.chapterWithTitle(it) } ?: "Next chapter"
    Box(Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 16.dp, vertical = 24.dp)) {
        Row(
            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(palette.card)
                .then(if (t.failed) Modifier.clickable(onClick = onRetry) else Modifier).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("End of " + Format.chapterLabel(t.seg.chapter), fontSize = 12.sp, lineHeight = 16.sp, color = palette.fg2)
                Text(
                    if (t.failed) "Couldn't load the next chapter · Tap to retry" else nextLabel,
                    fontSize = 15.sp, lineHeight = 20.sp, fontWeight = SemiBold, color = palette.fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            MvIcon(if (t.failed) Icons.Rounded.Refresh else Icons.Rounded.ArrowDownward, 24.dp, Mv.colors.ac)
        }
    }
}

@Composable
private fun CaughtUp(end: ReaderItem.End, palette: ReaderPalette, actions: EndActions, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MvIcon(Icons.Rounded.CheckCircle, 48.dp, Mv.colors.ok)
        Text("You're caught up", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = SemiBold, color = palette.fg)
        Text(
            "${Format.chapterLabel(end.seg.chapter)} is the latest chapter. We'll badge it in your Library when the next one lands.",
            fontSize = 15.sp, lineHeight = 21.sp, color = palette.fg2, textAlign = TextAlign.Center,
        )
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!actions.favorite) PrimaryButton("Add to favorites", actions.onFavorite, Modifier.fillMaxWidth(), height = 48.dp, fontSize = 15)
            PrimaryButton(
                "Back to details", actions.onBack, Modifier.fillMaxWidth(), height = 48.dp, fontSize = 15,
                color = palette.card, textColor = palette.fg,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Paged

@Composable
private fun Paged(
    ui: ReaderUi,
    palette: ReaderPalette,
    mode: String,
    fit: String,
    invert: Boolean,
    retryKeys: Map<String, Int>,
    startIndex: Int,
    onTapCenter: () -> Unit,
    onCurrent: (Int) -> Unit,
    jump: Int?,
    onJumped: () -> Unit,
    onLongPress: (ReaderItem.PageItem) -> Unit,
    onMore: () -> Boolean,
    endActions: EndActions,
    chapterFor: (Long?) -> Chapter?,
) {
    val items = ui.items
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    // Each page zooms on its own; this is whether the page on screen is zoomed in.
    var zoomed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.distinctUntilChanged().collect {
            zoomed = false
            onCurrent(it)
        }
    }
    LaunchedEffect(jump) {
        val j = jump ?: return@LaunchedEffect
        pager.scrollToPage(j.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        onJumped()
    }

    fun onTap(at: Offset, size: IntSize) {
        if (onMore()) return
        if (zoomed || size.width == 0 || size.height == 0) {
            onTapCenter()
            return
        }
        val f = if (mode == ReaderModes.PAGED_VERTICAL) at.y / size.height else at.x / size.width
        val zone = when {
            f < 0.3f -> -1
            f > 0.7f -> 1
            else -> 0
        }
        if (zone == 0) {
            onTapCenter()
            return
        }
        // In right-to-left, the left edge moves forward.
        var dir = if (mode == ReaderModes.RTL) -zone else zone
        if (invert) dir = -dir
        val target = (pager.currentPage + dir).coerceIn(0, items.size - 1)
        if (target != pager.currentPage) scope.launch { pager.animateScrollToPage(target) }
    }

    val content: @Composable (Int) -> Unit = { i ->
        when (val x = items[i]) {
            is ReaderItem.PageItem -> PagedPage(
                x.page, x.pageNumber, x.seg.data.imageHeaders, palette, retryKeys[x.key] ?: 0, fit,
                active = i == pager.currentPage, onZoomed = { if (i == pager.currentPage) zoomed = it },
                onTap = ::onTap, onLongPress = { onLongPress(x) },
            )
            is ReaderItem.Transition -> TapSurface(::onTap) {
                if (x.next == null && !x.failed) LaunchedEffect(x.nextId) { endActions.onRetryNext() }
                TransitionPage(x, palette, mode, chapterFor(x.nextId), endActions.onRetryNext)
            }
            is ReaderItem.End -> TapSurface(::onTap) { CaughtUp(x, palette, endActions, Modifier.fillMaxSize()) }
        }
    }

    // The pager runs left-to-right internally; RTL reverses the layout so page 2 sits to the left.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (mode == ReaderModes.PAGED_VERTICAL) {
            VerticalPager(
                state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                userScrollEnabled = !zoomed, key = { keyOf(items[it]) },
            ) { content(it) }
        } else {
            HorizontalPager(
                state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1,
                reverseLayout = mode == ReaderModes.RTL, userScrollEnabled = !zoomed, key = { keyOf(items[it]) },
            ) { content(it) }
        }
    }
}

@Composable
private fun TapSurface(onTap: (Offset, IntSize) -> Unit, content: @Composable () -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectTapGestures(onTap = { tap(it, size) }) },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TransitionPage(t: ReaderItem.Transition, palette: ReaderPalette, mode: String, next: Chapter?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("FINISHED", fontSize = 12.sp, letterSpacing = 1.sp, color = palette.fg2)
            Text(Format.chapterWithTitle(t.seg.chapter), fontSize = 20.sp, lineHeight = 26.sp, fontWeight = SemiBold, color = palette.fg)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.card))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("NEXT", fontSize = 12.sp, letterSpacing = 1.sp, color = Mv.colors.ac)
            Text(next?.let { Format.chapterWithTitle(it) } ?: "Next chapter", fontSize = 20.sp, lineHeight = 26.sp, fontWeight = SemiBold, color = palette.fg)
            val (dot, label) = when {
                t.next != null -> Mv.colors.ok to "First pages ready"
                t.failed -> Mv.colors.ac to "Couldn't load it · Tap here to retry"
                else -> Mv.colors.wn to "Loading first pages…"
            }
            Row(
                Modifier.padding(top = 4.dp).then(if (t.failed) Modifier.clickable(onClick = onRetry) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Text(label, fontSize = 13.sp, color = palette.fg2)
            }
        }
        Text(
            when (mode) {
                ReaderModes.RTL -> "Tap the left edge or swipe to continue"
                ReaderModes.LTR -> "Tap the right edge or swipe to continue"
                else -> "Swipe up to continue"
            },
            fontSize = 13.sp, color = palette.fg2, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Error, controls and menus

@Composable
private fun ChapterError(ui: ReaderUi, palette: ReaderPalette, onRetry: () -> Unit, onBack: () -> Unit) {
    val e = ui.error
    val ch = ui.chapterLabelFor(ui.startChapterId)
    val body = when {
        e?.isNetwork == true -> "You're offline and this chapter isn't saved on this phone yet. Your place is saved."
        e?.isUpstream == true -> "None of the sites that carry this chapter answered, and it isn't cached yet. Your place is saved."
        else -> "Something went wrong loading this chapter. Your place is saved."
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MvIcon(Icons.Rounded.CloudOff, 44.dp, palette.fg.copy(alpha = 0.7f))
        Text(
            "Couldn't load " + (ch?.let { Format.chapterLabel(it) } ?: "this chapter"),
            fontSize = 20.sp, lineHeight = 26.sp, fontWeight = SemiBold, color = palette.fg, textAlign = TextAlign.Center,
        )
        Text(body, fontSize = 15.sp, lineHeight = 21.sp, color = palette.fg2, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Retry", onRetry, Modifier.fillMaxWidth(), height = 48.dp, fontSize = 15)
            PrimaryButton("Back to details", onBack, Modifier.fillMaxWidth(), height = 48.dp, fontSize = 15, color = palette.card, textColor = palette.fg)
        }
    }
}

@Composable
private fun ControlsTop(title: String, sub: String, onBack: () -> Unit, onChapters: () -> Unit, onMore: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().background(BAR).statusBarsPadding().height(52.dp).padding(horizontal = 4.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, onBack, tint = BAR_FG, iconSize = 26.dp, contentDescription = "Back")
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = SemiBold, color = BAR_FG, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.sp, lineHeight = 16.sp, color = BAR_FG2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(Icons.AutoMirrored.Rounded.FormatListBulleted, onChapters, tint = BAR_FG, contentDescription = "Chapters")
        IconBtn(Icons.Rounded.MoreVert, onMore, tint = BAR_FG, contentDescription = "More")
    }
}

@Composable
private fun ControlsBottom(
    seg: Segment,
    page: Int,
    rtl: Boolean,
    palette: ReaderPalette,
    prev: Chapter?,
    prevId: Long?,
    next: Chapter?,
    nextId: Long?,
    onSlide: (Int) -> Unit,
    onPrev: (Long) -> Unit,
    onNext: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier,
) {
    val count = seg.count.coerceAtLeast(1)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(page.toFloat()) }
    val shown = if (dragging) dragValue.roundToInt() else page
    Column(
        modifier.fillMaxWidth().background(BAR).pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$shown / $count", fontSize = 13.sp, fontWeight = SemiBold, color = BAR_FG, modifier = Modifier.widthIn(min = 52.dp))
            BoxWithConstraints(Modifier.weight(1f)) {
                val w = maxWidth
                if (dragging && count > 1) {
                    val frac = (dragValue - 1f) / (count - 1)
                    val x = (if (rtl) 1f - frac else frac) * (w - 20.dp).value + 10f
                    val thumbPage = seg.data.pages.getOrNull(dragValue.roundToInt() - 1)
                    Column(
                        Modifier.offset(x = (x - 32f).dp, y = (-118).dp).width(64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier.width(64.dp).height(96.dp).shadow(6.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
                                .background(palette.sk),
                        ) {
                            if (thumbPage != null) {
                                PageImage(
                                    thumbPage, dragValue.roundToInt(), seg.data.imageHeaders, palette, 0,
                                    Modifier.fillMaxSize(), ContentScale.Crop, sizeToRatio = false,
                                )
                            }
                        }
                        Text(
                            "${dragValue.roundToInt()}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Slider(
                        value = if (dragging) dragValue else page.toFloat(),
                        onValueChange = { v ->
                            dragging = true
                            dragValue = v
                            onSlide(v.roundToInt())
                        },
                        onValueChangeFinished = { dragging = false },
                        valueRange = 1f..count.toFloat().coerceAtLeast(1.001f),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Mv.colors.ac, activeTrackColor = Mv.colors.ac, inactiveTrackColor = Color(0x33FFFFFF),
                        ),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChapterButton(
                prev?.let { Format.chapterLabel(it) } ?: if (prevId != null) "Previous" else "First",
                Icons.Rounded.SkipPrevious, leading = true, enabled = prevId != null, modifier = Modifier.weight(1f),
            ) { prevId?.let(onPrev) }
            Box(
                Modifier.width(56.dp).height(44.dp).clip(RoundedCornerShape(12.dp)).background(BAR_BUTTON).clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { MvIcon(Icons.Rounded.Tune, 22.dp, BAR_FG) }
            ChapterButton(
                next?.let { Format.chapterLabel(it) } ?: if (nextId != null) "Next" else "Latest",
                Icons.Rounded.SkipNext, leading = false, enabled = nextId != null, modifier = Modifier.weight(1f),
            ) { nextId?.let(onNext) }
        }
    }
}

@Composable
private fun ChapterButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    leading: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(BAR_BUTTON)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = BAR_FG.copy(alpha = if (enabled) 1f else 0.4f)
        if (leading) MvIcon(icon, 20.dp, tint)
        Text(label, fontSize = 13.sp, fontWeight = SemiBold, color = tint, maxLines = 1)
        if (!leading) MvIcon(icon, 20.dp, tint)
    }
}

@Composable
private fun MoreMenu(onShare: () -> Unit, onReport: () -> Unit, modifier: Modifier) {
    Column(
        modifier.statusBarsPadding().padding(top = 50.dp, end = 10.dp).width(220.dp)
            .shadow(16.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Color(0xFF26262E)),
    ) {
        listOf(
            Triple(Icons.Rounded.Share, "Share chapter", onShare),
            Triple(Icons.Rounded.Flag, "Report broken page", onReport),
        ).forEachIndexed { i, (icon, label, action) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x14FFFFFF)))
            Row(
                Modifier.fillMaxWidth().height(46.dp).clickable(onClick = action).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MvIcon(icon, 20.dp, BAR_FG)
                Text(label, fontSize = 15.sp, color = BAR_FG)
            }
        }
    }
}

/** Save and share run in the app scope so closing the sheet doesn't cancel them. */
private class PageActionRunner(
    private val onSave: (ReaderItem.PageItem) -> Unit,
    private val onShare: (ReaderItem.PageItem, String?) -> Unit,
) {
    fun save(item: ReaderItem.PageItem) = onSave(item)
    fun share(item: ReaderItem.PageItem, title: String?) = onShare(item, title)
}

private fun fileName(item: ReaderItem.PageItem) = "mangavault-${item.seg.chapter.mangaId}-${item.seg.chapter.id}-${item.pageNumber}"

@Composable
private fun rememberPageActions(): PageActionRunner {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<ReaderItem.PageItem?>(null) }
    fun doSave(item: ReaderItem.PageItem) {
        c.scope.launch {
            try {
                val bytes = PageActions.bytes(ctx, c.http, item.page, item.seg.data.imageHeaders)
                PageActions.save(ctx, bytes, fileName(item))
                c.toasts.show("Saved to Pictures")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                c.toasts.show("Couldn't save this page")
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = pending
        pending = null
        if (item != null) {
            if (granted) doSave(item) else c.toasts.show("Storage permission is needed to save pages")
        }
    }
    return PageActionRunner(
        onSave = { item ->
            val needs = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            if (needs) {
                pending = item
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else doSave(item)
        },
        onShare = { item, title ->
            c.scope.launch {
                try {
                    val bytes = PageActions.bytes(ctx, c.http, item.page, item.seg.data.imageHeaders)
                    val text = listOfNotNull(title, Format.chapterLabel(item.seg.chapter), "page ${item.pageNumber}").joinToString(" · ")
                    PageActions.share(ctx, bytes, fileName(item), text)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    c.toasts.show("Couldn't share this page")
                }
            }
        },
    )
}

@Composable
private fun PageMenuSheet(
    item: ReaderItem.PageItem,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListSheet(
        "Page ${item.pageNumber}", onDismiss,
        listOf(
            SheetItem(Icons.Rounded.Download, "Save image", onSave),
            SheetItem(Icons.Rounded.Share, "Share page", onShare),
            SheetItem(Icons.Rounded.Refresh, "Reload page", onReload),
        ),
    )
}
