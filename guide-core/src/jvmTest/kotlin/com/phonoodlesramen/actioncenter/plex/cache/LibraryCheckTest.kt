package com.phonoodlesramen.actioncenter.plex.cache

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexPage
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import com.phonoodlesramen.actioncenter.plex.PlexSection
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCheckTest {
    private val db = Room.inMemoryDatabaseBuilder<PlexDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()
    private val dao = db.dao()
    private val access = ServerAccess("https://basement", "server-token")

    @After
    fun tearDown() = db.close()

    /** Answers only a title's details, from [details]; a missing key has none. */
    private class Details(val details: Map<String, PlexItem>) : PlexServerApi {
        val asked = mutableListOf<String>()
        override suspend fun continueWatching(access: ServerAccess, count: Int) = emptyList<PlexItem>()
        override suspend fun sections(access: ServerAccess) = emptyList<PlexSection>()
        override suspend fun items(access: ServerAccess, section: String, type: Int, start: Int, size: Int, updatedSince: Long?) =
            PlexPage(emptyList<PlexItem>(), start, 0)
        override suspend fun history(access: ServerAccess, sinceSeconds: Long, accountId: Int, size: Int) = emptyList<PlexPlay>()
        override suspend fun metadata(access: ServerAccess, ratingKey: String): PlexItem? {
            asked += ratingKey
            return details[ratingKey]
        }
    }

    private suspend fun cache(vararg items: PlexItem) = dao.upsertItems(items.map { it.toEntity("1", 0) })

    @Test
    fun `it compares the titles with the most cast with their details, and says which lists are short`() = runTest {
        val listedHeat = PlexItem(
            "m1", "movie", "Heat", genres = listOf("Crime", "Drama"), actors = listOf("A", "B", "C"), directors = listOf("D"),
            collections = listOf("Heists"),
        )
        cache(
            listedHeat,
            PlexItem("m2", "movie", "Jaws", genres = listOf("Thriller"), actors = listOf("E")),
            PlexItem("m3", "movie", "No cast"),
            PlexItem("s1", "show", "Slow Horses", actors = listOf("F", "G")),
        )
        val server = Details(
            mapOf(
                "m1" to listedHeat.copy(genres = listOf("Crime", "Drama", "Thriller"), actors = ('A'..'L').map(Char::toString)),
                "m2" to PlexItem("m2", "movie", "Jaws", genres = listOf("Thriller"), actors = listOf("E")),
            ),
        )
        val lines = mutableListOf<String>()

        LibraryCheck(server, dao, lines::add).run(access)

        assertEquals("most cast first; titles with none aren't compared", listOf("m1", "m2", "s1"), server.asked)
        assertTrue(
            "cast compares up to the 10 kept; the cache hasn't been filled yet",
            "library check: m1 movie (not filled), cache/details: genres 2/3, cast 3/10, directors 1/1, collections 1/1, labels 0/0" in lines,
        )
        assertTrue(lines.toString(), "library check: s1 show (not filled): no details" in lines)
        assertEquals(
            "library check: short in the cache: genres 1 of 2, cast 1 of 2, directors 0 of 2, collections 0 of 2, labels 0 of 2",
            lines.last(),
        )
        assertTrue("never the token or a title", lines.none { "server-token" in it || "Heat" in it })
    }

    @Test
    fun `it counts the fields scoring needs, and whether last viewed moves on a partial play`() = runTest {
        cache(
            PlexItem("m1", "movie", "Rated", userRating = 8.0, releaseDate = "2001-01-01", collections = listOf("C"), labels = listOf("L"), studio = "S"),
            PlexItem("m2", "movie", "Half seen", viewOffsetMs = 600_000, lastViewedAt = 1_790_000_000, directors = listOf("D")),
            PlexItem("m3", "movie", "Half seen, no date", viewOffsetMs = 600_000),
            PlexItem("m4", "movie", "Rewatching", viewCount = 1, viewOffsetMs = 900_000, lastViewedAt = 1_792_000_000),
            PlexItem("s1", "show", "Counted", episodeCount = 10, watchedEpisodeCount = 3, logo = "/library/metadata/s1/clearLogo/1"),
            PlexItem("s2", "show", "Not counted", genres = listOf("Drama", "Crime", "Comedy")),
        )
        dao.insertPlays(listOf(PlayEntity("h1", "m4", "movie", "Rewatching", 1_780_000_000, null)))
        dao.replaceContinueWatching(
            listOf(PlexItem("e1", "episode", "Pilot", lastViewedAt = 1_791_000_000).toContinue(0), PlexItem("e2", "episode", "Two").toContinue(1)),
        )
        val lines = mutableListOf<String>()

        LibraryCheck(Details(emptyMap()), dao, lines::add).run(access)

        assertEquals(
            "library check: 6 items: 3 with a position, 1 rated by you, 1 with a release date, 1 in a collection, 1 labelled, 1 with a studio, 1 with a logo, " +
                "1 with directors, 0 with cast, 1 of 2 shows with episode counts, 0 of 6 movies and shows filled from details; " +
                "the most any title has: 3 genres, 0 cast, 1 directors",
            lines[0],
        )
        assertEquals(
            "library check: last viewed: 1 of 2 unfinished titles have one; 1 of 1 rewatches in progress were viewed after " +
                "their last finish; 1 of 2 in Continue Watching",
            lines[1],
        )
        assertEquals("library check: labels on the most titles: L 1", lines[2])
        assertEquals("library check: no titles to compare", lines.last())
    }

    @Test
    fun `it names the labels on the most titles, so a tool's tags stand out`() {
        val lists = List(1_200) { "radarr" } + listOf("radarr|Halloween", "Halloween", "4K| Halloween ", "4K")
        assertEquals("labels on the most titles: radarr 1,201, Halloween 3, 4K 2", LibraryCheck.commonLabels(lists))
        assertEquals("no labels", LibraryCheck.commonLabels(emptyList()))
    }
}
