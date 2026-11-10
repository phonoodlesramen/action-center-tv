package com.phonoodlesramen.actioncenter.plex

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the kids' sync asks the server (Phase 5, K2). Fixtures are made up, in the PMS spec's shapes. */
class KidPlexApiTest {
    private val server = MockWebServer()
    private lateinit var library: PlexLibrary
    private lateinit var access: ServerAccess

    @Before
    fun setUp() {
        server.start()
        library = PlexLibrary(OkHttpClient(), PlexHeaders({ "client-123" }, "0.2.0", TEST_DEVICE))
        access = ServerAccess(server.url("/").toString(), "server-token")
    }

    @After
    fun tearDown() = server.close()

    private fun respond(fixture: String) = server.enqueue(
        MockResponse.Builder().code(200).body(javaClass.getResource("/plex/$fixture")!!.readText()).build(),
    )

    @Test
    fun `the server's accounts are its ids and names, you first`() = runTest {
        respond("accounts.json")

        val accounts = library.accounts(access)

        assertEquals(listOf(0L to "", 1L to "Parent", 31_415_926L to "Maya"), accounts.map { it.id to it.name })
        val request = server.takeRequest()
        assertEquals("/accounts", request.url.encodedPath)
        assertEquals("server-token", request.headers["X-Plex-Token"])
    }

    @Test
    fun `a kid's history names their account, and an episode's play keeps its show`() = runTest {
        respond("history-kid.json")

        val plays = library.history(access, sinceSeconds = 1_793_000_000, accountId = 31_415_926)

        assertEquals(listOf("8801" to "8000", "7701" to null), plays.map { it.ratingKey to it.showKey })
        val url = server.takeRequest().url
        assertEquals("31415926", url.queryParameter("accountID"))
        assertTrue(url.toString(), url.toString().contains("viewedAt%3E=1793000000"))
        assertNull(plays[1].thumb)
    }
}
