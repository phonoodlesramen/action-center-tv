package com.phonoodlesramen.actioncenter.nfl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** City photos (ui-spec "Two cities") against a local server: fetched once, kept, and never hammered. No request reaches Wikimedia. */
class NflCityPhotosTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private var now = 1_000_000L
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val photos = mapOf(
        "PHI" to CityPhoto("A made-up river, Philadelphia", "Made-up river.jpg", "A. Nobody", "CC0"),
        "WSH" to CityPhoto("A made-up dome, Washington", "Made-up dome.jpg", "A. Nobody", "Public domain"),
    )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun cities(photos: Map<String, CityPhoto> = this.photos) = NflCityPhotos(
        dir = folder.root,
        http = OkHttpClient(),
        photos = photos,
        clock = { now },
        io = Dispatchers.Unconfined,
        urlOf = { server.url("/${it.file.replace(' ', '_')}") },
    )

    private fun picture() = server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body(Buffer().write(jpeg)).build())

    @Test
    fun `fetched once, saying what it is, and kept`() = runTest {
        picture()
        val cities = cities()
        val file = cities.file("PHI")!!
        assertArrayEquals(jpeg, file.readBytes())
        val request = server.takeRequest()
        assertEquals("/Made-up_river.jpg", request.url.encodedPath)
        assertEquals(NflCityPhotos.USER_AGENT, request.headers["User-Agent"])
        // Again, and from a new cache on the same folder: no second request.
        assertEquals(file, cities.file("PHI"))
        assertEquals(file, cities().file("PHI"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a team without a photo asks nobody`() = runTest {
        assertNull(cities().file("SEA"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a failure or a page that isn't a picture waits ten minutes before trying again`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html>").build())
        picture()
        val cities = cities()
        assertNull(cities.file("PHI"))
        assertNull(cities.file("PHI"))
        assertEquals(1, server.requestCount)
        now += 10 * 60_000L
        assertNull(cities.file("PHI"))
        now += 10 * 60_000L
        assertTrue(cities.file("PHI")!!.isFile)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a new photo for the team replaces the old one`() = runTest {
        picture()
        picture()
        val old = cities().file("PHI")!!
        val new = cities(photos + ("PHI" to photos.getValue("PHI").copy(file = "Another made-up river.jpg"))).file("PHI")!!
        assertTrue(new.isFile)
        assertTrue(!old.exists())
        assertEquals(listOf(new.name), folder.root.list()!!.filter { it.startsWith("PHI-") })
    }
}
