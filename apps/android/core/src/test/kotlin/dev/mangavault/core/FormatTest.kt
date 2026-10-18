package dev.mangavault.core

import dev.mangavault.core.model.Chapter
import dev.mangavault.core.util.Format
import dev.mangavault.core.util.ReaderModes
import dev.mangavault.core.util.jumpBuckets
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class FormatTest {
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 15)

    @Test
    fun numbers() {
        assertEquals("112", Format.number(112.0))
        assertEquals("112.5", Format.number(112.5))
        assertEquals("Ch. 3", Format.chapter(3.0))
    }

    @Test
    fun chapterSubtitle() {
        val c = Chapter(1, 1, 112.0, "The Ash Garden", "12", "2026-10-10T00:00:00Z", 111)
        assertEquals("Vol. 12 · The Ash Garden · Oct 10", Format.chapterSub(c, utc, today))
        assertEquals("Oct 10, 2024", Format.date("2024-10-10T00:00:00Z", utc, today))
    }

    @Test
    fun historyGroups() {
        assertEquals("Today", Format.historyGroup("2026-10-15T23:48:00Z", utc, today))
        assertEquals("11:48 PM", Format.historyTime("2026-10-15T23:48:00Z", utc, today))
        assertEquals("Yesterday", Format.historyGroup("2026-10-14T10:00:00Z", utc, today))
        assertEquals("This week", Format.historyGroup("2026-10-11T10:00:00Z", utc, today))
        assertEquals("Sunday", Format.historyTime("2026-10-11T10:00:00Z", utc, today))
        assertEquals("Earlier", Format.historyGroup("2026-10-02T10:00:00Z", utc, today))
        assertEquals("Oct 2", Format.historyTime("2026-10-02T10:00:00Z", utc, today))
    }

    @Test
    fun modesAndBuckets() {
        assertEquals(ReaderModes.RTL, ReaderModes.resolve(null, ReaderModes.AUTO, "manga"))
        assertEquals(ReaderModes.VERTICAL, ReaderModes.resolve(null, ReaderModes.AUTO, "manhwa"))
        assertEquals(ReaderModes.LTR, ReaderModes.resolve(ReaderModes.LTR, ReaderModes.AUTO, "manhwa"))
        assertEquals(ReaderModes.PAGED_VERTICAL, ReaderModes.resolve(null, ReaderModes.PAGED_VERTICAL, "manga"))
        assertEquals(listOf(1..50, 51..100), jumpBuckets(402).take(2))
        assertEquals(9, jumpBuckets(402).size)
        assertEquals(12, jumpBuckets(2000).size)
        assertEquals(listOf(1..10, 11..12), jumpBuckets(12))
        assertEquals(61..63, jumpBuckets(63).last())
    }
}
