package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.guide.ChannelRule.Companion.EPISODE
import com.phonoodlesramen.actioncenter.guide.ChannelRule.Companion.MOVIE
import com.phonoodlesramen.actioncenter.guide.Fixtures.episode
import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.show
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Channel rules run as queries on the Plex cache. */
class GuideRulesTest {
    private val harness = GuideHarness()
    private val library = GuideLibrary(harness.plex.guideLibrary())
    private val year = 2026

    private val nightShift = show("ns", "Night Shift", listOf("Drama", "Crime"), year = 2012)
    private val kitchen = show("kt", "Kitchen Table", listOf("Comedy"), year = 2020, rating = "TV-PG")

    private val items = listOf(
        movie("m1", "Big Atlas", 120, listOf("Action", "Adventure"), year = 2010),
        movie("m2", "Glowing Reef", 96, listOf("Family", "Animation"), year = 1995, rating = "PG", views = 2),
        movie("m3", "Quiet Field", 131, listOf("Drama"), year = 1987, rating = "R"),
        movie("m4", "Paper Harbor", 89, listOf("Comedy"), year = 2001, rating = "PG", file = false),
        movie("m5", "Cold Star Relay", 45, listOf("Sci_Fi"), year = 2019),
        movie("m6", "The Ninth Moon", 101, listOf("SciXFi"), year = 2019),
        movie("m7", "Blink", 0, listOf("Drama")),
        nightShift,
        episode(nightShift, 1, 1, 44),
        episode(nightShift, 1, 2, 44, views = 1),
        episode(nightShift, 2, 1, 44),
        episode(nightShift, 0, 1, 30),
        kitchen,
        episode(kitchen, 1, 1, 22),
        episode(kitchen, 1, 2, 22, file = false),
        episode(kitchen, 1, 3, 22, rating = "TV-14"),
    )

    /** [items] with studios and labels (Phase 9). */
    private val studiosAndLabels = items.map {
        when (it.ratingKey) {
            "m1" -> it.copy(studio = "Northlight", labels = "Favourites|Rewatch")
            "m2" -> it.copy(studio = "Reef Animation", labels = "Kids")
            "m3" -> it.copy(studio = "Northlight", labels = "Favourites")
            // No file: never offered, never scheduled.
            "m4" -> it.copy(studio = "Lost Studio", labels = "Lost")
            "m5" -> it.copy(studio = "northlight")
            "ns" -> it.copy(studio = "Northlight", labels = "Favourites")
            "kt" -> it.copy(studio = "Tidewater", labels = "Kids")
            // An episode's own don't count: its show's do.
            "kt-s01e01" -> it.copy(studio = "Northlight", labels = "Favourites")
            else -> it
        }
    }

    @After
    fun tearDown() = harness.close()

    private suspend fun keys(rule: ChannelRule) = library.pool(rule, year).map { it.ratingKey }.toSet()

    @Test
    fun `only movies and episodes with a file and a length are scheduled`() = runTest {
        harness.synced(items)

        assertEquals(setOf("m1", "m2", "m3", "m5", "m6"), keys(ChannelRule()))
        assertEquals(
            setOf("m1", "m2", "m3", "m5", "m6", "ns-s01e01", "ns-s01e02", "ns-s02e01", "kt-s01e01", "kt-s01e03"),
            keys(ChannelRule(types = listOf(MOVIE, EPISODE))),
        )
    }

    @Test
    fun `genres match any of them, an episode by its show, ignoring case`() = runTest {
        harness.synced(items)

        assertEquals(
            setOf("m3", "ns-s01e01", "ns-s01e02", "ns-s02e01"),
            keys(ChannelRule(types = listOf(MOVIE, EPISODE), genres = listOf("drama"))),
        )
        assertEquals(setOf("m1", "m2"), keys(ChannelRule(genres = listOf("Adventure", "Family"))))
        // "_" is a letter in a genre, not a wildcard.
        assertEquals(setOf("m5"), keys(ChannelRule(genres = listOf("Sci_Fi"))))
    }

    @Test
    fun `collections match by name like genres, an episode by its show's, and are offered as choices`() = runTest {
        val tagged = items.map {
            when (it.ratingKey) {
                "m1" -> it.copy(collections = "Holiday|Atlas Saga")
                "m3" -> it.copy(collections = "Holiday")
                // No file: never offered, never scheduled.
                "m4" -> it.copy(collections = "Lost Films")
                "kt" -> it.copy(collections = "Holiday")
                else -> it
            }
        }
        harness.synced(tagged)

        assertEquals(
            setOf("m1", "m3", "kt-s01e01", "kt-s01e03"),
            keys(ChannelRule(types = listOf(MOVIE, EPISODE), collections = listOf("holiday"))),
        )
        assertEquals(setOf("m1"), keys(ChannelRule(collections = listOf("Atlas Saga"))))
        assertEquals(setOf("m3"), keys(ChannelRule(genres = listOf("Drama"), collections = listOf("Holiday"))))
        assertEquals(listOf("Atlas Saga", "Holiday"), library.choices().collections)
    }

    @Test
    fun `studios and labels match any of them, an episode by its show's, with the rest of the rule`() = runTest {
        harness.synced(studiosAndLabels)
        val both = listOf(MOVIE, EPISODE)
        val nightShiftEpisodes = setOf("ns-s01e01", "ns-s01e02", "ns-s02e01")

        assertEquals(setOf("m1", "m3") + nightShiftEpisodes, keys(ChannelRule(types = both, studios = listOf("Northlight"))))
        assertEquals("any of them", setOf("m1", "m2", "m3"), keys(ChannelRule(studios = listOf("Northlight", "Reef Animation"))))
        assertEquals("a studio is its exact name", setOf("m5"), keys(ChannelRule(studios = listOf("northlight"))))
        assertEquals(
            "an episode has its show's studio, never its own",
            setOf("kt-s01e01", "kt-s01e03"),
            keys(ChannelRule(types = listOf(EPISODE), studios = listOf("Tidewater"))),
        )
        assertEquals(nightShiftEpisodes, keys(ChannelRule(types = listOf(EPISODE), studios = listOf("Northlight"))))

        assertEquals(setOf("m1", "m3") + nightShiftEpisodes, keys(ChannelRule(types = both, labels = listOf("Favourites"))))
        assertEquals("any of them", setOf("m1", "m2"), keys(ChannelRule(labels = listOf("Kids", "Rewatch"))))
        assertEquals("a label ignores case, as a genre does", setOf("m1", "m3"), keys(ChannelRule(labels = listOf("favourites"))))
        assertEquals(
            "an episode has its show's labels, never its own",
            setOf("kt-s01e01", "kt-s01e03"),
            keys(ChannelRule(types = listOf(EPISODE), labels = listOf("Kids"))),
        )

        assertEquals(
            "with genres",
            setOf("m3") + nightShiftEpisodes,
            keys(ChannelRule(types = both, genres = listOf("Drama"), studios = listOf("Northlight"))),
        )
        assertEquals("with ratings", setOf("m2"), keys(ChannelRule(labels = listOf("Kids", "Favourites"), contentRatings = listOf("PG"))))
        assertEquals("a studio and a label", setOf("m1"), keys(ChannelRule(studios = listOf("Northlight"), labels = listOf("Rewatch"))))
        assertEquals(emptySet<String>(), keys(ChannelRule(studios = listOf("Lost Studio"))))
    }

    @Test
    fun `studios and labels are offered from what the guide can play, the most titles first`() = runTest {
        harness.synced(studiosAndLabels)

        val choices = harness.editor.choices()

        // Northlight: Big Atlas, Quiet Field and Night Shift (an episode isn't a title); Paper Harbor has no file.
        assertEquals(listOf("Northlight", "northlight", "Reef Animation", "Tidewater"), choices.studios)
        assertEquals(listOf("Favourites", "Kids", "Rewatch"), choices.labels)
    }

    @Test
    fun `age, decade, rating, length and unwatched filter the titles`() = runTest {
        harness.synced(items)

        assertEquals("25+ years old, rolling", setOf("m2", "m3"), keys(ChannelRule(minAgeYears = 25)))
        assertEquals(setOf("m2"), keys(ChannelRule(decades = listOf(1990))))
        assertEquals(setOf("m3", "m2"), keys(ChannelRule(decades = listOf(1980, 1995))))
        assertEquals(setOf("m2"), keys(ChannelRule(contentRatings = listOf("PG"))))
        assertEquals(
            "an episode without a rating has its show's",
            setOf("kt-s01e01"),
            keys(ChannelRule(types = listOf(EPISODE), contentRatings = listOf("TV-PG"))),
        )
        assertEquals(setOf("m2", "m6"), keys(ChannelRule(minMinutes = 60, maxMinutes = 110)))
        assertEquals(setOf("m1", "m3", "m5", "m6"), keys(ChannelRule(unwatched = true)))
        assertEquals(setOf("ns-s01e01", "ns-s02e01", "kt-s01e01", "kt-s01e03"), keys(ChannelRule(types = listOf(EPISODE), unwatched = true)))
    }

    @Test
    fun `sections and shows narrow it, and specials are left out unless asked for`() = runTest {
        harness.synced(items)

        assertEquals(
            setOf("ns-s01e01", "ns-s01e02", "ns-s02e01", "kt-s01e01", "kt-s01e03"),
            keys(ChannelRule(types = listOf(MOVIE, EPISODE), sections = listOf(Fixtures.TV))),
        )
        assertEquals(setOf("kt-s01e01", "kt-s01e03"), keys(ChannelRule(types = listOf(EPISODE), shows = listOf("kt"))))
        assertEquals(
            setOf("ns-s00e01", "ns-s01e01", "ns-s01e02", "ns-s02e01"),
            keys(ChannelRule(types = listOf(EPISODE), shows = listOf("ns"), specials = true)),
        )
        assertEquals(
            "shows in order use episodes only",
            setOf("kt-s01e01", "kt-s01e03"),
            keys(ChannelRule(types = listOf(MOVIE), genres = listOf("Comedy"), order = ChannelRule.Order.IN_ORDER)),
        )
    }

    @Test
    fun `a rule's size, its shows and each show's next episode in order`() = runTest {
        harness.synced(items)
        val tv = ChannelRule(types = listOf(EPISODE), order = ChannelRule.Order.ROTATE_SHOWS)

        val stats = library.stats(ChannelRule(genres = listOf("Drama", "Action")), year)
        assertEquals(2, stats.count)
        assertEquals(251 * 60_000L, stats.totalMs)
        assertEquals(listOf("kt", "ns"), library.shows(tv, year))

        val first = library.episodeAfter(tv, year, "ns", null)!!
        assertEquals("ns-s01e01", first.ratingKey)
        val second = library.episodeAfter(tv, year, "ns", EpisodePosition(1, 1, first.ratingKey))!!
        assertEquals("ns-s01e02", second.ratingKey)
        assertEquals("ns-s02e01", library.episodeAfter(tv, year, "ns", EpisodePosition(1, 2, "ns-s01e02"))!!.ratingKey)
        assertNull("after the last", library.episodeAfter(tv, year, "ns", EpisodePosition(2, 1, "ns-s02e01")))
        assertEquals(
            "a deleted episode's place still finds the next",
            "kt-s01e03",
            library.episodeAfter(tv, year, "kt", EpisodePosition(1, 2, "gone"))!!.ratingKey,
        )
    }

    @Test
    fun `rules are portable JSON that leaves out defaults and skips what it doesn't know`() {
        assertEquals("{}", ChannelRule().toJson())
        val rule = ChannelRule(types = listOf(EPISODE), genres = listOf("Drama"), minAgeYears = 25, order = ChannelRule.Order.ROTATE_SHOWS)
        assertEquals(rule, ChannelRule.fromJson(rule.toJson()))
        assertEquals(
            ChannelRule(genres = listOf("Drama"), order = ChannelRule.Order.IN_ORDER),
            ChannelRule.fromJson("""{"genres":["Drama"],"order":"in_order","dayparts":[{"from":"06:00"}]}"""),
        )
    }

    @Test
    fun `studios and labels are written only when picked, so a rule saved before them reads and seeds the same`() {
        val saved = """{"types":["episode"],"genres":["Drama"]}"""
        val before = ChannelRule.fromJson(saved)
        assertEquals(emptyList<String>(), before.studios)
        assertEquals(emptyList<String>(), before.labels)
        assertEquals("the same JSON, so the same seed", saved, before.toJson())

        val picked = ChannelRule(studios = listOf("Northlight"), labels = listOf("Favourites", "Rewatch"))
        assertEquals("""{"studios":["Northlight"],"labels":["Favourites","Rewatch"]}""", picked.toJson())
        assertEquals(picked, ChannelRule.fromJson(picked.toJson()))
    }
}
