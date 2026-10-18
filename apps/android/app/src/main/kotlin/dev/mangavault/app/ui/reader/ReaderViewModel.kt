package dev.mangavault.app.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.SingletonImageLoader
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Size
import dev.mangavault.app.AppContainer
import dev.mangavault.app.ui.components.headersOf
import dev.mangavault.core.api.ApiException
import dev.mangavault.core.model.Chapter
import dev.mangavault.core.model.ChapterPages
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.model.Page
import dev.mangavault.core.store.UserOps
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The tall-page cap: Coil's default of 4096 would blur webtoon strips. 16384 is the common GPU texture limit. */
val PAGE_MAX_BITMAP = Size(4096, 16384)

fun pageRequest(ctx: Context, page: Page, headers: Map<String, String>, retry: Int = 0): ImageRequest =
    ImageRequest.Builder(ctx)
        .data(page.url)
        .httpHeaders(headersOf(headers))
        .maxBitmapSize(PAGE_MAX_BITMAP)
        .memoryCacheKeyExtra("retry", retry.toString())
        .build()

class ReaderViewModel(
    private val c: AppContainer,
    private val app: Context,
    private val mangaId: Long,
    chapterId: Long,
    page: Int,
) : ViewModel() {
    private val _ui = MutableStateFlow(ReaderUi(manga = c.summary(mangaId), startPage = page, startChapterId = chapterId))
    val ui = _ui.asStateFlow()

    private var openJob: Job? = null
    private var progressJob: Job? = null
    private var pendingSave: (() -> Unit)? = null
    private val prefetched = HashSet<String>()
    private var startChapterId = chapterId

    init {
        open(chapterId, page)
        viewModelScope.launch {
            if (_ui.value.manga == null) runCatching { c.api.manga(mangaId).summary() }.onSuccess { m -> _ui.update { it.copy(manga = m) } }
        }
        viewModelScope.launch {
            runCatching { c.api.chapters(mangaId) }.onSuccess { list -> _ui.update { it.copy(chapters = list.sortedBy { ch -> ch.index }) } }
        }
    }

    fun open(chapterId: Long, page: Int = 1) {
        startChapterId = chapterId
        openJob?.cancel()
        _ui.update { it.copy(loading = true, error = null, segments = emptyList(), nextLoading = null, nextFailed = null, startPage = page, startChapterId = chapterId) }
        openJob = viewModelScope.launch {
            try {
                val first = c.api.pages(chapterId)
                _ui.update { it.copy(loading = false, segments = listOf(Segment(first)), session = it.session + 1) }
                prefetchFrom(first, page)
            } catch (e: ApiException) {
                _ui.update { it.copy(loading = false, error = e) }
            }
        }
    }

    fun retry() = open(startChapterId, _ui.value.startPage)

    /** Appends the chapter after the last loaded one. */
    fun loadNext() {
        val ui = _ui.value
        val last = ui.segments.lastOrNull() ?: return
        val nextId = last.data.nextChapterId ?: return
        if (ui.nextLoading == nextId || ui.segments.any { it.chapter.id == nextId }) return
        _ui.update { it.copy(nextLoading = nextId, nextFailed = null) }
        viewModelScope.launch {
            try {
                val next = c.api.pages(nextId)
                _ui.update { s ->
                    if (s.segments.lastOrNull()?.chapter?.id != last.chapter.id) s
                    else s.copy(segments = s.segments + Segment(next), nextLoading = null)
                }
                prefetchFrom(next, 1)
            } catch (e: ApiException) {
                _ui.update { it.copy(nextLoading = null, nextFailed = nextId) }
            }
        }
    }

    private fun prefetchCount(): Int {
        val prefs = c.prefs.value
        val metered = c.network.metered.value
        return if (prefs.dataSaver && metered) minOf(prefs.prefetch, 2) else prefs.prefetch
    }

    private fun prefetchFrom(cp: ChapterPages, page: Int) {
        val n = prefetchCount()
        cp.pages.drop((page - 1).coerceAtLeast(0)).take(n + 1).forEach { enqueue(it, cp.imageHeaders) }
    }

    private fun enqueue(page: Page, headers: Map<String, String>) {
        if (!prefetched.add(page.url)) return
        // Small decode: the point is to land the bytes in the disk cache before the page is shown.
        val req = ImageRequest.Builder(app).data(page.url).httpHeaders(headersOf(headers))
            .size(Size(256, 256)).memoryCachePolicy(CachePolicy.DISABLED).build()
        SingletonImageLoader.get(app).enqueue(req)
    }

    /** Called when the item under the reading line changes. */
    fun onPosition(item: ReaderItem) {
        val ui = _ui.value
        val seg = item.seg
        val n = prefetchCount()
        // Prefetch ahead within this chapter, and the next chapter as the end nears.
        val idx = ui.items.indexOf(item)
        if (idx >= 0) {
            ui.items.drop(idx + 1).asSequence().filterIsInstance<ReaderItem.PageItem>().take(n)
                .forEach { enqueue(it.page, it.seg.data.imageHeaders) }
        }
        if (seg == ui.segments.lastOrNull() && item.pageNumber >= seg.count - maxOf(3, n)) loadNext()

        val completed = item !is ReaderItem.PageItem || item.pageNumber >= seg.count
        val save = {
            val m = _ui.value.manga ?: c.summary(mangaId)
            if (m != null) {
                c.user.update { UserOps.recordProgress(it, m, seg.chapter, item.pageNumber, seg.count, completed) }
                c.requestSync(4000)
            }
        }
        pendingSave = save
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(500)
            pendingSave = null
            save()
        }
    }

    fun toggleFavorite() {
        val m = _ui.value.manga ?: return
        c.user.update { if (it.isFavorite(m.id)) UserOps.removeFavorite(it, m.id) else UserOps.setFavorite(it, m, null) }
        c.requestSync()
    }

    override fun onCleared() {
        // Save the last position right away rather than losing it to the debounce.
        pendingSave?.invoke()
        pendingSave = null
        super.onCleared()
    }
}
