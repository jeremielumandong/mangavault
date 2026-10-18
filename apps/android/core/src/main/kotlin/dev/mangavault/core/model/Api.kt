package dev.mangavault.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire models for the v1 API (docs/api.md). */

@Serializable
data class MangaSummary(
    val id: Long,
    val title: String,
    val coverUrl: String? = null,
    val coverHeaders: Map<String, String> = emptyMap(),
    val type: String? = null,
    val status: String? = null,
    val latestChapter: Double? = null,
    val updatedAt: String? = null,
)

@Serializable
data class MangaDetail(
    val id: Long,
    val title: String,
    val coverUrl: String? = null,
    val coverHeaders: Map<String, String> = emptyMap(),
    val type: String? = null,
    val status: String? = null,
    val latestChapter: Double? = null,
    val updatedAt: String? = null,
    val altTitles: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val chapterCount: Int = 0,
    val detailsFetchedAt: String? = null,
) {
    fun summary() = MangaSummary(id, title, coverUrl, coverHeaders, type, status, latestChapter, updatedAt)
}

@Serializable
data class Chapter(
    val id: Long,
    val mangaId: Long,
    val number: Double? = null,
    val title: String? = null,
    val volume: String? = null,
    val publishedAt: String? = null,
    val index: Int = 0,
)

@Serializable
data class Page(
    val index: Int,
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class ChapterPages(
    val chapter: Chapter,
    val pages: List<Page>,
    val imageHeaders: Map<String, String> = emptyMap(),
    val prevChapterId: Long? = null,
    val nextChapterId: Long? = null,
)

@Serializable
data class MangaPage(
    val items: List<MangaSummary> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
)

@Serializable
data class HomeFeed(
    val popular: List<MangaSummary> = emptyList(),
    val latest: List<MangaSummary> = emptyList(),
    @SerialName("new") val newTitles: List<MangaSummary> = emptyList(),
)

/** `GET /v1/stats`: how big the server's catalog is. */
@Serializable
data class CatalogStats(val series: Long = 0, val chapters: Long = 0, val latestUpdateAt: String? = null)

@Serializable
data class Items<T>(val items: List<T> = emptyList())

@Serializable
data class User(val id: Long, val name: String? = null, val email: String? = null)

@Serializable
data class AuthResponse(val token: String, val user: User)

@Serializable
data class MeResponse(val user: User)

@Serializable
data class LibraryItem(
    val manga: MangaSummary,
    val category: String? = null,
    val addedAt: String? = null,
    val updatedAt: String,
    val deleted: Boolean = false,
    val unreadCount: Int? = null,
)

@Serializable
data class LibraryResponse(val items: List<LibraryItem> = emptyList(), val serverTime: String)

@Serializable
data class ProgressItem(
    val mangaId: Long,
    val chapterId: Long,
    val chapterNumber: Double? = null,
    val page: Int,
    val pageCount: Int? = null,
    val completed: Boolean = false,
    val updatedAt: String,
    val hidden: Boolean = false,
)

@Serializable
data class ProgressResponse(val items: List<ProgressItem> = emptyList(), val serverTime: String)

@Serializable
data class ReadItem(val chapterId: Long, val mangaId: Long, val read: Boolean, val updatedAt: String)

@Serializable
data class ReadResponse(val items: List<ReadItem> = emptyList(), val serverTime: String)

@Serializable
data class HistoryItem(
    val manga: MangaSummary,
    val chapterId: Long,
    val chapterNumber: Double? = null,
    val page: Int = 0,
    val readAt: String,
)

@Serializable
data class UpdateItem(val manga: MangaSummary, val newChapters: Int = 0)

/** Filters shared by `/v1/manga` and `/v1/search`. */
data class BrowseQuery(
    val sort: String? = null,
    val genres: List<String> = emptyList(),
    val excludeGenres: List<String> = emptyList(),
    val status: String? = null,
    val type: String? = null,
    val page: Int = 1,
    val limit: Int = 30,
)
