package com.phonoodlesramen.actioncenter.plex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The guide's two writes (Phase 11): the resume point and watched, each with the profile's token in a header. */
class PlexWatchStateTest {
    private val server = MockWebServer()
    private lateinit var watch: PlexWatchState
    private lateinit var access: ServerAccess

    @Before
    fun setUp() {
        server.start()
        watch = PlexWatchState(OkHttpClient(), PlexHeaders({ "client-123" }, "0.2.0", TEST_DEVICE))
        access = ServerAccess(server.url("/").toString(), "kid-server-token")
    }

    @After
    fun tearDown() = server.close()

    private fun ok() = server.enqueue(MockResponse.Builder().code(200).build())

    @Test
    fun `the resume point is a GET of progress, stopped, at the time in millis`() = runTest {
        ok()

        watch.setPosition(access, "7303", 1_234_567)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/:/progress", request.url.encodedPath)
        assertEquals("7303", request.url.queryParameter("key"))
        assertEquals("com.plexapp.plugins.library", request.url.queryParameter("identifier"))
        assertEquals("1234567", request.url.queryParameter("time"))
        assertEquals("stopped", request.url.queryParameter("state"))
        assertEquals("kid-server-token", request.headers["X-Plex-Token"])
        assertEquals("client-123", request.headers["X-Plex-Client-Identifier"])
        assertFalse("never in the URL", request.url.toString().contains("kid-server-token"))
    }

    @Test
    fun `watched is a PUT of scrobble, with the library's identifier and the key`() = runTest {
        ok()

        watch.markWatched(access, "7303")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/:/scrobble", request.url.encodedPath)
        assertEquals("com.plexapp.plugins.library", request.url.queryParameter("identifier"))
        assertEquals("7303", request.url.queryParameter("key"))
        assertEquals(setOf("identifier", "key"), request.url.queryParameterNames)
        assertEquals("kid-server-token", request.headers["X-Plex-Token"])
        assertFalse("never in the URL", request.url.toString().contains("kid-server-token"))
    }

    @Test
    fun `a refused token is Unauthorized, another refusal is Failed with its code`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(500).build())

        val refused = runCatching { watch.markWatched(access, "7303") }.exceptionOrNull()
        val failed = runCatching { watch.setPosition(access, "7303", 90_000) }.exceptionOrNull()

        assertTrue(refused is PlexException.Unauthorized)
        assertEquals(500, (failed as PlexException.Failed).code)
    }

    @Test
    fun `a server that's gone is Unreachable`() = runTest {
        val gone = ServerAccess("http://127.0.0.1:1", "token")

        val error = runCatching { watch.markWatched(gone, "7303") }.exceptionOrNull()

        assertTrue(error.toString(), error is PlexException.Unreachable)
    }

    @Test
    fun `a key that would change the request is refused before anything is sent`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { watch.markWatched(access, "7303&key=1") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { watch.setPosition(access, "", 90_000) } }
        assertEquals(0, server.requestCount)
    }
}
