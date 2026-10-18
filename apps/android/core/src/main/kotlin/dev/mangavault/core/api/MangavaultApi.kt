package dev.mangavault.core.api

import dev.mangavault.core.model.AuthResponse
import dev.mangavault.core.model.BrowseQuery
import dev.mangavault.core.model.Chapter
import dev.mangavault.core.model.ChapterPages
import dev.mangavault.core.model.HistoryItem
import dev.mangavault.core.model.HomeFeed
import dev.mangavault.core.model.Items
import dev.mangavault.core.model.LibraryResponse
import dev.mangavault.core.model.MangaDetail
import dev.mangavault.core.model.MangaPage
import dev.mangavault.core.model.MeResponse
import dev.mangavault.core.model.ProgressItem
import dev.mangavault.core.model.ProgressResponse
import dev.mangavault.core.model.ReadResponse
import dev.mangavault.core.model.UpdateItem
import dev.mangavault.core.model.User
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An API failure. [status] 0 means the request never reached the server. */
class ApiException(val status: Int, val code: String, message: String) : Exception(message) {
    val isNetwork get() = status == 0
    val isUpstream get() = code == "upstream"
    val isUnauthorized get() = status == 401
}

interface MangavaultApi {
    suspend fun genres(): List<String>
    suspend fun home(): HomeFeed
    suspend fun browse(query: BrowseQuery): MangaPage
    suspend fun search(q: String, query: BrowseQuery): MangaPage
    suspend fun manga(id: Long): MangaDetail
    suspend fun chapters(mangaId: Long): List<Chapter>
    suspend fun pages(chapterId: Long): ChapterPages

    suspend fun signIn(provider: String, body: Map<String, String>): AuthResponse
    suspend fun logout()
    suspend fun me(): User

    suspend fun library(since: String?): LibraryResponse
    suspend fun putLibrary(mangaId: Long, category: String?, updatedAt: String)
    suspend fun deleteLibrary(mangaId: Long, updatedAt: String)
    suspend fun progress(since: String?): ProgressResponse
    suspend fun putProgress(items: List<ProgressItem>): Int
    suspend fun readChanges(since: String?): ReadResponse
    suspend fun putRead(chapterIds: List<Long>, read: Boolean, updatedAt: String)
    suspend fun history(limit: Int): List<HistoryItem>
    suspend fun deleteHistory(mangaId: Long?)
    suspend fun updates(): List<UpdateItem>
}

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

class HttpMangavaultApi(
    baseUrl: String,
    private val client: OkHttpClient,
    private val token: () -> String?,
) : MangavaultApi {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val jsonType = "application/json".toMediaType()

    private fun url(path: String, params: List<Pair<String, String?>> = emptyList()): HttpUrl {
        val b = base.newBuilder()
        path.trim('/').split('/').forEach { b.addPathSegment(it) }
        params.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun queryParams(q: BrowseQuery): List<Pair<String, String?>> = buildList {
        add("sort" to q.sort)
        q.genres.forEach { add("genre" to it) }
        q.excludeGenres.forEach { add("excludeGenre" to it) }
        add("status" to q.status)
        add("type" to q.type)
        add("page" to q.page.toString())
        add("limit" to q.limit.toString())
    }

    private suspend fun <T> call(
        method: String,
        url: HttpUrl,
        body: RequestBody? = null,
        serializer: KSerializer<T>?,
    ): T? {
        val req = Request.Builder().url(url).method(method, body)
            .header("Accept", "application/json")
            .apply { token()?.let { header("Authorization", "Bearer $it") } }
            .build()
        val res = try {
            client.newCall(req).await()
        } catch (e: IOException) {
            throw ApiException(0, "offline", e.message ?: "Network error")
        }
        res.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw ApiException(0, "offline", e.message ?: "Network error")
            }
            if (!it.isSuccessful) throw parseError(it.code, text)
            if (serializer == null || text.isBlank()) return null
            return try {
                ApiJson.decodeFromString(serializer, text)
            } catch (e: Exception) {
                throw ApiException(it.code, "bad_response", e.message ?: "Unexpected response")
            }
        }
    }

    private suspend fun <T> get(url: HttpUrl, s: KSerializer<T>): T = call("GET", url, null, s)!!

    private fun <T> body(s: KSerializer<T>, value: T) = ApiJson.encodeToString(s, value).toRequestBody(jsonType)

    override suspend fun genres() = get(url("genres"), Items.serializer(String.serializer())).items
    override suspend fun home() = get(url("home"), HomeFeed.serializer())
    override suspend fun browse(query: BrowseQuery) = get(url("manga", queryParams(query)), MangaPage.serializer())
    override suspend fun search(q: String, query: BrowseQuery) =
        get(url("search", listOf("q" to q) + queryParams(query)), MangaPage.serializer())

    override suspend fun manga(id: Long) = get(url("manga/$id"), MangaDetail.serializer())
    override suspend fun chapters(mangaId: Long) =
        get(url("manga/$mangaId/chapters"), Items.serializer(Chapter.serializer())).items

    override suspend fun pages(chapterId: Long) = get(url("chapters/$chapterId/pages"), ChapterPages.serializer())

    override suspend fun signIn(provider: String, body: Map<String, String>): AuthResponse {
        val s = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())
        return call("POST", url("auth/$provider"), body(s, body), AuthResponse.serializer())!!
    }

    override suspend fun logout() {
        call<Unit>("POST", url("auth/logout"), ByteArray(0).toRequestBody(jsonType), null)
    }

    override suspend fun me() = get(url("me"), MeResponse.serializer()).user

    override suspend fun library(since: String?) = get(url("me/library", listOf("since" to since)), LibraryResponse.serializer())

    @Serializable
    private data class LibraryPut(val category: String?, val updatedAt: String)

    override suspend fun putLibrary(mangaId: Long, category: String?, updatedAt: String) {
        call<Unit>("PUT", url("me/library/$mangaId"), body(LibraryPut.serializer(), LibraryPut(category, updatedAt)), null)
    }

    override suspend fun deleteLibrary(mangaId: Long, updatedAt: String) {
        call<Unit>("DELETE", url("me/library/$mangaId", listOf("updatedAt" to updatedAt)), null, null)
    }

    override suspend fun progress(since: String?) = get(url("me/progress", listOf("since" to since)), ProgressResponse.serializer())

    @Serializable
    private data class ProgressPut(val items: List<ProgressItem>)

    @Serializable
    private data class Accepted(val accepted: Int = 0)

    override suspend fun putProgress(items: List<ProgressItem>): Int =
        call("PUT", url("me/progress"), body(ProgressPut.serializer(), ProgressPut(items)), Accepted.serializer())?.accepted ?: 0

    override suspend fun readChanges(since: String?) =
        get(url("me/read", listOf("since" to (since ?: "1970-01-01T00:00:00.000Z"))), ReadResponse.serializer())

    @Serializable
    private data class ReadPut(val chapterIds: List<Long>, val read: Boolean, val updatedAt: String)

    override suspend fun putRead(chapterIds: List<Long>, read: Boolean, updatedAt: String) {
        call<Unit>("PUT", url("me/read"), body(ReadPut.serializer(), ReadPut(chapterIds, read, updatedAt)), null)
    }

    override suspend fun history(limit: Int) =
        get(url("me/history", listOf("limit" to limit.toString())), Items.serializer(HistoryItem.serializer())).items

    override suspend fun deleteHistory(mangaId: Long?) {
        call<Unit>("DELETE", url(if (mangaId == null) "me/history" else "me/history/$mangaId"), null, null)
    }

    override suspend fun updates() = get(url("me/updates"), Items.serializer(UpdateItem.serializer())).items

    private fun parseError(status: Int, text: String): ApiException {
        val err = runCatching { ApiJson.parseToJsonElement(text).jsonObject["error"] as JsonObject }.getOrNull()
        val code = err?.get("code")?.jsonPrimitive?.content ?: "http_$status"
        val message = err?.get("message")?.jsonPrimitive?.content ?: "HTTP $status"
        return ApiException(status, code, message)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}
