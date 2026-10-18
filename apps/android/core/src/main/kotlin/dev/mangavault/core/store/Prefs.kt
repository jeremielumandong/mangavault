package dev.mangavault.core.store

import dev.mangavault.core.model.User
import dev.mangavault.core.util.ReaderModes
import kotlinx.serialization.Serializable

/** Device preferences. Never synced. */
@Serializable
data class Prefs(
    val onboarded: Boolean = false,
    val genres: List<String> = emptyList(),
    /** dark, light or oled */
    val theme: String = "dark",
    val recents: List<String> = emptyList(),
    val libCols: Int = 3,
    val libView: String = "grid",
    val libSort: String = "Last read",
    val libUnread: Boolean = false,
    val chaptersNewestFirst: Boolean = true,
    val reader: ReaderPrefs = ReaderPrefs(),
    /** Reading mode chosen per title. */
    val modes: Map<Long, String> = emptyMap(),
    val prefetch: Int = 5,
    val dataSaver: Boolean = true,
    val favPromptShown: Boolean = false,
) {
    fun withRecent(q: String): Prefs {
        val t = q.trim()
        if (t.isEmpty()) return this
        return copy(recents = (listOf(t) + recents.filter { !it.equals(t, ignoreCase = true) }).take(6))
    }
}

@Serializable
data class ReaderPrefs(
    val defaultMode: String = ReaderModes.AUTO,
    val background: String = "Black",
    val fit: String = "Width",
    val gap: Boolean = false,
    val dim: Boolean = true,
    val brightness: Int = 100,
    val keepOn: Boolean = true,
    val showNum: Boolean = true,
    val invert: Boolean = false,
    /** Paged modes: step through each page panel by panel. */
    val guided: Boolean = false,
)

/** The signed-in account, kept in app-private storage. */
@Serializable
data class Session(val token: String, val user: User, val provider: String)
