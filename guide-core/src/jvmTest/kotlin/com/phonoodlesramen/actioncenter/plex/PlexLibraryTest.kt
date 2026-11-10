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

/**
 * Fixtures follow the official PMS spec's shapes (made-up titles and keys, nothing from a
 * real library), except where a real server differs: it sends `hidden` as 0/1.
 */
class PlexLibraryTest {
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

    private fun respond(fixture: String, code: Int = 200) = server.enqueue(
        MockResponse.Builder().code(code).body(javaClass.getResource("/plex/$fixture")!!.readText()).build(),
    )

    @Test
    fun `Continue Watching flattens the hub, episodes keep their show, and a numeric ratingKey parses`() = runTest {
        respond("continue-watching.json")

        val items = library.continueWatching(access, count = 12)

        assertEquals(listOf("5101", "6202", "7303"), items.map { it.ratingKey })
        val slowHorses = items.first()
        assertEquals("Slow Horses", slowHorses.showTitle)
        assertEquals(4 to 2, slowHorses.season to slowHorses.episode)
        assertEquals(480_000L, slowHorses.viewOffsetMs)
        assertEquals("5000" to 1_793_040_000L, slowHorses.showKey to slowHorses.lastViewedAt)
        assertNull(items[1].viewOffsetMs)
        assertEquals(listOf("Drama", "Science Fiction"), items[2].genres)
        val request = server.takeRequest()
        assertEquals("/hubs/continueWatching", request.url.encodedPath)
        assertEquals("12", request.url.queryParameter("count"))
        assertEquals("server-token", request.headers["X-Plex-Token"])
    }

    @Test
    fun `search reads each hub's Metadata and Directory, keeps Plex's reasons, and leaves tags out (Phase 15)`() = runTest {
        respond("hubs-search.json")

        val hubs = library.search(access, "harbor", limit = 12)

        assertEquals(listOf("movie", "show", "episode", "actor"), hubs.map { it.type })
        assertEquals(listOf("8101", "8102"), hubs[0].hits.map { it.ratingKey })
        assertEquals("actor" to "Dana Example", hubs[0].hits[1].reason to hubs[0].hits[1].reasonTitle)
        assertEquals("Harbor Watch", hubs[1].hits.single().title)
        val episode = hubs[2].hits.single()
        assertEquals(listOf("Harbor Watch", "8200", 2, 5), listOf(episode.showTitle, episode.showKey, episode.season, episode.episode))
        assertEquals("an episode shows its show's poster", "/library/metadata/8200/thumb/1", episode.thumb)
        assertEquals("a tag has no ratingKey", emptyList<PlexSearchHit>(), hubs[3].hits)
        val request = server.takeRequest()
        assertEquals("/hubs/search", request.url.encodedPath)
        assertEquals(listOf("harbor", "12", "1"), listOf("query", "limit", "includeCollections").map { request.url.queryParameter(it) })
        assertEquals("server-token", request.headers["X-Plex-Token"])
    }

    @Test
    fun `sections leave hidden ones out, whether the server says 0 and 1 or false and true`() = runTest {
        respond("sections.json")

        assertEquals(
            listOf(PlexSection("1", "movie", "Movies"), PlexSection("2", "show", "TV Shows"), PlexSection("3", "artist", "Music")),
            library.sections(access),
        )
        assertEquals("/library/sections/all", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `items page with Plex's container headers, and ask only for changes when given a date`() = runTest {
        respond("movies-page-0.json")

        val page = library.items(access, section = "1", type = 1, start = 0, size = 2, updatedSince = 1_790_000_000)

        assertEquals(listOf("Arrival", "Jaws"), page.items.map { it.title })
        assertEquals(3, page.totalSize)
        assertEquals(8.2, page.items.first().audienceRating!!, 0.0)
        val request = server.takeRequest()
        assertEquals("/library/sections/1/all", request.url.encodedPath)
        assertEquals("1", request.url.queryParameter("type"))
        assertEquals("0", request.headers["X-Plex-Container-Start"])
        assertEquals("2", request.headers["X-Plex-Container-Size"])
        assertTrue(request.url.toString(), request.url.toString().contains("updatedAt%3E%3E=1790000000"))
    }

    @Test
    fun `a movie's primaryExtraKey is its trailer, kept as the extra's ratingKey`() = runTest {
        // Spec: primaryExtraKey "for a movie, this is a trailer", and points to /library/metadata/{id}.
        respond("movies-page-0.json")

        val page = library.items(access, section = "1", type = 1, start = 0, size = 2)

        assertEquals(listOf("7310", null), page.items.map { it.trailerKey })
    }

    @Test
    fun `a show's primary extra isn't taken for a trailer`() = runTest {
        // The spec only says what a movie's (a trailer) and a track's (a music video) are.
        respond("shows-page-0.json")

        val page = library.items(access, section = "2", type = 2, start = 0, size = 200)

        assertNull(page.items.single().trailerKey)
    }

    @Test
    fun `a listed title carries its file's details and summary, but never the file's path`() = runTest {
        respond("movies-page-0.json")

        val arrival = library.items(access, section = "1", type = 1, start = 0, size = 2).items.first()

        assertEquals("A linguist is asked to talk to visitors.", arrival.summary)
        val media = arrival.media!!
        assertEquals("/library/parts/9101/1690000000/file.mkv", media.partKey)
        assertEquals(listOf("mkv", "hevc", "main 10", "4k"), listOf(media.container, media.videoCodec, media.videoProfile, media.videoResolution))
        assertEquals("truehd" to 8, media.audioCodec to media.audioChannels)
        assertEquals(52_000_000_000L, media.sizeBytes)
        assertNull("only a title's details carry streams", media.dynamicRange)
        assertTrue("the path on the server isn't kept", "Arrival.mkv" !in media.toString())
    }

    @Test
    fun `a listed title carries what Phase 3 scores on, with blank tags left out`() = runTest {
        respond("movies-page-0.json")

        val (arrival, jaws) = library.items(access, section = "1", type = 1, start = 0, size = 2).items

        assertEquals(1_200_000L, arrival.viewOffsetMs)
        assertEquals(9.0, arrival.userRating!!, 0.0)
        assertEquals(1_700_000_500L, arrival.lastRatedAt)
        assertEquals("2016-11-11", arrival.releaseDate)
        assertEquals(listOf("First Contact"), arrival.collections)
        assertEquals(listOf("Favourites"), arrival.labels)
        assertEquals(listOf("Dena Varga"), arrival.directors)
        assertEquals(listOf("Mara Quint", "Joel Barrow", "Ines Okafor"), arrival.actors)
        assertNull("unrated", jaws.userRating)
        assertEquals(emptyList<String>(), jaws.actors)
    }

    @Test
    fun `a listed title carries its studio, and a blank one is none`() = runTest {
        respond("movies-page-0.json")

        val (arrival, jaws) = library.items(access, section = "1", type = 1, start = 0, size = 2).items

        assertEquals("Northlight Pictures", arrival.studio)
        assertNull(jaws.studio)
    }

    @Test
    fun `a listed title's logo is its clearLogo image, and a title with none has none`() = runTest {
        respond("movies-page-0.json")

        val (arrival, jaws) = library.items(access, section = "1", type = 1, start = 0, size = 2).items

        assertEquals("/library/metadata/7303/clearLogo/1790000000", arrival.logo)
        assertNull("no Image array", jaws.logo)
    }

    @Test
    fun `a listed show with only other images has no logo`() = runTest {
        respond("shows-page-0.json")

        val show = library.items(access, section = "2", type = 2, start = 0, size = 200).items.single()

        assertNull("a poster and a square background, no clearLogo", show.logo)
    }

    @Test
    fun `a listed show carries its episode counts`() = runTest {
        respond("shows-page-0.json")

        val show = library.items(access, section = "2", type = 2, start = 0, size = 200).items.single()

        assertEquals(18 to 7, show.episodeCount to show.watchedEpisodeCount)
        assertEquals(listOf("Tess Morrow", "Owen Pike"), show.actors)
        assertEquals("2019-04-02", show.releaseDate)
        assertNull("this listing has no studio: the details fill it", show.studio)
    }

    @Test
    fun `episodes list with their show, and one without a file has no media`() = runTest {
        respond("episodes-page-0.json")

        val page = library.items(access, section = "2", type = 4, start = 0, size = 200)

        val pilot = page.items.first()
        assertEquals(listOf("episode", "8800", "Harbour Lights"), listOf(pilot.type, pilot.showKey, pilot.showTitle))
        assertEquals(1 to 1, pilot.season to pilot.episode)
        assertEquals("eac3", pilot.media?.audioCodec)
        assertNull(page.items[1].media)
        assertEquals("4", server.takeRequest().url.queryParameter("type"))
    }

    @Test
    fun `a title's details tell Dolby Vision from its video stream`() = runTest {
        respond("metadata-detail.json")

        val arrival = library.metadata(access, "7303")!!

        assertEquals(PlexMedia.DOLBY_VISION, arrival.media?.dynamicRange)
        assertEquals("/library/metadata/7303", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `several titles' details come in one request, with their full tags`() = runTest {
        respond("metadata-batch.json")

        val (arrival, show) = library.details(access, listOf("7303", "8801"))

        assertEquals("/library/metadata/7303,8801", server.takeRequest().url.encodedPath)
        assertEquals(listOf("Drama", "Science Fiction", "Mystery"), arrival.genres)
        assertEquals(listOf("Favourites", "Rewatch"), arrival.labels)
        assertEquals(4, arrival.actors.size)
        assertEquals(listOf("Halloween"), show.labels)
        assertEquals("Northlight Pictures" to "Tidewater Television", arrival.studio to show.studio)
        assertEquals("/library/metadata/8801/clearLogo/1790000000", show.logo)
        assertNull("these details list no images", arrival.logo)
    }

    @Test
    fun `details of no titles ask for nothing, and all of them gone is none`() = runTest {
        assertEquals(emptyList<PlexItem>(), library.details(access, emptyList()))
        server.enqueue(MockResponse.Builder().code(404).build())

        assertEquals(emptyList<PlexItem>(), library.details(access, listOf("998", "999")))
        assertEquals("only the second call reached the server", 1, server.requestCount)
    }

    @Test
    fun `credits start at each title's first credits marker, asked for with includeMarkers`() = runTest {
        respond("metadata-markers.json")

        val credits = library.creditsStart(access, listOf("8801", "7303", "7505"))

        assertEquals("an intro isn't the credits", mapOf("8801" to 3_240_000L, "7505" to 9_600_000L), credits)
        val url = server.takeRequest().url
        assertEquals("/library/metadata/8801,7303,7505", url.encodedPath)
        assertEquals("1", url.queryParameter("includeMarkers"))
    }

    @Test
    fun `credits of no titles ask for nothing, and all of them gone is none`() = runTest {
        assertEquals(emptyMap<String, Long>(), library.creditsStart(access, listOf("", "not-a-key")))
        server.enqueue(MockResponse.Builder().code(404).build())

        assertEquals(emptyMap<String, Long>(), library.creditsStart(access, listOf("999")))
        assertEquals("only the second call reached the server", 1, server.requestCount)
    }

    @Test
    fun `a title that's gone has no details`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        assertNull(library.metadata(access, "999"))
    }

    @Test
    fun `history is the owner's plays since a date, newest first`() = runTest {
        respond("history.json")

        val plays = library.history(access, sinceSeconds = 1_750_000_000)

        assertEquals(listOf("7303", "7505"), plays.map { it.ratingKey })
        assertEquals(1_761_400_000L, plays[1].viewedAt)
        val url = server.takeRequest().url
        assertEquals("1", url.queryParameter("accountID"))
        assertEquals("viewedAt:desc", url.queryParameter("sort"))
        assertTrue(url.toString(), url.toString().contains("viewedAt%3E=1750000000"))
    }

    @Test
    fun `a 401 from the server is Unauthorized, so the server token can be refreshed`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())

        val error = try {
            library.sections(access)
            null
        } catch (e: PlexException) {
            e
        }

        assertTrue(error is PlexException.Unauthorized)
    }
}
