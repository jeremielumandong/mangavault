package dev.mangavault.core.store

import dev.mangavault.core.model.Chapter
import dev.mangavault.core.model.MangaSummary
import kotlinx.serialization.Serializable
import java.time.Instant

val DEFAULT_CATEGORIES = listOf("Reading", "Plan to read", "Completed")

/** Everything a reader owns, kept on the phone. Synced when signed in. */
@Serializable
data class UserData(
    val library: Map<Long, LibraryEntry> = emptyMap(),
    val progress: Map<Long, ProgressEntry> = emptyMap(),
    /** Explicit read/unread marks by chapter id. */
    val readMarks: Map<Long, ReadMark> = emptyMap(),
    /** Latest read per manga. */
    val history: Map<Long, HistoryEntry> = emptyMap(),
    /** Last known summary of every manga referenced above. */
    val manga: Map<Long, MangaSummary> = emptyMap(),
    val categories: List<String> = DEFAULT_CATEGORIES,
    val pending: Pending = Pending(),
    val cursors: Cursors = Cursors(),
) {
    val favorites: List<LibraryEntry> get() = library.values.filter { !it.deleted }
    fun isFavorite(id: Long) = library[id]?.deleted == false
    val hasLocalData get() = favorites.isNotEmpty() || progress.isNotEmpty() || history.isNotEmpty()
}

@Serializable
data class LibraryEntry(
    val mangaId: Long,
    val category: String? = null,
    val addedAt: String,
    val updatedAt: String,
    val deleted: Boolean = false,
    /** From the server when signed in; computed locally otherwise. */
    val unreadCount: Int? = null,
)

@Serializable
data class ProgressEntry(
    val mangaId: Long,
    val chapterId: Long,
    val chapterNumber: Double? = null,
    val chapterTitle: String? = null,
    val page: Int,
    val pageCount: Int? = null,
    val completed: Boolean = false,
    val updatedAt: String,
)

@Serializable
data class ReadMark(val chapterId: Long, val mangaId: Long, val read: Boolean, val updatedAt: String)

@Serializable
data class HistoryEntry(
    val mangaId: Long,
    val chapterId: Long,
    val chapterNumber: Double? = null,
    val page: Int,
    val pageCount: Int? = null,
    val readAt: String,
)

/** Local changes not yet pushed to the server. */
@Serializable
data class Pending(
    val library: Set<Long> = emptySet(),
    val progress: Set<Long> = emptySet(),
    val read: Set<Long> = emptySet(),
    val historyDeletes: Set<Long> = emptySet(),
    val historyClearedAt: String? = null,
) {
    val isEmpty get() = library.isEmpty() && progress.isEmpty() && read.isEmpty() &&
        historyDeletes.isEmpty() && historyClearedAt == null
}

@Serializable
data class Cursors(
    val library: String? = null,
    val progress: String? = null,
    val read: String? = null,
    val lastSyncAt: String? = null,
)

fun isoNow(): String = Instant.now().toString()

/** Compares two ISO timestamps; unparsable values sort first. */
fun isoAfter(a: String?, b: String?): Boolean {
    val ta = a?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    val tb = b?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
    return ta.isAfter(tb)
}

/** Pure edits on [UserData], so they are easy to test and to apply atomically. */
object UserOps {
    fun cache(d: UserData, m: MangaSummary): UserData = d.copy(manga = d.manga + (m.id to m))

    fun setFavorite(d: UserData, m: MangaSummary, category: String?, now: String = isoNow()): UserData {
        val old = d.library[m.id]
        val entry = LibraryEntry(
            mangaId = m.id,
            category = category,
            addedAt = if (old == null || old.deleted) now else old.addedAt,
            updatedAt = now,
            unreadCount = old?.unreadCount,
        )
        val cats = if (category != null && category !in d.categories) d.categories + category else d.categories
        return cache(d, m).copy(
            library = d.library + (m.id to entry),
            categories = cats,
            pending = d.pending.copy(library = d.pending.library + m.id),
        )
    }

    fun removeFavorite(d: UserData, id: Long, now: String = isoNow()): UserData {
        val old = d.library[id] ?: return d
        return d.copy(
            library = d.library + (id to old.copy(deleted = true, updatedAt = now)),
            pending = d.pending.copy(library = d.pending.library + id),
        )
    }

    fun addCategory(d: UserData, name: String): UserData =
        if (name.isBlank() || name in d.categories) d else d.copy(categories = d.categories + name.trim())

    fun recordProgress(
        d: UserData,
        m: MangaSummary,
        chapter: Chapter,
        page: Int,
        pageCount: Int?,
        completed: Boolean,
        now: String = isoNow(),
    ): UserData {
        val p = ProgressEntry(m.id, chapter.id, chapter.number, chapter.title, page, pageCount, completed, now)
        val h = HistoryEntry(m.id, chapter.id, chapter.number, page, pageCount, now)
        var marks = d.readMarks
        if (completed) marks = marks + (chapter.id to ReadMark(chapter.id, m.id, true, now))
        return cache(d, m).copy(
            progress = d.progress + (m.id to p),
            history = d.history + (m.id to h),
            readMarks = marks,
            pending = d.pending.copy(
                progress = d.pending.progress + m.id,
                historyDeletes = d.pending.historyDeletes - m.id,
            ),
        )
    }

    fun setRead(d: UserData, mangaId: Long, chapterIds: Collection<Long>, read: Boolean, now: String = isoNow()): UserData {
        val marks = d.readMarks.toMutableMap()
        chapterIds.forEach { marks[it] = ReadMark(it, mangaId, read, now) }
        return d.copy(readMarks = marks, pending = d.pending.copy(read = d.pending.read + chapterIds))
    }

    fun removeHistory(d: UserData, id: Long): UserData = d.copy(
        history = d.history - id,
        pending = d.pending.copy(historyDeletes = d.pending.historyDeletes + id),
    )

    fun restoreHistory(d: UserData, entry: HistoryEntry): UserData = d.copy(
        history = d.history + (entry.mangaId to entry),
        pending = d.pending.copy(historyDeletes = d.pending.historyDeletes - entry.mangaId),
    )

    fun clearHistory(d: UserData, now: String = isoNow()): UserData = d.copy(
        history = emptyMap(),
        pending = d.pending.copy(historyDeletes = emptySet(), historyClearedAt = now),
    )

    fun restoreAllHistory(d: UserData, history: Map<Long, HistoryEntry>): UserData = d.copy(
        history = history,
        pending = d.pending.copy(historyClearedAt = null),
    )

    /** Whether [chapter] counts as read: an explicit mark wins, else progress implies it. */
    fun isRead(d: UserData, chapter: Chapter): Boolean {
        d.readMarks[chapter.id]?.let { return it.read }
        val p = d.progress[chapter.mangaId] ?: return false
        if (p.chapterId == chapter.id) return p.completed
        val n = chapter.number ?: return false
        val pn = p.chapterNumber ?: return false
        return n < pn || (p.completed && n <= pn)
    }

    /** Unread chapter count from the summary's latest number and local progress. */
    fun localUnread(d: UserData, id: Long): Int {
        val latest = d.manga[id]?.latestChapter ?: return d.library[id]?.unreadCount ?: 0
        val p = d.progress[id] ?: return kotlin.math.floor(latest).toInt().coerceAtLeast(0)
        val pn = p.chapterNumber ?: return 0
        val diff = kotlin.math.floor(latest).toInt() - kotlin.math.floor(pn).toInt()
        return (if (p.completed) diff else diff + 1).coerceAtLeast(0)
    }

    fun unread(d: UserData, id: Long, signedIn: Boolean): Int =
        if (signedIn) d.library[id]?.unreadCount ?: localUnread(d, id) else localUnread(d, id)

    /** Favorites with chapters newer than where the reader is, computed on the phone. */
    fun localUpdates(d: UserData): List<Pair<MangaSummary, Int>> = d.favorites.mapNotNull { e ->
        val m = d.manga[e.mangaId] ?: return@mapNotNull null
        val p = d.progress[e.mangaId] ?: return@mapNotNull null
        val latest = m.latestChapter ?: return@mapNotNull null
        val pn = p.chapterNumber ?: return@mapNotNull null
        if (!isoAfter(m.updatedAt, p.updatedAt)) return@mapNotNull null
        val n = kotlin.math.floor(latest).toInt() - kotlin.math.floor(pn).toInt()
        if (n > 0) m to n else null
    }.sortedByDescending { it.first.updatedAt }

    /** Prepare local data to be pushed in full after signing in ("Merge and sync"). */
    fun markAllPending(d: UserData): UserData = d.copy(
        pending = Pending(
            library = d.library.keys,
            progress = d.progress.keys,
            read = d.readMarks.keys,
        ),
        cursors = Cursors(),
    )

    /** "Use account data only": drop everything personal, keep the summary cache. */
    fun wipePersonal(d: UserData): UserData = UserData(manga = d.manga, categories = DEFAULT_CATEGORIES)
}
