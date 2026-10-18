package dev.mangavault.app.ui.reader

import dev.mangavault.core.api.ApiException
import dev.mangavault.core.model.Chapter
import dev.mangavault.core.model.ChapterPages
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.model.Page

// Pure reader state, kept free of Android types so it is unit-tested on the JVM.

/** One loaded chapter in the reading strip. */
data class Segment(val data: ChapterPages) {
    val chapter: Chapter get() = data.chapter
    val count: Int get() = data.pages.size
}

/** A flattened reader item: pages, then a transition (or the end) after each chapter. */
sealed interface ReaderItem {
    val seg: Segment
    /** 1-based page this item counts as for progress. */
    val pageNumber: Int

    data class PageItem(override val seg: Segment, val page: Page, override val pageNumber: Int) : ReaderItem {
        val key get() = "p:${seg.chapter.id}:${page.index}"
    }

    /** Between [seg] and the next chapter; [next] is null while it loads or if it failed. */
    data class Transition(override val seg: Segment, val nextId: Long, val next: Segment?, val failed: Boolean) : ReaderItem {
        override val pageNumber get() = seg.count
    }

    data class End(override val seg: Segment) : ReaderItem {
        override val pageNumber get() = seg.count
    }
}

data class ReaderUi(
    val manga: MangaSummary? = null,
    val chapters: List<Chapter> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val loading: Boolean = true,
    /** Set when the first chapter could not load. */
    val error: ApiException? = null,
    val nextLoading: Long? = null,
    val nextFailed: Long? = null,
    /** Bumped on every open(), so the screen resets its scroll and pager state. */
    val session: Int = 0,
    val startPage: Int = 1,
    val startChapterId: Long = 0,
) {
    val items: List<ReaderItem> by lazy {
        buildList {
            segments.forEachIndexed { i, s ->
                s.data.pages.forEachIndexed { p, page -> add(ReaderItem.PageItem(s, page, p + 1)) }
                val nextId = s.data.nextChapterId
                if (nextId == null) add(ReaderItem.End(s))
                else add(ReaderItem.Transition(s, nextId, segments.getOrNull(i + 1), nextFailed == nextId))
            }
        }
    }

    fun indexOf(chapterId: Long, page: Int): Int {
        val i = items.indexOfFirst { it is ReaderItem.PageItem && it.seg.chapter.id == chapterId && it.pageNumber == page }
        if (i >= 0) return i
        return items.indexOfFirst { it is ReaderItem.PageItem && it.seg.chapter.id == chapterId }.coerceAtLeast(0)
    }

    fun chapterLabelFor(id: Long?): Chapter? = id?.let { cid -> chapters.firstOrNull { it.id == cid } ?: segments.firstOrNull { it.chapter.id == cid }?.chapter }
}
