package com.phonoodlesramen.actioncenter.plex.cache

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.phonoodlesramen.actioncenter.plex.ConnectionKind
import com.phonoodlesramen.actioncenter.plex.FakeLinkStore
import com.phonoodlesramen.actioncenter.plex.PlexAccount
import com.phonoodlesramen.actioncenter.plex.PlexAccounts
import com.phonoodlesramen.actioncenter.plex.PlexConnection
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexMedia
import com.phonoodlesramen.actioncenter.plex.PlexPage
import com.phonoodlesramen.actioncenter.plex.PlexPin
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import com.phonoodlesramen.actioncenter.plex.PlexSection
import com.phonoodlesramen.actioncenter.plex.PlexServer
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.PlexServers
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.ServerReach
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexSyncTest {
    private val db = Room.inMemoryDatabaseBuilder<PlexDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()
    private val dao = db.dao()
    private var now = 1_793_000_000_000L

    @After
    fun tearDown() = db.close()

    private fun movie(key: String, title: String, viewCount: Int = 0) =
        PlexItem(key, "movie", title, year = 2000, durationMs = 6_000_000, viewCount = viewCount, addedAt = 1_790_000_000)

    private class FakeServer : PlexServerApi {
        var down = false
        var continueWatchingFails = false
        var continueWatching = listOf(PlexItem("5101", "episode", "Hello Darkness", showTitle = "Slow Horses", season = 4, episode = 2))
        var sections = listOf(PlexSection("1", "movie", "Movies"), PlexSection("2", "show", "TV"), PlexSection("3", "artist", "Music"))
        val items = mutableMapOf<String, List<PlexItem>>()
        /** A TV section's episodes (type 4); [items] holds its shows (type 2). */
        val episodes = mutableMapOf<String, List<PlexItem>>()
        var history = emptyList<PlexPlay>()
        /** Sections asked for movies or shows; [askedEpisodes] the ones asked for episodes. */
        val asked = mutableListOf<Pair<String, Long?>>()
        val askedEpisodes = mutableListOf<String>()
        val historyAsked = mutableListOf<Long>()
        val bases = mutableListOf<String>()

        private fun check(access: ServerAccess) {
            bases += access.baseUri
            if (down) throw PlexException.Unreachable(IOException("down"))
        }

        override suspend fun continueWatching(access: ServerAccess, count: Int): List<PlexItem> {
            check(access)
            if (continueWatchingFails) throw PlexException.Failed(404, "/hubs/continueWatching answered 404")
            return continueWatching
        }
        override suspend fun sections(access: ServerAccess) = sections.also { check(access) }
        override suspend fun items(access: ServerAccess, section: String, type: Int, start: Int, size: Int, updatedSince: Long?): PlexPage<PlexItem> {
            check(access)
            if (start == 0) {
                if (type == 4) askedEpisodes += section else asked += section to updatedSince
            }
            val source = if (type == 4) episodes[section] else items[section]
            // Like the server: with a date, only items updated after it.
            val all = source.orEmpty().filter { updatedSince == null || (it.updatedAt ?: 0) > updatedSince }
            return PlexPage(all.drop(start).take(size), start, all.size)
        }
        override suspend fun history(access: ServerAccess, sinceSeconds: Long, accountId: Int, size: Int): List<PlexPlay> {
            check(access)
            historyAsked += sinceSeconds
            return history
        }

        /** Titles' details by key (S11): a key that's missing has none. */
        val details = mutableMapOf<String, PlexItem>()
        val detailsAsked = mutableListOf<List<String>>()
        var detailsFail = false

        override suspend fun details(access: ServerAccess, ratingKeys: List<String>): List<PlexItem> {
            check(access)
            detailsAsked += ratingKeys
            if (detailsFail) throw PlexException.Failed(500, "/library/metadata answered 500")
            return ratingKeys.mapNotNull { details[it] }
        }

        /** Where titles' credits start, by key; [creditsAsked] the keys each request named. */
        val credits = mutableMapOf<String, Long>()
        val creditsAsked = mutableListOf<List<String>>()
        var creditsFail = false

        override suspend fun creditsStart(access: ServerAccess, ratingKeys: List<String>): Map<String, Long> {
            check(access)
            creditsAsked += ratingKeys
            if (creditsFail) throw PlexException.Failed(500, "/library/metadata answered 500")
            return credits.filterKeys { it in ratingKeys }
        }
    }

    private class FakeAccounts : PlexAccounts {
        var servers = emptyList<PlexServer>()
        var revoked = false
        override suspend fun createPin() = PlexPin(1, "CODE", Long.MAX_VALUE, null)
        override suspend fun checkPin(id: Long) = null
        override suspend fun account(token: String) = PlexAccount(1, "u", "user", "User")
        override suspend fun servers(token: String): List<PlexServer> {
            if (revoked) throw PlexException.Unauthorized()
            return servers
        }
    }

    private class Setup(val store: FakeLinkStore, val server: FakeServer, val accounts: FakeAccounts, val sync: PlexSync)

    private fun basement(uri: String) = PlexServer(
        "Basement", "aaaa", "server-token", owned = true,
        listOf(PlexConnection(uri, "https", "192.168.1.20", 32400, local = true, relay = false, ipv6 = false)),
    )

    private suspend fun setup(linked: Boolean = true, afterSync: suspend () -> Unit = {}): Setup {
        val store = FakeLinkStore()
        if (linked) {
            store.saveAccount("user-token", PlexAccount(1, "u", "user", "User"))
            store.saveServer(basement("https://old"), PlexServers.Candidate("https://old", ConnectionKind.LOCAL))
        }
        val server = FakeServer()
        val accounts = FakeAccounts()
        val reach = ServerReach { s -> PlexServers.candidates(s).first() }
        return Setup(store, server, accounts, PlexSync(store, accounts, reach, server, dao, clock = { now }, log = {}, afterSync = afterSync))
    }

    @Test
    fun `the first sync copies Continue Watching, every Movies and TV item across pages, and history`() = runTest {
        val s = setup()
        s.server.items["1"] = (1..250).map { movie("m$it", "Movie $it") }
        s.server.items["2"] = listOf(PlexItem("s1", "show", "Slow Horses"))
        s.server.items["3"] = listOf(PlexItem("a1", "artist", "Not cached"))
        s.server.history = listOf(PlexPlay("h1", "m7", "movie", "Movie 7", 1_792_900_000, null))

        assertEquals(SyncResult.UPDATED, s.sync.sync())

        assertEquals(listOf("Slow Horses"), dao.continueWatching().map { it.showTitle })
        assertEquals(251, dao.itemCount())
        assertEquals(listOf("1" to null, "2" to null), s.server.asked)
        assertEquals(1_792_900_000L, dao.latestPlay())
        val state = dao.syncState()!!
        assertEquals(now, state.lastSuccessAt)
        assertEquals(now, state.lastFullAt)
        assertNull(state.error)
    }

    @Test
    fun `a movie's trailer extra is cached with it, so Trailer needs no request`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(movie("m1", "Jaws").copy(trailerKey = "m1t"), movie("m2", "Heat"))

        s.sync.sync()

        assertEquals(mapOf("Jaws" to "m1t", "Heat" to null), dao.newestUnwatchedMovies(10).associate { it.title to it.trailerKey })
    }

    @Test
    fun `a TV section's episodes are cached with their show and their file, for the Plex Guide`() = runTest {
        val s = setup()
        s.server.items["2"] = listOf(PlexItem("s1", "show", "Harbour Lights"))
        s.server.episodes["2"] = listOf(
            PlexItem(
                "e1", "episode", "Pilot", showTitle = "Harbour Lights", season = 1, episode = 1, durationMs = 1_500_000,
                showKey = "s1", summary = "The first night.",
                media = PlexMedia("/library/parts/9201/1/file.mp4", container = "mp4", videoCodec = "h264", audioCodec = "eac3", sizeBytes = 750_000_000),
            ),
        )

        assertEquals(SyncResult.UPDATED, s.sync.sync())

        assertEquals(listOf("2"), s.server.askedEpisodes)
        val pilot = dao.item("e1")!!
        assertEquals(listOf("s1", "Harbour Lights", "/library/parts/9201/1/file.mp4", "eac3"), listOf(pilot.showKey, pilot.showTitle, pilot.partKey, pilot.audioCodec))
        assertEquals(1 to 1, pilot.season to pilot.episode)
        assertEquals("The first night.", pilot.summary)
        assertEquals(listOf(pilot), dao.playableLike(audioCodec = "eac3"))
        assertEquals(1, dao.playableCount())
    }

    @Test
    fun `a sync keeps what Phase 3 scores on, and Continue Watching's last view and show`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(
            movie("m1", "Jaws").copy(
                viewOffsetMs = 1_200_000, userRating = 8.0, lastRatedAt = 1_700_000_000, releaseDate = "1975-06-20",
                collections = listOf("Sea"), labels = listOf("Summer"), directors = listOf("Dena Varga"),
                actors = listOf("Mara Quint", "Joel Barrow"),
            ),
            movie("m2", "Heat").copy(viewOffsetMs = 0),
        )
        s.server.items["2"] = listOf(PlexItem("s1", "show", "Slow Horses", episodeCount = 24, watchedEpisodeCount = 9))
        s.server.continueWatching = listOf(
            PlexItem("5101", "episode", "Hello Darkness", showTitle = "Slow Horses", viewOffsetMs = 480_000, lastViewedAt = 1_792_990_000, showKey = "s1"),
        )

        s.sync.sync()

        val jaws = dao.item("m1")!!
        assertEquals(1_200_000L, jaws.viewOffsetMs)
        assertEquals(8.0 to 1_700_000_000L, jaws.userRating to jaws.lastRatedAt)
        assertEquals("1975-06-20", jaws.releaseDate)
        assertEquals(listOf("Sea", "Summer", "Dena Varga", "Mara Quint|Joel Barrow"), listOf(jaws.collections, jaws.labels, jaws.directors, jaws.actors))
        assertNull("no position is the same as none", dao.item("m2")!!.viewOffsetMs)
        assertEquals("", dao.item("m2")!!.actors)
        assertEquals(24 to 9, dao.item("s1")!!.let { it.episodeCount to it.watchedEpisodeCount })
        val resume = dao.continueWatching().single()
        assertEquals(1_792_990_000L to "s1", resume.lastViewedAt to resume.showKey)
    }

    /** The operator's evening (10/27/2026): a first episode, its credits from 54:00, the second cached. */
    private fun FakeServer.pilotStoppedInTheCredits(atMs: Long) {
        items["2"] = listOf(PlexItem("8800", "show", "Harbour Lights"))
        episodes["2"] = listOf(
            PlexItem("8801", "episode", "Pilot", showTitle = "Harbour Lights", season = 1, episode = 1, durationMs = 3_360_000, showKey = "8800"),
            PlexItem("8802", "episode", "Second", showTitle = "Harbour Lights", season = 1, episode = 2, durationMs = 3_300_000, showKey = "8800"),
        )
        credits["8801"] = 3_240_000
        continueWatching = listOf(
            PlexItem(
                "8801", "episode", "Pilot", showTitle = "Harbour Lights", season = 1, episode = 1, durationMs = 3_360_000,
                viewOffsetMs = atMs, lastViewedAt = 1_792_990_000, showKey = "8800", showArt = "/library/metadata/8800/art/1",
            ),
            PlexItem("7303", "movie", "Arrival", durationMs = 6_960_000, viewOffsetMs = 6_800_000),
            PlexItem("6202", "episode", "Sundae", showTitle = "The Bear", season = 2, episode = 3, showKey = "6000"),
        )
    }

    @Test
    fun `an episode Plex still lists past its credits makes way for the next one, and a finished film leaves`() = runTest {
        val s = setup()
        s.server.pilotStoppedInTheCredits(atMs = 3_300_000)
        val lines = mutableListOf<String>()
        val sync = PlexSync(s.store, s.accounts, { srv -> PlexServers.candidates(srv).first() }, s.server, dao, clock = { now }, log = { lines += it })
        // The first sync caches the episodes; the next one finds the second in the cache.
        sync.sync()
        now += 31 * 60_000

        assertEquals(SyncResult.UPDATED, sync.sync())

        val upNext = dao.continueWatching()
        assertEquals(listOf("8802", "6202"), upNext.map { it.ratingKey })
        assertEquals(listOf(0, 1), upNext.map { it.position })
        val second = upNext.first()
        assertNull("not started", second.viewOffsetMs)
        assertEquals("Harbour Lights" to "/library/metadata/8800/art/1", second.showTitle to second.showArt)
        assertEquals(1_792_990_000L to "8800", second.lastViewedAt to second.showKey)
        assertEquals("credits are asked for what's part-way in, once a sync", listOf("8801", "7303"), s.server.creditsAsked.last())
        assertTrue(lines.last { it.startsWith("plex sync: updated") }, lines.any { "2 continue watching (1 with a credits marker, 2 finished that Plex still lists)" in it })
    }

    @Test
    fun `part-way in is Up Next as Plex lists it`() = runTest {
        val s = setup()
        // Where the 10/27 sync saw the pilot: 42 minutes in, about 25% left.
        s.server.pilotStoppedInTheCredits(atMs = 2_520_000)
        s.server.continueWatching = s.server.continueWatching.take(1)

        s.sync.sync()

        val pilot = dao.continueWatching().single()
        assertEquals("8801" to 2_520_000L, pilot.ratingKey to pilot.viewOffsetMs)
    }

    @Test
    fun `a credits request the server rejects still syncs Continue Watching, by the played point alone`() = runTest {
        val s = setup()
        s.server.pilotStoppedInTheCredits(atMs = 3_300_000)
        s.server.creditsFail = true

        assertEquals(SyncResult.UPDATED, s.sync.sync())

        // 98% of the pilot is past Plex's 90% anyway; the second isn't cached yet on a first sync.
        assertEquals(listOf("6202"), dao.continueWatching().map { it.ratingKey })
        assertNull(dao.syncState()!!.error)
    }

    @Test
    fun `nothing part-way in asks for no credits`() = runTest {
        val s = setup()

        s.sync.sync()

        assertTrue(s.server.creditsAsked.isEmpty())
    }

    @Test
    fun `movies' and shows' full tags come from their details, 50 at a time, and nothing else changes`() = runTest {
        val s = setup()
        val keys = (1..120).map { "m%03d".format(it) }
        s.server.items["1"] = keys.map {
            movie(it, "Movie $it").copy(genres = listOf("Drama"), actors = listOf("A", "B", "C"), media = PlexMedia("/library/parts/$it/file.mkv"))
        }
        s.server.items["2"] = listOf(PlexItem("s1", "show", "Harbour Lights"))
        s.server.episodes["2"] = listOf(PlexItem("e1", "episode", "Pilot", showKey = "s1"))
        (keys + "s1").forEach {
            s.server.details[it] = PlexItem(
                it, "movie", "Details", genres = listOf("Drama", "Crime", "Thriller"), labels = listOf("Halloween"),
                collections = listOf("Heists"), directors = listOf("Dena Varga"), actors = ('A'..'L').map(Char::toString),
            )
        }

        assertEquals(SyncResult.UPDATED, s.sync.sync())

        assertEquals("movies and shows only, never-filled first", listOf(50, 50, 21), s.server.detailsAsked.map { it.size })
        assertEquals("m001", s.server.detailsAsked.first().first())
        val first = dao.item("m001")!!
        assertEquals("Drama|Crime|Thriller", first.genres)
        assertEquals(listOf("Halloween", "Heists", "Dena Varga"), listOf(first.labels, first.collections, first.directors))
        assertEquals("the top-billed 10", ('A'..'J').joinToString("|"), first.actors)
        assertEquals(now, first.detailsAt)
        assertEquals("a file's details aren't touched", "/library/parts/m001/file.mkv", first.partKey)
        assertEquals("Movie m001", first.title)
        assertNull("episodes use their show's tags", dao.item("e1")!!.detailsAt)
    }

    @Test
    fun `filled tags outlast the listing while a title is unchanged, a changed one is filled again, and all after a week`() = runTest {
        val s = setup()
        fun listed(key: String, updatedAt: Long) = movie(key, key).copy(updatedAt = updatedAt, genres = listOf("Drama"))
        s.server.items["1"] = listOf(listed("m1", 100), listed("m2", 100))
        listOf("m1", "m2").forEach { s.server.details[it] = PlexItem(it, "movie", it, genres = listOf("Crime", "Drama")) }
        s.sync.sync()
        assertEquals(listOf("m1", "m2"), s.server.detailsAsked.single())

        // An incremental sync sees m2 changed: it takes the listing's tags, then is filled again.
        now += 31 * 60_000
        s.server.items["1"] = listOf(listed("m1", 100), listed("m2", now / 1_000))
        s.sync.sync()
        assertEquals(listOf("m2"), s.server.detailsAsked.last())

        // The daily full sync lists both again, unchanged: their tags stay, and nothing is fetched.
        now += PlexSync.FULL_SYNC_MILLIS
        s.sync.sync()
        assertEquals(2, s.server.detailsAsked.size)
        assertEquals("Crime|Drama", dao.item("m1")!!.genres)

        now += PlexSync.DETAILS_REFRESH_MILLIS
        s.sync.sync()
        assertEquals(setOf("m1", "m2"), s.server.detailsAsked.last().toSet())
    }

    @Test
    fun `a studio is the listing's, or the details' when the listing has none, and outlasts the next listing`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(
            movie("m1", "Arrival").copy(updatedAt = 100, studio = "Northlight Pictures"),
            movie("m2", "Jaws").copy(updatedAt = 100),
        )
        s.server.items["2"] = listOf(PlexItem("s1", "show", "Harbor Lights", updatedAt = 100))
        s.server.details["m1"] = PlexItem("m1", "movie", "Arrival", updatedAt = 100)
        s.server.details["m2"] = PlexItem("m2", "movie", "Jaws", updatedAt = 100, studio = "Shoreline Films")
        s.server.details["s1"] = PlexItem("s1", "show", "Harbor Lights", updatedAt = 100, studio = "Tidewater Television")

        s.sync.sync()

        assertEquals("details without one keep the listing's", "Northlight Pictures", dao.item("m1")!!.studio)
        assertEquals("Shoreline Films", dao.item("m2")!!.studio)
        assertEquals("Tidewater Television", dao.item("s1")!!.studio)

        // The daily full sync lists them again, unchanged and without a studio: the details' stay.
        now += PlexSync.FULL_SYNC_MILLIS
        s.sync.sync()
        assertEquals(listOf("Northlight Pictures", "Shoreline Films", "Tidewater Television"), listOf("m1", "m2", "s1").map { dao.item(it)!!.studio })
        assertEquals("nothing fetched again", 1, s.server.detailsAsked.size)
    }

    @Test
    fun `a cache with no full sync on record (as 5 to 6 leaves it) reads every title again and fills studios`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(movie("m1", "Arrival").copy(updatedAt = 100))
        s.sync.sync()
        assertEquals("", dao.item("m1")!!.studio)
        // What the migration does: the last full sync is forgotten, the incremental mark stays.
        dao.saveSyncState(dao.syncState()!!.copy(lastFullAt = null))
        s.server.items["1"] = listOf(movie("m1", "Arrival").copy(updatedAt = 100, studio = "Northlight Pictures"))
        now += 31 * 60_000

        s.sync.sync()

        assertNull("a full listing: an unchanged title is read too", s.server.asked.last { it.first == "1" }.second)
        assertEquals("Northlight Pictures", dao.item("m1")!!.studio)
        assertEquals(now, dao.syncState()!!.lastFullAt)
    }

    @Test
    fun `a logo is the listing's, or the details' when the listing has none, and outlasts the next listing`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(
            movie("m1", "Arrival").copy(updatedAt = 100, logo = "/library/metadata/m1/clearLogo/1"),
            movie("m2", "Jaws").copy(updatedAt = 100),
        )
        s.server.items["2"] = listOf(PlexItem("s1", "show", "Harbor Lights", updatedAt = 100))
        s.server.details["m1"] = PlexItem("m1", "movie", "Arrival", updatedAt = 100)
        s.server.details["m2"] = PlexItem("m2", "movie", "Jaws", updatedAt = 100, logo = "/library/metadata/m2/clearLogo/2")
        s.server.details["s1"] = PlexItem("s1", "show", "Harbor Lights", updatedAt = 100, logo = "/library/metadata/s1/clearLogo/3")

        s.sync.sync()

        assertEquals("details without one keep the listing's", "/library/metadata/m1/clearLogo/1", dao.item("m1")!!.logo)
        assertEquals("/library/metadata/m2/clearLogo/2", dao.item("m2")!!.logo)
        assertEquals("/library/metadata/s1/clearLogo/3", dao.item("s1")!!.logo)

        // The daily full sync lists them again, unchanged and without a logo: the details' stay.
        now += PlexSync.FULL_SYNC_MILLIS
        s.sync.sync()
        assertEquals(
            listOf("/library/metadata/m1/clearLogo/1", "/library/metadata/m2/clearLogo/2", "/library/metadata/s1/clearLogo/3"),
            listOf("m1", "m2", "s1").map { dao.item(it)!!.logo },
        )
        assertEquals("nothing fetched again", 1, s.server.detailsAsked.size)
    }

    @Test
    fun `a failed details request is left for the next sync, and never fails this one`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(movie("m1", "Jaws").copy(genres = listOf("Thriller")))
        s.server.details["m1"] = PlexItem("m1", "movie", "Jaws", genres = listOf("Thriller", "Adventure"))
        s.server.detailsFail = true

        assertEquals(SyncResult.UPDATED, s.sync.sync())
        assertEquals("Thriller", dao.item("m1")!!.genres)
        assertNull(dao.syncState()!!.error)

        s.server.detailsFail = false
        now += 31 * 60_000
        s.sync.sync()

        assertEquals("Thriller|Adventure", dao.item("m1")!!.genres)
    }

    @Test
    fun `the library check logs after a full sync only, and never fails it`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(movie("m1", "Jaws").copy(actors = listOf("Mara Quint")))
        val lines = mutableListOf<String>()
        val broken = object : PlexServerApi by s.server {
            override suspend fun metadata(access: ServerAccess, ratingKey: String): PlexItem? = error("details broke")
        }
        val sync = PlexSync(s.store, s.accounts, { srv -> PlexServers.candidates(srv).first() }, broken, dao, clock = { now }, log = { lines += it })

        assertEquals(SyncResult.UPDATED, sync.sync())
        val checked = lines.filter { it.startsWith("library check:") }
        assertTrue(checked.toString(), checked.first().startsWith("library check: 1 items:"))
        assertEquals("library check: IllegalStateException", checked.last())
        assertTrue("never the token", lines.none { "server-token" in it })

        now += 31 * 60_000
        lines.clear()
        sync.sync()

        assertTrue("an incremental sync doesn't check", lines.none { it.startsWith("library check:") })
    }

    @Test
    fun `later syncs ask only for changes, and history marks a movie watched`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(movie("m1", "Jaws"), movie("m2", "Heat"))
        s.server.history = listOf(PlexPlay("h1", "m2", "movie", "Heat", 1_792_900_000, null))
        s.sync.sync()
        val firstRun = now / 1_000
        now += 31 * 60_000
        s.server.history = listOf(PlexPlay("h9", "m1", "movie", "Jaws", now / 1_000, null))

        s.sync.sync()

        assertEquals("1" to firstRun - 60, s.server.asked.last { it.first == "1" })
        assertEquals("history starts just after the newest play it has", 1_792_900_001L, s.server.historyAsked.last())
        assertTrue(dao.newestUnwatchedMovies(10).isEmpty())
    }

    @Test
    fun `once a day a full sync drops what's gone from the server`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(movie("m1", "Jaws"), movie("m2", "Heat"))
        s.sync.sync()
        s.server.items["1"] = listOf(movie("m1", "Jaws"))
        now += PlexSync.FULL_SYNC_MILLIS

        s.sync.sync()

        assertEquals(listOf("Jaws"), dao.newestUnwatchedMovies(10).map { it.title })
    }

    @Test
    fun `when the old address stops answering, plex_tv's new one is used`() = runTest {
        val s = setup()
        s.accounts.servers = listOf(basement("https://new"))
        s.server.down = true
        var calls = 0
        val flaky = object : PlexServerApi by s.server {
            override suspend fun continueWatching(access: ServerAccess, count: Int): List<PlexItem> {
                calls++
                if (access.baseUri == "https://old") throw PlexException.Unreachable(IOException("moved"))
                s.server.down = false
                return s.server.continueWatching(access, count)
            }
        }
        val sync = PlexSync(s.store, s.accounts, { srv -> PlexServers.candidates(srv).first() }, flaky, dao, clock = { now }, log = {})

        assertEquals(SyncResult.UPDATED, sync.sync())

        assertEquals("https://new", s.store.link.first().server?.uri)
        assertEquals(2, calls)
    }

    @Test
    fun `offline keeps the cache and says so`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(movie("m1", "Jaws"))
        s.sync.sync()
        s.server.down = true
        now += 31 * 60_000

        assertEquals(SyncResult.OFFLINE, s.sync.sync())

        assertEquals(1, dao.itemCount())
        assertEquals(PlexSync.OFFLINE, dao.syncState()?.error)
        assertEquals(now, dao.syncState()?.lastAttemptAt)
    }

    @Test
    fun `a revoked account signs out and empties the cache`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(movie("m1", "Jaws"))
        s.sync.sync()
        s.server.down = true
        s.accounts.revoked = true
        now += 31 * 60_000

        assertEquals(SyncResult.SIGNED_OUT, s.sync.sync())

        assertFalse(s.store.link.first().signedIn)
        assertEquals(0, dao.itemCount())
    }

    @Test
    fun `nothing to sync until a server is linked, and stale after 30 minutes`() = runTest {
        val unlinked = setup(linked = false)
        assertEquals(SyncResult.NOT_LINKED, unlinked.sync.sync())
        assertFalse(unlinked.sync.needsSync())

        val s = setup()
        assertTrue(s.sync.needsSync())
        s.sync.sync()
        assertFalse(s.sync.needsSync())
        now += PlexSync.STALE_AFTER_MILLIS
        assertTrue(s.sync.needsSync())
    }

    @Test
    fun `back home, a minute-old cache is stale (the Up Next credits fix)`() = runTest {
        assertFalse(setup(linked = false).sync.needsSyncBackHome())
        val s = setup()
        s.sync.sync()

        // 10/27/2026: the sync ran mid-episode, and Home came back 20 minutes later.
        now += 20 * 60_000
        assertFalse("not stale for the 30-minute schedule", s.sync.needsSync())
        assertTrue(s.sync.needsSyncBackHome())

        s.sync.sync()
        now += PlexSync.BACK_HOME_STALE_MILLIS - 1
        assertFalse("back again within the minute", s.sync.needsSyncBackHome())
    }

    @Test
    fun `a request the server rejects doesn't stop the rest, and says which one it was`() = runTest {
        val s = setup()
        s.server.continueWatchingFails = true
        s.server.items["1"] = listOf(movie("m1", "Jaws"))

        assertEquals(SyncResult.FAILED, s.sync.sync())

        assertEquals(1, dao.itemCount())
        val state = dao.syncState()!!
        assertEquals("/hubs/continueWatching answered 404", state.error)
        assertNull("a partial sync doesn't move the incremental mark", state.itemsUpdatedSince)
        assertNull(state.lastSuccessAt)
    }

    @Test
    fun `two syncs at once run one after the other, and the second has nothing left to do`() = runTest {
        val s = setup()
        s.server.items["1"] = listOf(movie("m1", "Jaws"))

        val results = listOf(async { s.sync.sync() }, async { s.sync.sync() }).awaitAll()

        assertEquals(listOf(SyncResult.UPDATED, SyncResult.UPDATED), results)
        assertEquals(listOf("1" to null, "2" to null), s.server.asked)
    }

    @Test
    fun `running holds while a sync and the work after it run, and drops once it's done or stopped`() = runTest {
        var after = CompletableDeferred<Unit>()
        val s = setup(afterSync = { after.await() })
        assertFalse(s.sync.running.first())

        val run = async { s.sync.sync() }
        s.sync.running.first { it }
        after.complete(Unit)
        assertEquals(SyncResult.UPDATED, run.await())
        assertFalse(s.sync.running.first())

        after = CompletableDeferred()
        val stopped = async { s.sync.sync() }
        s.sync.running.first { it }
        stopped.cancel()
        stopped.join()
        assertFalse("a cancelled run doesn't leave Updating up", s.sync.running.first())
    }

    @Test
    fun `nothing linked, nothing running`() = runTest {
        val s = setup(linked = false)

        assertEquals(SyncResult.NOT_LINKED, s.sync.sync())
        assertFalse(s.sync.running.first())
    }
}
