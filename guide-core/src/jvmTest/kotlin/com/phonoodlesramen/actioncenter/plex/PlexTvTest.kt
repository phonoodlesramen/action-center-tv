package com.phonoodlesramen.actioncenter.plex

import java.time.Instant
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

/**
 * The pin-* and user-401 fixtures were recorded from plex.tv on 10/26/2026 (location
 * scrubbed); pin-claimed adds a token to a recorded one. user and resources are
 * hand-written to Plex's documented shape until a real sign-in can be recorded.
 */
class PlexTvTest {
    private val server = MockWebServer()
    private lateinit var plex: PlexTv

    @Before
    fun setUp() {
        server.start()
        plex = PlexTv(OkHttpClient(), PlexHeaders({ "client-123" }, "0.2.0", TEST_DEVICE), server.url("/"))
    }

    @After
    fun tearDown() = server.close()

    private fun respond(fixture: String, code: Int = 200) = server.enqueue(
        MockResponse.Builder().code(code).body(javaClass.getResource("/plex/$fixture")!!.readText()).build(),
    )

    private suspend fun failure(block: suspend () -> Unit): PlexException = try {
        block()
        throw AssertionError("Expected a PlexException")
    } catch (e: PlexException) {
        e
    }

    @Test
    fun `a new code is 4 characters with its expiry, and every request says who we are`() = runTest {
        respond("pin-create.json", code = 201)

        val pin = plex.createPin()

        assertEquals(4, pin.code.length)
        assertEquals(44974373L, pin.id)
        assertNull(pin.authToken)
        assertEquals(Instant.parse("2026-10-26T23:30:44Z").toEpochMilli(), pin.expiresAtMillis)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v2/pins", request.url.encodedPath)
        assertEquals(null, request.url.queryParameter("strong"))
        assertEquals("client-123", request.headers["X-Plex-Client-Identifier"])
        assertEquals("Action Center", request.headers["X-Plex-Product"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun `every request names the device it's given, a media request too`() = runTest {
        val station = PlexDevice(platform = "Linux", platformVersion = "6.1", device = "Guide station", deviceName = "NAS")
        val headers = PlexHeaders({ "client-123" }, "0.2.0", station)
        respond("pin-create.json", code = 201)

        PlexTv(OkHttpClient(), headers, server.url("/")).createPin()

        val request = server.takeRequest()
        assertEquals("Linux", request.headers["X-Plex-Platform"])
        assertEquals("6.1", request.headers["X-Plex-Platform-Version"])
        assertEquals("Guide station", request.headers["X-Plex-Device"])
        assertEquals("NAS", request.headers["X-Plex-Device-Name"])
        val media = headers.media("server-token")
        assertEquals("Linux", media["X-Plex-Platform"])
        assertEquals("Guide station", media["X-Plex-Device"])
        assertEquals("server-token", media["X-Plex-Token"])
    }

    @Test
    fun `an unclaimed code has no token yet, a claimed one does`() = runTest {
        respond("pin-check.json")
        respond("pin-claimed.json")

        assertNull(plex.checkPin(44974373)!!.authToken)
        assertEquals("fixture-user-token", plex.checkPin(44974373)!!.authToken)
        assertEquals("/api/v2/pins/44974373", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `an expired code is null, not an error`() = runTest {
        respond("pin-missing.json", code = 404)

        assertNull(plex.checkPin(1))
    }

    @Test
    fun `the account comes back for a good token, and a 401 means sign in again`() = runTest {
        respond("user.json")
        respond("user-401.json", code = 401)

        assertEquals(PlexAccount(1234567, "0a1b2c3d4e5f6a7b", "example-user", "Example User"), plex.account("good"))
        assertEquals("good", server.takeRequest().headers["X-Plex-Token"])
        assertTrue(failure { plex.account("bad") } is PlexException.Unauthorized)
    }

    @Test
    fun `servers keep only machines that provide a server, with their connections`() = runTest {
        respond("resources.json")

        val servers = plex.servers("good")

        assertEquals(listOf("Basement", "Friend's server"), servers.map { it.name })
        val basement = servers.first()
        assertEquals("aaaa1111bbbb2222cccc3333dddd4444", basement.clientIdentifier)
        assertEquals("fixture-server-token", basement.accessToken)
        assertTrue(basement.owned)
        assertEquals(4, basement.connections.size)
        assertTrue(basement.connections.single { it.ipv6 }.local)
        val url = server.takeRequest().url
        assertEquals("1", url.queryParameter("includeRelay"))
        assertEquals("1", url.queryParameter("includeHttps"))
    }

    @Test
    fun `no network is Unreachable, a server error is Failed`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(failure { plex.createPin() } is PlexException.Failed)

        server.close()
        assertTrue(failure { plex.createPin() } is PlexException.Unreachable)
    }
}
