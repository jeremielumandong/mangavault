package dev.mangavault.core

import dev.mangavault.core.api.MangavaultApi
import dev.mangavault.core.model.AuthResponse
import dev.mangavault.core.model.BrowseQuery
import dev.mangavault.core.model.Chapter
import dev.mangavault.core.model.ChapterPages
import dev.mangavault.core.model.HistoryItem
import dev.mangavault.core.model.HomeFeed
import dev.mangavault.core.model.LibraryItem
import dev.mangavault.core.model.LibraryResponse
import dev.mangavault.core.model.MangaDetail
import dev.mangavault.core.model.MangaPage
import dev.mangavault.core.model.MangaSummary
import dev.mangavault.core.model.ProgressItem
import dev.mangavault.core.model.ProgressResponse
import dev.mangavault.core.model.ReadItem
import dev.mangavault.core.model.ReadResponse
import dev.mangavault.core.model.UpdateItem
import dev.mangavault.core.model.User
import dev.mangavault.core.store.JsonFileState
import dev.mangavault.core.store.UserData
import dev.mangavault.core.store.UserOps
import dev.mangavault.core.sync.SyncEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyncEngineTest {
    private class FakeApi : MangavaultApi {
        val libraryPuts = mutableListOf<Triple<Long, String?, String>>()
        val libraryDeletes = mutableListOf<Long>()
        val progressPuts = mutableListOf<ProgressItem>()
        val readPuts = mutableListOf<Pair<List<Long>, Boolean>>()
        val historyDeletes = mutableListOf<Long?>()
        var remoteLibrary = listOf<LibraryItem>()
        var remoteProgress = listOf<ProgressItem>()
        var remoteRead = listOf<ReadItem>()
        var remoteHistory = listOf<HistoryItem>()
        val sinces = mutableListOf<String?>()

        override suspend fun genres() = emptyList<String>()
        override suspend fun home() = HomeFeed()
        override suspend fun stats() = dev.mangavault.core.model.CatalogStats()
        override suspend fun browse(query: BrowseQuery) = MangaPage()
        override suspend fun search(q: String, query: BrowseQuery) = MangaPage()
        override suspend fun manga(id: Long): MangaDetail = error("unused")
        override suspend fun chapters(mangaId: Long) = emptyList<Chapter>()
        override suspend fun pages(chapterId: Long): ChapterPages = error("unused")
        override suspend fun signIn(provider: String, body: Map<String, String>) = AuthResponse("t", User(1))
        override suspend fun logout() {}
        override suspend fun me() = User(1)
        override suspend fun library(since: String?): LibraryResponse { sinces += since; return LibraryResponse(remoteLibrary, "2026-10-18T00:00:00.000Z") }
        override suspend fun putLibrary(mangaId: Long, category: String?, updatedAt: String) { libraryPuts += Triple(mangaId, category, updatedAt) }
        override suspend fun deleteLibrary(mangaId: Long, updatedAt: String) { libraryDeletes += mangaId }
        override suspend fun progress(since: String?) = ProgressResponse(remoteProgress, "2026-10-18T00:00:00.000Z")
        override suspend fun putProgress(items: List<ProgressItem>): Int { progressPuts += items; return items.size }
        override suspend fun readChanges(since: String?) = ReadResponse(remoteRead, "2026-10-18T00:00:00.000Z")
        override suspend fun putRead(chapterIds: List<Long>, read: Boolean, updatedAt: String) { readPuts += chapterIds to read }
        override suspend fun history(limit: Int) = remoteHistory
        override suspend fun deleteHistory(mangaId: Long?) { historyDeletes += mangaId }
        override suspend fun updates() = emptyList<UpdateItem>()
    }

    private val m1 = MangaSummary(1, "One", latestChapter = 10.0)
    private val m2 = MangaSummary(2, "Two", latestChapter = 20.0)

    private fun store(scope: kotlinx.coroutines.CoroutineScope) =
        JsonFileState(File.createTempFile("user", ".json").apply { delete() }, UserData.serializer(), UserData(), scope)

    @Test
    fun pushesPendingAndPullsWithLastWriteWins() = runTest {
        val s = store(backgroundScope)
        s.update { UserOps.setFavorite(it, m1, "Reading", "2026-10-10T00:00:00.000Z") }
        s.update { UserOps.recordProgress(it, m1, Chapter(100, 1, 3.0), 5, 20, false, "2026-10-10T00:00:00.000Z") }
        s.update { UserOps.setRead(it, 1, listOf(98L, 99L), true, "2026-10-10T00:00:00.000Z") }
        val api = FakeApi().apply {
            remoteLibrary = listOf(
                // Older than our local edit: ignored.
                LibraryItem(m1, "Completed", updatedAt = "2026-10-01T00:00:00.000Z", unreadCount = 7),
                LibraryItem(m2, "Plan to read", updatedAt = "2026-10-12T00:00:00.000Z", unreadCount = 20),
            )
            remoteProgress = listOf(ProgressItem(2, 200, 4.0, 2, 30, false, "2026-10-12T00:00:00.000Z"))
            remoteRead = listOf(ReadItem(150, 2, true, "2026-10-12T00:00:00.000Z"))
            remoteHistory = listOf(HistoryItem(m2, 200, 4.0, 2, "2026-10-12T00:00:00.000Z"))
        }
        SyncEngine(api, s).sync()
        val d = s.value
        assertEquals(listOf(Triple(1L, "Reading" as String?, "2026-10-10T00:00:00.000Z")), api.libraryPuts)
        assertEquals(1, api.progressPuts.size)
        assertEquals(listOf(listOf(98L, 99L) to true), api.readPuts)
        assertTrue(d.pending.isEmpty)
        assertEquals("Reading", d.library[1]!!.category)
        assertEquals(7, d.library[1]!!.unreadCount)
        assertEquals("Plan to read", d.library[2]!!.category)
        assertEquals(200L, d.progress[2]!!.chapterId)
        assertTrue(d.readMarks[150]!!.read)
        assertEquals(200L, d.history[2]!!.chapterId)
        assertEquals("2026-10-18T00:00:00.000Z", d.cursors.library)

        SyncEngine(api, s).sync()
        assertEquals("2026-10-18T00:00:00.000Z", api.sinces.last())
    }

    @Test
    fun remoteTombstoneRemovesFavoriteAndHistoryDeletesPush() = runTest {
        val s = store(backgroundScope)
        s.update { UserOps.setFavorite(it, m1, "Reading", "2026-10-01T00:00:00.000Z") }
        s.update { UserOps.recordProgress(it, m1, Chapter(100, 1, 3.0), 5, 20, false, "2026-10-01T00:00:00.000Z") }
        s.update { UserOps.removeHistory(it, 1) }
        val api = FakeApi().apply {
            remoteLibrary = listOf(LibraryItem(m1, "Reading", updatedAt = "2026-10-05T00:00:00.000Z", deleted = true))
            remoteHistory = listOf(HistoryItem(m1, 100, 3.0, 5, "2026-10-01T00:00:00.000Z"))
        }
        SyncEngine(api, s).sync()
        assertFalse(s.value.isFavorite(1))
        assertEquals(listOf<Long?>(1L), api.historyDeletes)
        // The pulled history happened before our delete went through, but the push already removed it server side.
        assertTrue(s.value.pending.historyDeletes.isEmpty())
    }
}
