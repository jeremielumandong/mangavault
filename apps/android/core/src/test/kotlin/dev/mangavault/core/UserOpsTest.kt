package dev.mangavault.core

import dev.mangavault.core.model.Chapter
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.store.UserData
import dev.mangavault.core.store.UserOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserOpsTest {
    private val m = MangaSummary(1, "Ashen Crown", latestChapter = 118.0, updatedAt = "2026-10-17T00:00:00Z")
    private fun ch(id: Long, n: Double) = Chapter(id, 1, n, index = n.toInt() - 1)

    @Test
    fun favoritesAreTombstonedAndRestored() {
        var d = UserOps.setFavorite(UserData(), m, "Reading", "2026-10-01T00:00:00Z")
        assertTrue(d.isFavorite(1))
        assertEquals(setOf(1L), d.pending.library)
        d = UserOps.removeFavorite(d, 1, "2026-10-02T00:00:00Z")
        assertFalse(d.isFavorite(1))
        assertTrue(d.library[1]!!.deleted)
        d = UserOps.setFavorite(d, m, "Weekly", "2026-10-03T00:00:00Z")
        assertEquals("2026-10-03T00:00:00Z", d.library[1]!!.addedAt)
        assertTrue("Weekly" in d.categories)
    }

    @Test
    fun progressImpliesReadState() {
        var d = UserOps.recordProgress(UserData(), m, ch(112, 112.0), 14, 38, completed = false)
        assertTrue(UserOps.isRead(d, ch(111, 111.0)))
        assertFalse(UserOps.isRead(d, ch(112, 112.0)))
        assertFalse(UserOps.isRead(d, ch(113, 113.0)))
        d = UserOps.setRead(d, 1, listOf(111L), read = false)
        assertFalse(UserOps.isRead(d, ch(111, 111.0)))
        assertEquals(7, UserOps.localUnread(d, 1))
        d = UserOps.recordProgress(d, m, ch(112, 112.0), 38, 38, completed = true)
        assertTrue(UserOps.isRead(d, ch(112, 112.0)))
        assertEquals(6, UserOps.localUnread(d, 1))
        assertEquals(112.0, d.history[1]!!.chapterNumber!!, 0.0)
    }

    @Test
    fun historyRemovalIsUndoable() {
        var d = UserOps.recordProgress(UserData(), m, ch(5, 5.0), 1, 20, false)
        val entry = d.history[1]!!
        d = UserOps.removeHistory(d, 1)
        assertTrue(d.history.isEmpty())
        assertEquals(setOf(1L), d.pending.historyDeletes)
        d = UserOps.restoreHistory(d, entry)
        assertEquals(entry, d.history[1])
        assertTrue(d.pending.historyDeletes.isEmpty())
    }

    @Test
    fun localUpdatesNeedNewerSummary() {
        var d = UserOps.setFavorite(UserData(), m.copy(updatedAt = "2026-10-01T00:00:00Z"), "Reading")
        d = UserOps.recordProgress(d, m.copy(updatedAt = "2026-10-01T00:00:00Z"), ch(110, 110.0), 3, 20, false, "2026-10-05T00:00:00Z")
        assertTrue(UserOps.localUpdates(d).isEmpty())
        d = UserOps.cache(d, m.copy(updatedAt = "2026-10-10T00:00:00Z"))
        assertEquals(8, UserOps.localUpdates(d).single().second)
    }

    @Test
    fun continueReadingCanBeHiddenUntilReadAgain() {
        var d = UserOps.recordProgress(UserData(), m, ch(112, 112.0), 14, 38, completed = false, now = "2026-10-01T00:00:00Z")
        d = d.copy(pending = d.pending.copy(progress = emptySet()))
        d = UserOps.setContinueHidden(d, 1, true, "2026-10-02T00:00:00Z")
        assertTrue(d.progress[1]!!.hidden)
        assertEquals(14, d.progress[1]!!.page)
        assertEquals("2026-10-02T00:00:00Z", d.progress[1]!!.updatedAt)
        assertEquals(setOf(1L), d.pending.progress)
        d = UserOps.recordProgress(d, m, ch(112, 112.0), 15, 38, completed = false, now = "2026-10-03T00:00:00Z")
        assertFalse(d.progress[1]!!.hidden)
    }

    @Test
    fun categoriesRenameDeleteAndBulkMove() {
        val m2 = MangaSummary(2, "Two")
        var d = UserOps.setFavorite(UserData(), m, "Weekly", "2026-10-01T00:00:00Z")
        d = UserOps.setFavorite(d, m2, "Reading", "2026-10-01T00:00:00Z")
        d = d.copy(pending = d.pending.copy(library = emptySet()))

        d = UserOps.renameCategory(d, "Weekly", "Daily", "2026-10-02T00:00:00Z")
        assertEquals("Daily", d.library[1]!!.category)
        assertTrue("Daily" in d.categories && "Weekly" !in d.categories)
        assertEquals(setOf(1L), d.pending.library)

        d = UserOps.setCategory(d, listOf(1L, 2L), "Completed", "2026-10-03T00:00:00Z")
        assertEquals("Completed", d.library[2]!!.category)

        d = UserOps.deleteCategory(d, "Completed", "2026-10-04T00:00:00Z")
        assertEquals(null, d.library[1]!!.category)
        assertTrue(d.isFavorite(1) && d.isFavorite(2))
        assertFalse("Completed" in d.categories)

        val before = d.favorites
        d = UserOps.removeFavorites(d, listOf(1L, 2L), "2026-10-05T00:00:00Z")
        assertTrue(d.favorites.isEmpty())
        d = UserOps.restoreFavorites(d, before, "2026-10-06T00:00:00Z")
        assertEquals(2, d.favorites.size)
        assertEquals("2026-10-01T00:00:00Z", d.library[1]!!.addedAt)
    }
}
