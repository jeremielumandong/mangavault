package dev.mangavault.core.sync

import dev.mangavault.core.api.MangavaultApi
import dev.mangavault.core.model.ProgressItem
import dev.mangavault.core.store.HistoryEntry
import dev.mangavault.core.store.JsonFileState
import dev.mangavault.core.store.LibraryEntry
import dev.mangavault.core.store.ProgressEntry
import dev.mangavault.core.store.ReadMark
import dev.mangavault.core.store.UserData
import dev.mangavault.core.store.isoAfter
import dev.mangavault.core.store.isoNow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Two-way sync of [UserData] with `/v1/me/...`: push pending local changes,
 * then pull everything changed since the last cursor. Conflicts resolve by
 * last write wins on `updatedAt`, like the server.
 */
class SyncEngine(private val api: MangavaultApi, private val store: JsonFileState<UserData>) {
    private val lock = Mutex()

    suspend fun sync() = lock.withLock {
        push()
        pull()
    }

    private suspend fun push() {
        val d = store.value
        val p = d.pending

        for (id in p.library) {
            val e = d.library[id]
            if (e != null) {
                if (e.deleted) api.deleteLibrary(id, e.updatedAt) else api.putLibrary(id, e.category, e.updatedAt)
            }
            store.update { it.copy(pending = it.pending.copy(library = it.pending.library - id).takeIfUnchanged(it, id, e)) }
        }

        val progress = p.progress.mapNotNull { d.progress[it] }
        progress.chunked(200).forEach { chunk ->
            api.putProgress(chunk.map { it.toItem() })
            val sent = chunk.associate { it.mangaId to it.updatedAt }
            store.update { cur ->
                val done = sent.filter { (id, t) -> cur.progress[id]?.updatedAt == t }.keys
                cur.copy(pending = cur.pending.copy(progress = cur.pending.progress - done))
            }
        }

        val marks = p.read.mapNotNull { d.readMarks[it] }
        marks.groupBy { it.read to it.updatedAt }.forEach { (key, group) ->
            group.chunked(500).forEach { chunk -> api.putRead(chunk.map { it.chapterId }, key.first, key.second) }
            val ids = group.map { it.chapterId }.toSet()
            store.update { cur ->
                val done = ids.filter { cur.readMarks[it]?.updatedAt == key.second }
                cur.copy(pending = cur.pending.copy(read = cur.pending.read - done.toSet()))
            }
        }

        p.historyClearedAt?.let { t ->
            api.deleteHistory(null)
            store.update { cur ->
                if (cur.pending.historyClearedAt == t) cur.copy(pending = cur.pending.copy(historyClearedAt = null)) else cur
            }
        }
        for (id in p.historyDeletes) {
            api.deleteHistory(id)
            store.update { cur ->
                if (cur.history.containsKey(id)) cur
                else cur.copy(pending = cur.pending.copy(historyDeletes = cur.pending.historyDeletes - id))
            }
        }
    }

    private suspend fun pull() {
        val lib = api.library(store.value.cursors.library)
        store.update { cur -> mergeLibrary(cur, lib.items.map { it.manga to it.toEntry() }).let {
            it.copy(cursors = it.cursors.copy(library = lib.serverTime))
        } }

        val prog = api.progress(store.value.cursors.progress)
        store.update { cur -> mergeProgress(cur, prog.items).let { it.copy(cursors = it.cursors.copy(progress = prog.serverTime)) } }

        val read = api.readChanges(store.value.cursors.read)
        store.update { cur ->
            val marks = cur.readMarks.toMutableMap()
            read.items.forEach { r ->
                val local = marks[r.chapterId]
                if (r.chapterId !in cur.pending.read && (local == null || !isoAfter(local.updatedAt, r.updatedAt))) {
                    marks[r.chapterId] = ReadMark(r.chapterId, r.mangaId, r.read, r.updatedAt)
                }
            }
            cur.copy(readMarks = marks, cursors = cur.cursors.copy(read = read.serverTime))
        }

        val history = api.history(100)
        store.update { cur ->
            val h = cur.history.toMutableMap()
            var manga = cur.manga
            history.forEach { item ->
                if (item.manga.id in cur.pending.historyDeletes) return@forEach
                if (cur.pending.historyClearedAt != null && !isoAfter(item.readAt, cur.pending.historyClearedAt)) return@forEach
                val local = h[item.manga.id]
                if (local == null || isoAfter(item.readAt, local.readAt)) {
                    val pageCount = cur.progress[item.manga.id]?.takeIf { it.chapterId == item.chapterId }?.pageCount
                    h[item.manga.id] = HistoryEntry(item.manga.id, item.chapterId, item.chapterNumber, item.page, pageCount, item.readAt)
                }
                manga = manga + (item.manga.id to item.manga)
            }
            cur.copy(history = h, manga = manga, cursors = cur.cursors.copy(lastSyncAt = isoNow()))
        }
    }

    companion object {
        fun mergeLibrary(cur: UserData, items: List<Pair<dev.mangavault.core.model.MangaSummary, LibraryEntry>>): UserData {
            val lib = cur.library.toMutableMap()
            val manga = cur.manga.toMutableMap()
            val cats = cur.categories.toMutableList()
            items.forEach { (m, remote) ->
                manga[m.id] = m
                val local = lib[m.id]
                when {
                    m.id in cur.pending.library && local != null && isoAfter(local.updatedAt, remote.updatedAt) -> Unit
                    local == null || !isoAfter(local.updatedAt, remote.updatedAt) -> {
                        lib[m.id] = remote
                        remote.category?.let { if (!remote.deleted && it !in cats) cats += it }
                    }
                    else -> lib[m.id] = local.copy(unreadCount = remote.unreadCount)
                }
            }
            return cur.copy(library = lib, manga = manga, categories = cats)
        }

        fun mergeProgress(cur: UserData, items: List<ProgressItem>): UserData {
            val prog = cur.progress.toMutableMap()
            items.forEach { r ->
                val local = prog[r.mangaId]
                if (local == null || !isoAfter(local.updatedAt, r.updatedAt)) {
                    val title = local?.takeIf { it.chapterId == r.chapterId }?.chapterTitle
                    prog[r.mangaId] = ProgressEntry(r.mangaId, r.chapterId, r.chapterNumber, title, r.page, r.pageCount, r.completed, r.updatedAt, r.hidden)
                }
            }
            val pending = cur.pending.copy(progress = cur.pending.progress.filter { id ->
                val l = prog[id]
                l != null && items.none { it.mangaId == id && !isoAfter(l.updatedAt, it.updatedAt) }
            }.toSet())
            return cur.copy(progress = prog, pending = pending)
        }
    }
}

private fun dev.mangavault.core.model.LibraryItem.toEntry() =
    LibraryEntry(manga.id, category, addedAt ?: updatedAt, updatedAt, deleted, unreadCount)

private fun ProgressEntry.toItem() = ProgressItem(mangaId, chapterId, chapterNumber, page, pageCount, completed, updatedAt, hidden)

/** Keep an id pending when the entry changed again while its push was in flight. */
private fun dev.mangavault.core.store.Pending.takeIfUnchanged(cur: UserData, id: Long, sent: LibraryEntry?) =
    if (sent != null && cur.library[id]?.updatedAt != sent.updatedAt) copy(library = library + id) else this
