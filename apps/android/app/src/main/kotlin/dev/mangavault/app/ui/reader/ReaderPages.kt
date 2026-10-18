package dev.mangavault.app.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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

/**
 * Pinch, pan and double-tap zoom. Single-finger drags pass through to the list or pager
 * underneath unless the content is zoomed (then a drag pans it).
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    val zoomed get() = scale > 1.01f

    fun clamp() {
        val maxX = (size.width * (scale - 1f)) / 2f
        val maxY = (size.height * (scale - 1f)) / 2f
        offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** Toggles 2.2× around [at] (in layout pixels). */
    suspend fun toggle(at: Offset) {
        val anim = Animatable(0f)
        val fromScale = scale
        val fromOffset = offset
        val toScale = if (zoomed) 1f else 2.2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val toOffset = if (toScale == 1f) Offset.Zero else (center - at) * (toScale - 1f)
        anim.animateTo(1f, tween(220)) {
            scale = fromScale + (toScale - fromScale) * value
            offset = fromOffset + (toOffset - fromOffset) * value
            clamp()
        }
    }
}

/**
 * Pinch-zoom gesture. With [panWhenZoomed], a single finger pans once zoomed in and is consumed,
 * so a pager doesn't flip pages while the reader looks around a page.
 */
fun Modifier.pinchZoom(state: ZoomState, panWhenZoomed: Boolean): Modifier = this
    .onSizeChanged { state.size = it }
    .pointerInput(state, panWhenZoomed) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid()
                    val newScale = (state.scale * zoom).coerceIn(1f, 5f)
                    val center = Offset(state.size.width / 2f, state.size.height / 2f)
                    // Keep the point under the fingers steady while scaling.
                    val k = newScale / state.scale
                    state.offset = (state.offset - (centroid - center)) * k + (centroid - center) + pan
                    state.scale = newScale
                    state.clamp()
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } else if (pressed == 1 && state.zoomed) {
                    val ch = event.changes.first { it.pressed }
                    val delta = ch.position - ch.previousPosition
                    if (panWhenZoomed) {
                        state.offset += delta
                        state.clamp()
                        if (ch.positionChanged()) ch.consume()
                    } else {
                        // Vertical lists keep scrolling; only follow sideways movement.
                        state.offset = Offset(state.offset.x + delta.x, state.offset.y)
                        state.clamp()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

fun Modifier.zoomLayer(state: ZoomState): Modifier = this.graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
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

/** A paged-mode page: fit modes, pinch and double-tap zoom, tap zones handled by [onTap]. */
@Composable
fun PagedPage(
    page: Page,
    number: Int,
    headers: Map<String, String>,
    palette: ReaderPalette,
    retryKey: Int,
    fit: String,
    zoom: ZoomState,
    onTap: (Offset, IntSize) -> Unit,
    onLongPress: () -> Unit,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pinchZoom(zoom, panWhenZoomed = true)
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
        Box(Modifier.fillMaxSize().zoomLayer(zoom), contentAlignment = Alignment.Center) {
            when (fit) {
                "Height" -> PageImage(page, number, headers, palette, retryKey, Modifier.fillMaxHeight(), ContentScale.FillHeight, 0.68f)
                "Screen" -> PageImage(page, number, headers, palette, retryKey, Modifier.fillMaxSize(), ContentScale.Fit, sizeToRatio = false)
                "Original" -> PageImage(page, number, headers, palette, retryKey, Modifier.fillMaxWidth(0.82f).wrapContentSize(), ContentScale.Fit, 0.68f)
                // Width: tall pages scroll inside the page.
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val minH = maxHeight
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Box(Modifier.fillMaxWidth().heightIn(min = minH), contentAlignment = Alignment.Center) {
                            PageImage(page, number, headers, palette, retryKey, Modifier.fillMaxWidth(), ContentScale.FillWidth, 0.68f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderCard(palette: ReaderPalette, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(12.dp)).background(palette.card).padding(horizontal = 16.dp)) { content() }
}
