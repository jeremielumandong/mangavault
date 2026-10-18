package dev.mangavault.core

import dev.mangavault.core.api.ApiException
import dev.mangavault.core.api.HttpMangavaultApi
import dev.mangavault.core.model.BrowseQuery
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private val server = MockWebServer()
    private var token: String? = null
    private lateinit var api: HttpMangavaultApi

    @Before
    fun setUp() {
        server.start()
        api = HttpMangavaultApi(server.url("/v1").toString(), OkHttpClient()) { token }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body))

    @Test
    fun parsesHomeAndIgnoresUnknownFields() = runTest {
        json("""{"popular":[{"id":42,"title":"Solo Leveling","coverUrl":null,"coverHeaders":{},"type":"manhwa","status":"ongoing","latestChapter":200.0,"updatedAt":"2026-10-15T08:00:00.000Z","extra":1}],"latest":[],"new":[{"id":1,"title":"A"}]}""")
        val home = api.home()
        assertEquals(42L, home.popular.single().id)
        assertEquals(200.0, home.popular.single().latestChapter!!, 0.0)
        assertEquals("A", home.newTitles.single().title)
        assertEquals("/v1/home", server.takeRequest().path)
    }

    @Test
    fun sendsFiltersAndToken() = runTest {
        token = "tok"
        json("""{"items":[],"page":2,"hasMore":false}""")
        api.search("sword saint", BrowseQuery(sort = "popular", genres = listOf("Action", "Drama"), excludeGenres = listOf("Horror"), status = "ongoing", page = 2, limit = 30))
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val url = req.requestUrl!!
        assertEquals("/v1/search", url.encodedPath)
        assertEquals("sword saint", url.queryParameter("q"))
        assertEquals(listOf("Action", "Drama"), url.queryParameterValues("genre"))
        assertEquals(listOf("Horror"), url.queryParameterValues("excludeGenre"))
        assertEquals("ongoing", url.queryParameter("status"))
        assertEquals(null, url.queryParameter("type"))
        assertEquals("2", url.queryParameter("page"))
    }

    @Test
    fun mapsErrorBodies() = runTest {
        json("""{"error":{"code":"upstream","message":"No site answered"}}""", 502)
        try {
            api.pages(9)
            fail()
        } catch (e: ApiException) {
            assertEquals(502, e.status)
            assertTrue(e.isUpstream)
            assertEquals("No site answered", e.message)
        }
    }

    @Test
    fun networkFailuresAreFlagged() = runTest {
        server.shutdown()
        try {
            api.genres()
            fail()
        } catch (e: ApiException) {
            assertTrue(e.isNetwork)
        }
    }

    @Test
    fun putsLibraryAndProgressBodies() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        json("""{"accepted":1}""")
        api.putLibrary(42, "Reading", "2026-10-17T22:00:00.000Z")
        val accepted = api.putProgress(listOf(dev.mangavault.core.model.ProgressItem(42, 9001, 112.0, 14, 38, false, "2026-10-17T22:00:00.000Z")))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/v1/me/library/42", put.path)
        assertEquals("""{"category":"Reading","updatedAt":"2026-10-17T22:00:00.000Z"}""", put.body.readUtf8())
        assertEquals(1, accepted)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"chapterId\":9001") && body.contains("\"pageCount\":38"))
    }
}
