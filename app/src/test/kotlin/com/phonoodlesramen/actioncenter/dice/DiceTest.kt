package com.phonoodlesramen.actioncenter.dice

import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.suggest.Library
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The die (spec §4): what's on the table, the dials, and the throw. Made-up titles. */
class DiceTest {
    private val now = 1_793_000_000_000L
    private val nowSeconds = now / 1_000
    private val day = 86_400L

    private fun movie(
        key: String,
        year: Int? = 1995,
        genres: String = "Comedy",
        viewCount: Int = 0,
        lastViewed: Long? = null,
        file: Boolean = true,
        offsetMs: Long? = null,
        stars: Double? = null,
        minutes: Long = 101,
    ) = ItemEntity(
        ratingKey = key, sectionKey = "1", type = "movie", title = "Film $key", year = year, durationMs = minutes * 60_000,
        viewCount = viewCount, lastViewedAt = lastViewed, addedAt = null, updatedAt = null, contentRating = "PG-13",
        audienceRating = 7.4, rating = null, thumb = null, art = "/art/$key", genres = genres, syncedAt = 0,
        partKey = if (file) "/library/parts/$key/1/file.mkv" else null, viewOffsetMs = offsetMs, userRating = stars,
    )

    private fun show(key: String, year: Int = 2019, genres: String = "Drama") = ItemEntity(
        ratingKey = key, sectionKey = "2", type = "show", title = "Show $key", year = year, durationMs = null,
        viewCount = 0, lastViewedAt = null, addedAt = null, updatedAt = null, contentRating = "TV-14",
        audienceRating = null, rating = 8.1, thumb = null, art = "/art/$key", genres = genres, syncedAt = 0,
    )

    private fun episode(show: String, season: Int, number: Int, viewCount: Int = 0, lastViewed: Long? = null) =
        EpisodeRow("$show-$season-$number", show, season, number, 48 * 60_000L, viewCount, lastViewed, null, null)

    private fun library(titles: List<ItemEntity>, episodes: List<EpisodeRow> = emptyList(), plays: List<PlayEntity> = emptyList(), cw: List<ContinueEntity> = emptyList()) =
        Library(titles, episodes, plays, cw)

    private fun deck(library: Library, notTonight: Set<String> = emptySet()) = Dice.deck(library, notTonight, now, ZoneOffset.UTC)

    @Test
    fun `films with a file and shows with an episode are on the table, but not recent, started, disliked or hidden ones aren't`() {
        val table = deck(
            library(
                listOf(
                    movie("unseen"),
                    movie("long-ago", viewCount = 1, lastViewed = nowSeconds - 3 * 365 * day),
                    movie("last-month", viewCount = 1, lastViewed = nowSeconds - 30 * day),
                    movie("no-file", file = false),
                    movie("halfway", offsetMs = 20 * 60_000),
                    movie("one-star", stars = 2.0),
                    movie("not-tonight"),
                    show("new-show"),
                    show("no-episodes"),
                    show("watching"),
                ),
                episodes = listOf(
                    episode("new-show", 1, 1),
                    episode("new-show", 1, 2),
                    episode("watching", 1, 1, viewCount = 1, lastViewed = nowSeconds - 2 * day),
                    episode("watching", 1, 2),
                ),
            ),
            notTonight = setOf("not-tonight"),
        )
        assertEquals(listOf("long-ago", "new-show", "unseen"), table.titles.map { it.ratingKey })
    }

    @Test
    fun `nothing in Continue Watching, however long ago`() {
        val cw = ContinueEntity(0, "kept", "movie", "Film kept", null, null, null, 1990, 6_000_000, 600_000, null, null, null, null, lastViewedAt = nowSeconds - 900 * day)
        val table = deck(library(listOf(movie("kept"), movie("other")), cw = listOf(cw)))
        assertEquals(listOf("other"), table.titles.map { it.ratingKey })
    }

    @Test
    fun `a show plays its next episode, and says whether it picks up or starts`() {
        val table = deck(
            library(
                listOf(show("fresh"), show("paused")),
                episodes = listOf(
                    episode("fresh", 1, 1), episode("fresh", 1, 2),
                    episode("paused", 1, 1, viewCount = 1, lastViewed = nowSeconds - 800 * day),
                    episode("paused", 1, 2, viewCount = 1, lastViewed = nowSeconds - 800 * day),
                    episode("paused", 2, 1),
                ),
            ),
        )
        val fresh = table.titles.single { it.ratingKey == "fresh" }
        assertEquals("fresh-1-1", fresh.playKey)
        assertEquals("S1 · E1", fresh.episode)
        assertFalse(fresh.picksUp)
        assertFalse(fresh.seen)
        val paused = table.titles.single { it.ratingKey == "paused" }
        assertEquals("paused-2-1", paused.playKey)
        assertTrue(paused.picksUp)
        assertTrue(paused.seen)
        assertEquals(2024, paused.lastSeenYear)
    }

    @Test
    fun `each dial counts what it would leave with the other two as they are, in an order that doesn't move`() {
        val table = deck(
            library(
                listOf(
                    movie("a", year = 1994, genres = "Comedy|Romance"),
                    movie("b", year = 1998, genres = "Comedy"),
                    movie("c", year = 2004, genres = "Horror"),
                    show("s", year = 1996, genres = "Comedy"),
                ),
                episodes = listOf(episode("s", 1, 1)),
            ),
        )
        val nineties = DiceFilters(decade = 1990)
        assertEquals(listOf(4, 3, 1), table.kinds(DiceFilters()).map { it.count })
        assertEquals(listOf(3, 2, 1), table.kinds(nineties).map { it.count })
        assertEquals(listOf("Any genre", "Comedy", "Horror", "Romance"), table.genres(nineties).map { it.label })
        assertEquals(listOf(3, 3, 0, 1), table.genres(nineties).map { it.count })
        assertEquals(listOf("Any decade", "2000s", "1990s"), table.decades(DiceFilters(genre = "comedy")).map { it.label })
        assertEquals(listOf(3, 0, 3), table.decades(DiceFilters(genre = "comedy")).map { it.count })
        assertEquals(listOf("a", "b"), table.matching(DiceFilters(DieKind.MOVIES, "Comedy", 1990)).map { it.ratingKey })
    }

    @Test
    fun `a chosen genre past the dial's end stays on it`() {
        val titles = (1..Dice.MAX_GENRES + 2).map { movie("m$it", genres = "Genre ${'@' + it}|Common") }
        val table = deck(library(titles))
        val chosen = "Genre ${'@' + Dice.MAX_GENRES + 2}"
        assertEquals(Dice.MAX_GENRES + 1, table.genres(DiceFilters()).size)
        assertEquals(chosen, table.genres(DiceFilters(genre = chosen)).last().value)
    }

    @Test
    fun `the die is loaded a little toward what you've never seen`() {
        val table = DiceDeck(listOf(DieTitle("new", "new", "movie", "New"), DieTitle("old", "old", "movie", "Old", seen = true)))
        val random = Random(7)
        val landed = (1..4_000).map { Dice.roll(table, DiceFilters(), emptySet(), 1, random)!!.title.ratingKey }
        val share = landed.count { it == "new" } / 4_000.0
        assertEquals(0.75, share, 0.03)
    }

    @Test
    fun `what's come up stays off the table until everything has, then it starts over`() {
        val table = DiceTable(read = { DiceDeck((1..3).map { DieTitle("t$it", "t$it", "movie", "Title $it") }) }, random = Random(3), today = { LocalDate.of(2026, 11, 14) })
        val deck = runBlocking { table.deck(ActiveProfile.owner("Sam"), version = 1) }
        val first = (1..3).map { table.roll("owner", deck, DiceFilters(), it)!! }
        assertEquals(setOf("t1", "t2", "t3"), first.map { it.title.ratingKey }.toSet())
        assertFalse(first.any { it.startedOver })
        val fourth = table.roll("owner", deck, DiceFilters(), 4)!!
        assertTrue(fourth.startedOver)
        assertEquals("That's all of them. Round two", fourth.line)
        // A kid's throws are their own.
        assertFalse(table.roll("kid", deck, DiceFilters(), 1)!!.startedOver)
    }

    @Test
    fun `the table is read once per profile, sync and day, and a failed read is an empty table`() {
        var reads = 0
        var fail = false
        val table = DiceTable(read = { reads++; if (fail) error("cache gone") else DiceDeck(listOf(DieTitle("x", "x", "movie", "X"))) })
        val you = ActiveProfile.owner("Sam")
        runBlocking {
            table.deck(you, 1)
            table.deck(you, 1)
            assertEquals(1, reads)
            table.deck(you, 2)
            assertEquals(2, reads)
            fail = true
            assertTrue(table.deck(you.copy(name = "Alex"), 2).isEmpty)
        }
    }

    @Test
    fun `nothing fits, nothing rolls`() {
        val table = DiceDeck(listOf(DieTitle("m", "m", "movie", "M", year = 1999)))
        assertNull(Dice.roll(table, DiceFilters(decade = 1950), emptySet(), 1, Random(1)))
        val only = Dice.roll(table, DiceFilters(), emptySet(), 1, Random(1))
        assertNotNull(only)
        assertEquals("The only one that fits", only!!.line)
        assertTrue(only.face in 1..6)
    }

    @Test
    fun `the eyebrow has a little more to say each time you roll again`() {
        val title = DieTitle("m", "m", "movie", "M")
        assertEquals(
            listOf(
                "The die has spoken", "Rolled again", "Third time's the charm", "Still deciding?",
                "The die is getting dizzy", "The die is getting dizzy", "Roll 7. The die believes in you",
            ),
            (1..7).map { DieRoll(title, 3, it, pool = 10).line },
        )
    }

    @Test
    fun `the card's lines say what it is, whether it's new to you, and when it'd be over`() {
        val evening = LocalDateTime.of(2026, 11, 14, 20, 30)
        val film = DieTitle("m", "m", "movie", "M", year = 1995, genres = listOf("Crime", "Drama", "Thriller"), contentRating = "R", durationMs = 170 * 60_000L + 5_000)
        assertEquals("1995 · R · 2h 50m · Crime, Drama", DieLines.meta(film))
        assertEquals("New to you · over by 11:21 PM", DieLines.detail(film, evening))
        assertEquals("Last seen in 2019 · over by 11:21 PM", DieLines.detail(film.copy(seen = true, lastSeenYear = 2019), evening))
        val series = DieTitle("s", "e", "show", "S", year = 2019, genres = listOf("Comedy"), contentRating = "TV-14", durationMs = 22 * 60_000L, episode = "S1 · E1")
        assertEquals("2019 · TV-14 · 22m episodes · Comedy", DieLines.meta(series))
        assertEquals("New to you · starts at S1 · E1 · over by 8:52 PM", DieLines.detail(series, evening))
        assertEquals("Picks up at S3 · E2 · over by 8:52 PM", DieLines.detail(series.copy(episode = "S3 · E2", seen = true, picksUp = true), evening))
        assertEquals("1h", DieLines.length(60 * 60_000L))
        assertNull(DieLines.length(0))
    }
}
