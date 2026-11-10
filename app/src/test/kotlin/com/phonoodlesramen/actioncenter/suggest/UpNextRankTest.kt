package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up titles. Now is Monday 26 October 2026, 8:41 PM: a weeknight evening. */
class UpNextRankTest {
    private val zone = ZoneOffset.UTC
    private val monday = LocalDateTime.of(2026, 10, 26, 20, 41)

    private fun seconds(time: LocalDateTime) = time.toEpochSecond(zone)

    /** Epoch seconds at [hour] o'clock, [days] before Monday. */
    private fun daysAgo(days: Long, hour: Int = 20) = seconds(monday.toLocalDate().minusDays(days).atTime(hour, 0))

    private fun film(position: Int, key: String, lastViewed: Long?, watchedMinutes: Long = 50) = ContinueEntity(
        position, key, "movie", key, null, null, null, 2020, 100 * 60_000L, watchedMinutes * 60_000, null, null, null, null,
        lastViewedAt = lastViewed,
    )

    /** A show's next episode in Continue Watching: no position yet. */
    private fun nextUp(position: Int, key: String, show: String?, lastViewed: Long?) = ContinueEntity(
        position, key, "episode", key, show, 1, 1, null, 30 * 60_000L, null, null, null, null, null,
        lastViewedAt = lastViewed, showKey = show,
    )

    private fun watched(key: String, show: String, number: Int, at: Long) = EpisodeRow(key, show, 1, number, 30 * 60_000L, 1, at, null, null)

    private fun unwatched(key: String, show: String, number: Int) = EpisodeRow(key, show, 1, number, 30 * 60_000L, 0, null, null, null)

    /** Each watched episode's play in history. */
    private fun playsOf(episodes: List<EpisodeRow>) =
        episodes.filter { it.viewCount > 0 }.map { PlayEntity("${it.ratingKey}@${it.lastViewedAt}", it.ratingKey, "episode", it.ratingKey, it.lastViewedAt!!, null) }

    private fun rank(
        continueWatching: List<ContinueEntity>,
        episodes: List<EpisodeRow> = emptyList(),
        plays: List<PlayEntity> = playsOf(episodes),
        hides: Hides = Hides(),
        at: LocalDateTime = monday,
    ): List<Picked> {
        val library = Library(emptyList(), episodes, plays, continueWatching)
        return UpNextRank.rank(RunInput(library, emptyList(), hides, now = seconds(at) * 1_000, zone = zone))
    }

    @Test
    fun `momentum beats a slightly more recent one-off`() {
        val oneOff = film(0, "paper-moons", lastViewed = daysAgo(1, hour = 21))
        val binge = nextUp(1, "glasshouse-6", "glasshouse", lastViewed = daysAgo(2, hour = 9))
        // Four episodes in the last week, in the mornings, so habit stays out of it.
        val episodes = (2..5).map { watched("glasshouse-$it", "glasshouse", it, daysAgo(7L - it, hour = 9)) } +
            unwatched("glasshouse-6", "glasshouse", 6)

        val ranked = rank(listOf(oneOff, binge), episodes)

        assertEquals(listOf("glasshouse-6", "paper-moons"), ranked.map { it.ratingKey })
        assertEquals("4 episodes this week", ranked.first().reason)
        assertEquals(
            "without those episodes the more recent film leads",
            listOf("paper-moons", "glasshouse-6"),
            rank(listOf(oneOff, binge)).map { it.ratingKey },
        )
    }

    @Test
    fun `a stale item sinks below every fresh one, and the stale keep their own order`() {
        // Lantern was a weeknight-evening habit (Monday 7 to Wednesday 9 September), so it
        // outscores Orchard; both it and the half-watched film are 30+ days old.
        val episodes = (1..3).map { watched("lantern-$it", "lantern", it, daysAgo(50L - it)) } +
            unwatched("lantern-4", "lantern", 4) +
            watched("orchard-1", "orchard", 1, daysAgo(20)) + unwatched("orchard-2", "orchard", 2)
        val continueWatching = listOf(
            film(0, "old-film", lastViewed = daysAgo(40)),
            nextUp(1, "lantern-4", "lantern", lastViewed = daysAgo(45)),
            nextUp(2, "orchard-2", "orchard", lastViewed = daysAgo(20)),
        )

        val ranked = rank(continueWatching, episodes, plays = playsOf(episodes.filter { it.showKey == "lantern" }))

        assertEquals(listOf("orchard-2", "lantern-4", "old-film"), ranked.map { it.ratingKey })
    }

    @Test
    fun `a habit at this hour, on this kind of day, reorders`() {
        // Both finished three episodes on Monday 5 to Wednesday 7 October (no momentum now),
        // Dawn in the mornings and Dusk in the evenings, and both were last watched 3 days ago.
        fun show(name: String, hour: Int) = (1..3).map { watched("$name-$it", name, it, daysAgo(18L + it, hour)) } + unwatched("$name-4", name, 4)
        val episodes = show("dawn", hour = 9) + show("dusk", hour = 20)
        val continueWatching = listOf(nextUp(0, "dawn-4", "dawn", daysAgo(3)), nextUp(1, "dusk-4", "dusk", daysAgo(3)))

        val weeknight = rank(continueWatching, episodes)
        assertEquals(listOf("dusk-4", "dawn-4"), weeknight.map { it.ratingKey })
        assertEquals("You usually watch this in the evening", weeknight.first().reason)

        val saturday = rank(continueWatching, episodes, at = LocalDateTime.of(2026, 10, 24, 20, 41))
        assertEquals("a weekend evening isn't a weeknight", listOf("dawn-4", "dusk-4"), saturday.map { it.ratingKey })
    }

    @Test
    fun `plays after midnight count for the night before`() {
        // Owl's plays are at 1 AM on Saturdays 19 September to 3 October: Friday nights, late.
        val owl = (1..3).map { watched("owl-$it", "owl", it, seconds(LocalDate.of(2026, 9, 19).plusWeeks(it - 1L).atTime(1, 0))) } +
            unwatched("owl-4", "owl", 4)
        val continueWatching = listOf(nextUp(0, "lark-2", "lark", daysAgo(5)), nextUp(1, "owl-4", "owl", daysAgo(5)))

        val ranked = rank(continueWatching, owl, at = LocalDateTime.of(2026, 10, 23, 23, 30))

        assertEquals(listOf("owl-4", "lark-2"), ranked.map { it.ratingKey })
        assertEquals("You usually watch this late at night", ranked.first().reason)
    }

    @Test
    fun `the bands of the day, and the small hours belong to the night before`() {
        fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
        assertEquals(UpNextRank.Band.LATE, UpNextRank.band(at(26, 4, 59)))
        assertEquals(UpNextRank.Band.MORNING, UpNextRank.band(at(26, 5)))
        assertEquals(UpNextRank.Band.AFTERNOON, UpNextRank.band(at(26, 12)))
        assertEquals(UpNextRank.Band.EVENING, UpNextRank.band(at(26, 22, 29)))
        assertEquals(UpNextRank.Band.LATE, UpNextRank.band(at(26, 22, 30)))

        // Friday 23 to Sunday 25 October.
        assertTrue("Friday evening", UpNextRank.weekendNight(at(23, 20)))
        assertTrue("1 AM Saturday is Friday night", UpNextRank.weekendNight(at(24, 1)))
        assertTrue("1 AM Sunday is Saturday night", UpNextRank.weekendNight(at(25, 1)))
        assertFalse("Sunday evening", UpNextRank.weekendNight(at(25, 20)))
        assertFalse("1 AM Friday is Thursday night", UpNextRank.weekendNight(at(23, 1)))
    }

    @Test
    fun `half-way beats barely started and nearly done`() {
        val seen = daysAgo(2)
        val ranked = rank(
            listOf(film(0, "barely", seen, watchedMinutes = 3), film(1, "nearly", seen, watchedMinutes = 95), film(2, "halfway", seen, watchedMinutes = 50)),
        )

        assertEquals(listOf("halfway", "nearly", "barely"), ranked.map { it.ratingKey })
    }

    @Test
    fun `items removed from Up Next are left out, an episode by its show`() {
        val seen = daysAgo(2)
        val continueWatching = listOf(
            film(0, "kept-film", seen),
            nextUp(1, "harbour-3", "harbour", seen),
            // No show key in Continue Watching: the library knows its show.
            nextUp(2, "quarry-5", show = null, lastViewed = seen),
            film(3, "removed-film", seen),
            nextUp(4, "meadow-2", "meadow", seen),
        )

        val ranked = rank(
            continueWatching,
            episodes = listOf(unwatched("quarry-5", "quarry", 5)),
            hides = Hides(upNext = setOf("harbour", "quarry", "removed-film"), notTonight = setOf("meadow"), quickWatch = setOf("kept-film")),
        )

        assertEquals("other cards' hides don't count", listOf("kept-film", "meadow-2"), ranked.map { it.ratingKey })
    }

    @Test
    fun `ties keep Plex's order`() {
        val seen = daysAgo(4)
        val continueWatching = listOf(nextUp(2, "mango-1", "mango", seen), nextUp(0, "zebra-1", "zebra", seen), nextUp(1, "apple-1", "apple", seen))

        assertEquals(listOf("zebra-1", "apple-1", "mango-1"), rank(continueWatching).map { it.ratingKey })
    }

    @Test
    fun `every item has a reason, the strongest first`() {
        // Ferry: three episodes 9 to 11 days ago, so in two weeks but not this week.
        val ferry = (1..3).map { watched("ferry-$it", "ferry", it, daysAgo(8L + it, hour = 9)) } + unwatched("ferry-4", "ferry", 4)
        val ranked = rank(
            listOf(
                nextUp(0, "ferry-4", "ferry", daysAgo(9, hour = 9)),
                film(1, "tonight", seconds(monday.minusHours(2))),
                film(2, "last-night", daysAgo(1, hour = 23)),
                film(3, "midweek", daysAgo(3)),
                film(4, "long-ago", daysAgo(40)),
                film(5, "unknown", lastViewed = null),
            ),
            ferry,
        )

        assertEquals(
            mapOf(
                "ferry-4" to "3 episodes in two weeks",
                "tonight" to "Watched today",
                "last-night" to "Watched yesterday",
                "midweek" to "Watched 3 days ago",
                "long-ago" to "In your Continue Watching",
                "unknown" to "In your Continue Watching",
            ),
            ranked.associate { it.ratingKey to it.reason },
        )
        assertTrue(ranked.all { it.reason.isNotBlank() })
    }
}
