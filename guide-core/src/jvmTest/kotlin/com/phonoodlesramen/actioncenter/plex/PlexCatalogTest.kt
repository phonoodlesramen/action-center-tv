package com.phonoodlesramen.actioncenter.plex

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Plex's catalog (Phase 15) on a fake server. The fixtures are made up, in the shapes the real
 * catalog answered with on 11/02/2026.
 */
class PlexCatalogTest {
    private val server = MockWebServer()
    private lateinit var catalog: PlexCatalog

    @Before
    fun setUp() {
        server.start()
        val base = server.url("/")
        catalog = PlexCatalog(OkHttpClient(), PlexHeaders({ "client-123" }, "0.2.0", TEST_DEVICE), discover = base, metadata = base)
    }

    @After
    fun tearDown() = server.close()

    private fun respond(fixture: String) = server.enqueue(
        MockResponse.Builder().code(200).body(javaClass.getResource("/plex/$fixture")!!.readText()).build(),
    )

    @Test
    fun `search reads the external results, movies and shows only, and the suggested terms, with no token`() = runTest {
        respond("catalog-search.json")

        val found = catalog.search("harbor", limit = 20)

        assertEquals(listOf("5d77aa01", "6841bb02"), found.titles.map { it.ratingKey })
        assertEquals(CatalogTitle("5d77aa01", "movie", "Harbor Lights", 1995, "https://example.invalid/t.jpg", null, listOf("Action", "Crime"), 0.76), found.titles[0])
        assertEquals(listOf("Harbor Heat", "Heathers Example"), found.suggestedTerms)
        val request = server.takeRequest()
        assertEquals("/library/search", request.url.encodedPath)
        assertEquals(
            listOf("harbor", "20", "movies,tv", "discover", "1"),
            listOf("query", "limit", "searchTypes", "searchProviders", "includeMetadata").map { request.url.queryParameter(it) },
        )
        assertNull(request.headers["X-Plex-Token"])
    }

    @Test
    fun `details give the rating and Common Sense Media's official age`() = runTest {
        respond("catalog-details.json")

        assertEquals(CatalogDetails("5d77aa01", "R", 16), catalog.details("5d77aa01"))
        assertEquals("/library/metadata/5d77aa01", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a title the catalog doesn't know has no details`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())

        assertNull(catalog.details("ffff"))
    }

    @Test
    fun `where to watch sends the token in a header, never the URL, and drops an offer with no platform`() = runTest {
        respond("catalog-offers.json")

        val offers = catalog.offers("5d77aa01", token = "user-token")

        assertEquals(
            listOf(
                CatalogOffer("netflix", "Netflix", "subscription", "US", "https://www.netflix.com/title/00000001"),
                CatalogOffer("amazon", "Amazon Video", "rent", "US", "https://www.amazon.com/example"),
            ),
            offers,
        )
        val request = server.takeRequest()
        assertEquals("/library/metadata/5d77aa01/availabilities", request.url.encodedPath)
        assertEquals("user-token", request.headers["X-Plex-Token"])
        assertEquals(null, request.url.queryParameter("X-Plex-Token"))
    }

    @Test
    fun `without a token where to watch is Plex saying no`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"Error":{"error":"Unauthorized"}}""").build())

        try {
            catalog.offers("5d77aa01", token = "stale")
            fail("expected Unauthorized")
        } catch (_: PlexException.Unauthorized) {
        }
    }

    @Test
    fun `a ratingKey that isn't the catalog's is refused before any request`() = runTest {
        try {
            catalog.details("../library")
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, server.requestCount)
    }
}
