package com.phonoodlesramen.actioncenter.plex

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A title's streams from its details (G13). The fixture's shapes follow the PMS spec; the titles are made up. */
class PlexStreamsTest {
    private val server = MockWebServer()
    private lateinit var reader: PlexStreams
    private lateinit var access: ServerAccess

    @Before
    fun setUp() {
        server.start()
        reader = PlexStreams(OkHttpClient(), PlexHeaders({ "client-123" }, "0.2.0", SHIELD_PLEX_DEVICE))
        access = ServerAccess(server.url("/").toString(), "server-token")
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun fixture(name: String) = javaClass.getResource("/plex/$name")!!.readText()

    @Test
    fun `it reads the first part's audio and subtitle streams, with Plex's choices and flags`() = runTest {
        respond(fixture("metadata-streams.json"))

        val streams = reader.streams(access, "7303")!!

        // The video and chapter streams are left out.
        assertEquals(listOf("40002", "40003", "40004", "40005", "40006"), streams.map { it.id })
        val main = streams[0]
        assertEquals(PlexStream.AUDIO, main.type)
        assertEquals(1, main.index)
        assertEquals("eng", main.languageCode)
        assertEquals("English (TRUEHD 7.1)", main.displayTitle)
        assertTrue(main.selected)
        assertTrue(main.default)
        assertEquals(8, main.channels)
        assertEquals("Commentary (English AC3 Stereo)", streams[1].extendedDisplayTitle)
        assertFalse(streams[1].selected)
        // Plex sends some flags as "1".
        assertTrue(streams[2].forced)
        assertTrue(streams[3].inVideo)
        val sidecar = streams[4]
        assertNull(sidecar.index)
        assertEquals("/library/streams/40006", sidecar.key)
        assertEquals("srt", sidecar.codec)
    }

    @Test
    fun `the token goes in a header, never the URL`() = runTest {
        respond(fixture("metadata-streams.json"))

        reader.streams(access, "7303")

        val request = server.takeRequest()
        assertEquals("/library/metadata/7303", request.url.encodedPath)
        assertNull(request.url.query)
        assertEquals("server-token", request.headers["X-Plex-Token"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun `a title that's gone, or has no file, has no streams`() = runTest {
        respond("", code = 404)
        assertNull(reader.streams(access, "999"))
        respond("""{ "MediaContainer": { "Metadata": [ { "ratingKey": "5", "type": "movie", "title": "No file" } ] } }""")
        assertNull(reader.streams(access, "5"))
    }

    @Test
    fun `a bad answer or a refused token is Plex's error, for the player to treat as unknown`() = runTest {
        respond("not json")
        assertTrue(runCatching { reader.streams(access, "7303") }.exceptionOrNull() is PlexException.Failed)
        respond("", code = 401)
        assertTrue(runCatching { reader.streams(access, "7303") }.exceptionOrNull() is PlexException.Unauthorized)
    }
}
