package dev.mangavault.app.ui.reader

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.SingletonImageLoader
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.mangavault.app.ui.components.headersOf
import dev.mangavault.core.model.Page
import dev.mangavault.core.util.PanelDetector
import dev.mangavault.core.util.PanelRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Guided view for one page: its panels, in reading order and ending with the whole page, and
 * which one is on screen. [index] is -1 until the reader arrives on the page.
 */
@Stable
class PanelGuide(private val zoom: ZoomState) {
    var panels by mutableStateOf<List<PanelRect>?>(null)
    var index by mutableIntStateOf(-1)

    /** Moves one panel forward or back. False when there's no panel that way, so the page turns. */
    suspend fun step(dir: Int): Boolean {
        val p = panels ?: return false
        val to = GuideSteps.next(index, p.size, dir) ?: return false
        index = to
        zoom.show(p[to])
        return true
    }

    /** Arriving going forward starts at the first panel; going back, at the whole page. */
    suspend fun enter(forward: Boolean) {
        val p = panels ?: return
        index = if (forward) 0 else p.lastIndex
        zoom.show(p[index])
    }

    suspend fun reframe() {
        val p = panels ?: return
        p.getOrNull(index)?.let { zoom.show(it) }
    }

    fun leave() {
        index = -1
    }
}

object GuideSteps {
    /** The panel after [index] in direction [dir], or null to turn the page. -1 means not started. */
    fun next(index: Int, count: Int, dir: Int): Int? {
        if (count == 0) return null
        val to = if (index < 0) (if (dir > 0) 0 else count - 1) else index + dir
        return to.takeIf { it in 0 until count }
    }
}

/** Panels per page, found once from a small copy of the image Coil already has on disk. */
object PanelCache {
    private val cache = LruCache<String, List<PanelRect>>(200)

    suspend fun panels(ctx: Context, page: Page, headers: Map<String, String>, rtl: Boolean): List<PanelRect>? {
        val key = "$rtl|${page.url}"
        cache.get(key)?.let { return it }
        val req = ImageRequest.Builder(ctx)
            .data(page.url)
            .httpHeaders(headersOf(headers))
            .size(PanelDetector.ANALYSIS_SIZE)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = SingletonImageLoader.get(ctx).execute(req) as? SuccessResult ?: return null
        val panels = withContext(Dispatchers.Default) {
            val bmp = result.image.toBitmap()
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            PanelDetector.detect(px, w, h, rtl)
        }
        cache.put(key, panels)
        return panels
    }
}
