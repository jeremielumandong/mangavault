package dev.mangavault.app.ui.reader

import dev.mangavault.core.model.Chapter
import dev.mangavault.core.model.ChapterPages
import dev.mangavault.core.model.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderItemsTest {
    private fun seg(id: Long, pages: Int, prev: Long?, next: Long?) = Segment(
        ChapterPages(
            chapter = Chapter(id = id, mangaId = 1, number = id.toDouble(), index = id.toInt()),
            pages = (0 until pages).map { Page(it, "https://img.example.com/$id/$it.jpg") },
            prevChapterId = prev,
            nextChapterId = next,
        ),
    )

    @Test
    fun transitionBetweenChaptersAndEndAfterTheLast() {
        val ui = ReaderUi(segments = listOf(seg(1, 3, null, 2), seg(2, 2, 1, null)), loading = false)
        val kinds = ui.items.map { it::class.simpleName }
        assertEquals(listOf("PageItem", "PageItem", "PageItem", "Transition", "PageItem", "PageItem", "End"), kinds)
        val t = ui.items[3] as ReaderItem.Transition
        assertEquals(2L, t.next?.chapter?.id)
        assertEquals(3, t.pageNumber)
    }

    @Test
    fun transitionWaitsForANextChapterThatIsNotLoaded() {
        val ui = ReaderUi(segments = listOf(seg(5, 2, 4, 6)), loading = false, nextFailed = 6)
        val t = ui.items.last() as ReaderItem.Transition
        assertEquals(null, t.next)
        assertTrue(t.failed)
    }

    @Test
    fun indexOfFindsThePageOrFallsBackToTheChapterStart() {
        val ui = ReaderUi(segments = listOf(seg(1, 3, null, 2), seg(2, 4, 1, null)), loading = false)
        assertEquals(5, ui.indexOf(2, 2))
        assertEquals(4, ui.indexOf(2, 99))
        assertEquals(0, ui.indexOf(42, 1))
    }
}
