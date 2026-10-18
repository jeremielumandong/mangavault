package dev.mangavault.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import dev.mangavault.app.ui.components.MvIcon
import dev.mangavault.app.ui.components.PillButton
import dev.mangavault.app.ui.components.shimmer
import dev.mangavault.app.ui.theme.Mv
import dev.mangavault.app.ui.theme.SemiBold
import dev.mangavault.core.model.Page
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Reader palette for the chosen background, matching the prototype. */
data class ReaderPalette(val bg: Color, val fg: Color, val fg2: Color, val card: Color, val sk: Color, val sk2: Color) {
    companion object {
        fun of(background: String): ReaderPalette {
            val bg = dev.mangavault.app.ui.settings.READER_BACKGROUNDS[background] ?: Color.Black
            return when (background) {
                "White" -> ReaderPalette(bg, Color(0xFF1A1A1E), Color(0xFF5C5C66), Color(0x0F000000), Color(0xFFECECEF), Color(0xFFF7F7F9))
                "Sepia" -> ReaderPalette(bg, Color(0xFF1A1A1E), Color(0xFF5C5C66), Color(0x0F000000), Color(0xFFE6DAC1), Color(0xFFF1E6CF))
                else -> ReaderPalette(bg, Color(0xFFE6E6EA), Color(0xFF8E8E98), Color(0x12FFFFFF), Color(0xFF141417), Color(0xFF22222A))
            }
        }
    }
}

/** One page image with its loading shimmer and retry state. */
@Composable
fun PageImage(
    page: Page,
    number: Int,
    headers: Map<String, String>,
    palette: ReaderPalette,
    retryKey: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    /** Width/height before the image is known; webtoon strips are tall. */
    fallbackRatio: Float = 0.68f,
    sizeToRatio: Boolean = true,
) {
    val ctx = LocalContext.current
    var localRetry by remember(page.url) { mutableIntStateOf(0) }
    val req = remember(page.url, retryKey, localRetry) { pageRequest(ctx, page, headers, retryKey * 1000 + localRetry) }
    var state by remember(page.url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var decodedRatio by remember(page.url) { mutableFloatStateOf(0f) }
    val w = page.width
    val h = page.height
    val known = if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else 0f
    val ratio = when {
        known > 0f -> known
        decodedRatio > 0f -> decodedRatio
        else -> fallbackRatio
    }
    Box(if (sizeToRatio) modifier.aspectRatio(ratio.coerceIn(0.05f, 4f)) else modifier) {
        AsyncImage(
            model = req,
            contentDescription = "Page $number",
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onState = { s ->
                state = s
                if (s is AsyncImagePainter.State.Success) {
                    val img = s.result.image
                    if (img.width > 0 && img.height > 0) decodedRatio = img.width.toFloat() / img.height
                }
            },
        )
        when (state) {
            is AsyncImagePainter.State.Loading, AsyncImagePainter.State.Empty ->
                Box(Modifier.matchParentSize().shimmer(palette.sk, palette.sk2))
            is AsyncImagePainter.State.Error -> Column(
                Modifier.matchParentSize().background(palette.sk).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MvIcon(Icons.Rounded.BrokenImage, 36.dp, palette.fg.copy(alpha = 0.7f))
                Text("Page $number didn't load", color = palette.fg, fontSize = 15.sp, fontWeight = SemiBold)
                PillButton(
                    "Tap to retry", { localRetry++ }, height = 44.dp, horizontal = 22.dp,
                    background = Mv.colors.ac, textColor = Color.White, fontSize = 15, weight = SemiBold,
                )
            }
            else -> Unit
        }
    }
}

/**
 * A paged-mode page: fit modes, pinch, pan, fling and double-tap zoom, tap zones handled by [onTap].
 * Each page keeps its own zoom; [active] is the page on screen, and [onZoomed] tells the pager
 * when it is zoomed in so swipes pan instead of turning pages.
 */
@Composable
fun PagedPage(
    page: Page,
    number: Int,
    headers: Map<String, String>,
    palette: ReaderPalette,
    retryKey: Int,
    fit: String,
    active: Boolean,
    onZoomed: (Boolean) -> Unit,
    onTap: (Offset, IntSize) -> Unit,
    onLongPress: () -> Unit,
    /** Guided view: step through the page's panels; the page is shown whole (Screen fit). */
    guided: Boolean = false,
    rtl: Boolean = false,
    /** Whether the reader arrived here going forward, read when the page becomes [active]. */
    arrivedForward: () -> Boolean = { true },
    onGuide: (PanelGuide?) -> Unit = {},
    onSwipe: (Offset) -> Unit = {},
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val reportZoom by rememberUpdatedState(onZoomed)
    val forward by rememberUpdatedState(arrivedForward)
    val swipe by rememberUpdatedState(onSwipe)
    val guidedSwipe = remember { { d: Offset -> swipe(d) } }
    val zoom = remember(page.url, fit) { ZoomState() }
    val zoomed by remember(zoom) { derivedStateOf { zoom.zoomed } }
    val guide = remember(zoom) { PanelGuide(zoom) }
    val ctx = LocalContext.current
    LaunchedEffect(zoom, active) {
        if (!active) {
            guide.leave()
            zoom.reset()
        }
    }
    LaunchedEffect(zoomed, active) { if (active) reportZoom(zoomed) }
    DisposableEffect(guide, guided) {
        onGuide(if (guided) guide else null)
        onDispose { onGuide(null) }
    }
    LaunchedEffect(page.url, guided, rtl, retryKey) {
        guide.panels = if (guided) PanelCache.panels(ctx, page, headers, rtl) else null
    }
    val panels = guide.panels
    LaunchedEffect(active, panels) {
        if (active && guided && panels != null && guide.index < 0) guide.enter(forward())
    }
    // The page's real size arrives with the image: frame the current panel again.
    val contentSize = zoom.content
    LaunchedEffect(contentSize) {
        if (active && guided) guide.reframe()
    }
    Box(
        Modifier.fillMaxSize()
            .clipToBounds()
            .onSizeChanged { box = it }
            .zoomable(zoom, panUnzoomed = true, onSwipe = if (guided) guidedSwipe else null)
            .pointerInput(page.url) {
                coroutineScope {
                    detectTapGestures(
                        onTap = { tap(it, box) },
                        onDoubleTap = { at -> launch { zoom.toggle(at) } },
                        onLongPress = { longPress() },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The page is laid out at its fitted size (taller or wider than the screen is fine), centred,
        // and the zoom layer scales it around its own centre.
        val content = Modifier.onSizeChanged { zoom.content = it }.zoomLayer(zoom)
        when (fit) {
            "Height" -> Box(Modifier.fillMaxHeight().wrapContentWidth(unbounded = true)) {
                PageImage(page, number, headers, palette, retryKey, content.fillMaxHeight(), ContentScale.FillHeight, 0.68f)
            }
            "Screen" -> PageImage(page, number, headers, palette, retryKey, content, ContentScale.Fit, 0.68f)
            "Original" -> Box(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true), contentAlignment = Alignment.Center) {
                PageImage(page, number, headers, palette, retryKey, content.fillMaxWidth(0.82f), ContentScale.Fit, 0.68f)
            }
            // Width: a tall page pans up and down at 1×, starting at the top.
            else -> Box(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true)) {
                PageImage(page, number, headers, palette, retryKey, content.fillMaxWidth(), ContentScale.FillWidth, 0.68f)
            }
        }
    }
}

@Composable
fun ReaderCard(palette: ReaderPalette, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(12.dp)).background(palette.card).padding(horizontal = 16.dp)) { content() }
}
