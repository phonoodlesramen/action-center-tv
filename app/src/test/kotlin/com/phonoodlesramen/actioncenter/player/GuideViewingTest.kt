package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 11's rules: the watched point, what counts (K2), the branch's write, the credits card's time and its next episode (K5). */
class GuideViewingTest {
    private val minute = 60_000L
    private val episode = 44 * minute

    @Test
    fun `the watched point is the credits marker, else 90 percent`() {
        assertEquals(41 * minute, GuideViewing.watchedPoint(episode, creditsMs = 41 * minute))
        assertEquals((episode * 0.9).toLong(), GuideViewing.watchedPoint(episode, creditsMs = null))
        assertEquals("a marker past the end isn't one", (episode * 0.9).toLong(), GuideViewing.watchedPoint(episode, creditsMs = episode + minute))
        assertEquals((episode * 0.9).toLong(), GuideViewing.watchedPoint(episode, creditsMs = 0))
    }

    @Test
    fun `it counts at the watched point once three quarters has played`() {
        val credits = 41 * minute
        assertTrue(GuideViewing.counts(positionMs = credits, watchedMs = 33 * minute, durationMs = episode, creditsMs = credits))
        assertFalse("not at the credits yet", GuideViewing.counts(credits - 1, 40 * minute, episode, credits))
        assertFalse("joined late: under three quarters played", GuideViewing.counts(credits, 32 * minute, episode, credits))
        assertFalse("no length, no count", GuideViewing.counts(credits, 40 * minute, 0, credits))
    }

    @Test
    fun `without a marker it counts at 90 percent`() {
        val ninety = (episode * 0.9).toLong()
        assertFalse(GuideViewing.counts(ninety - 1, episode, episode, creditsMs = null))
        assertTrue(GuideViewing.counts(ninety, episode, episode, creditsMs = null))
    }

    @Test
    fun `under a minute in, a branch writes nothing`() {
        assertEquals(ResumeWrite.Nothing, ResumeWrite.of(null))
        assertEquals(ResumeWrite.Nothing, ResumeWrite.of(BranchPoint(59_999, 40 * minute)))
    }

    @Test
    fun `before the watched point a branch writes the position, and from it on, watched`() {
        assertEquals(ResumeWrite.Position(minute), ResumeWrite.of(BranchPoint(minute, 40 * minute)))
        assertEquals(ResumeWrite.Position(40 * minute - 1), ResumeWrite.of(BranchPoint(40 * minute - 1, 40 * minute)))
        assertEquals(ResumeWrite.Watched, ResumeWrite.of(BranchPoint(40 * minute, 40 * minute)))
        assertEquals(ResumeWrite.Watched, ResumeWrite.of(BranchPoint(43 * minute, 40 * minute)))
    }

    @Test
    fun `the credits card's time is from the marker, else the last minute, to the end`() {
        assertTrue(GuideViewing.inCredits(41 * minute, episode, creditsMs = 41 * minute))
        assertFalse(GuideViewing.inCredits(41 * minute - 1, episode, creditsMs = 41 * minute))
        assertFalse(GuideViewing.inCredits(42 * minute, episode, creditsMs = null))
        assertTrue(GuideViewing.inCredits(43 * minute, episode, creditsMs = null))
        assertFalse("past the end", GuideViewing.inCredits(episode, episode, creditsMs = null))
    }

    // The next episode, from the cache (K5). Every title is made up.

    private fun item(
        key: String,
        type: String = "episode",
        season: Int? = null,
        number: Int? = null,
        show: String? = "show-harbour",
        rating: String? = "TV-PG",
    ) = ItemEntity(
        ratingKey = key, sectionKey = "2", type = type, title = "Title $key", year = 2025, durationMs = episode, viewCount = 0,
        lastViewedAt = null, addedAt = 1_790_000_000, updatedAt = 1_790_000_000, contentRating = rating, audienceRating = null,
        rating = null, thumb = "/library/metadata/$key/thumb", art = null, genres = "", syncedAt = 0,
        showKey = show, showTitle = "Harbour Days", season = season, episode = number,
    )

    private val episodes = listOf(
        item("101", season = 1, number = 1),
        item("102", season = 1, number = 2),
        item("103", season = 1, number = 3),
        item("201", season = 2, number = 1),
        item("202", season = 2, number = 2),
        item("900", season = 0, number = 1),
        // Night Watch: its third episode is rated above the first two; its show is TV-14.
        item("301", season = 1, number = 1, show = "show-night", rating = "TV-PG"),
        item("302", season = 1, number = 2, show = "show-night", rating = null),
        item("303", season = 1, number = 3, show = "show-night", rating = "TV-MA"),
        // Quiet Hours: neither its episodes nor its show have a rating.
        item("401", season = 1, number = 1, show = "show-quiet", rating = null),
        item("402", season = 1, number = 2, show = "show-quiet", rating = null),
    )

    private val shows = listOf(
        item("show-harbour", type = "show", show = null, rating = "TV-PG"),
        item("show-night", type = "show", show = null, rating = "TV-14"),
        item("show-quiet", type = "show", show = null, rating = null),
    )

    private val lookup = NextEpisodes(
        item = { key -> (episodes + shows + item("555", type = "movie", show = null)).firstOrNull { it.ratingKey == key } },
        episodesOf = { show -> episodes.filter { it.showKey == show } },
    )

    /** A kid of 7 to 9: AgeBrackets.DEFAULT's first bracket. */
    private val kid = setOf("G", "PG", "TV-Y", "TV-Y7", "TV-G", "TV-PG")

    @Test
    fun `the next episode is the next in its season`() = runTest {
        assertEquals(NextEpisode("103", "S1 E3", "Title 103", "/library/metadata/103/thumb"), lookup.after("102"))
    }

    @Test
    fun `after a season's last, the next season's first`() = runTest {
        assertEquals("201", lookup.after("103")?.ratingKey)
        assertEquals("S2 E1", lookup.after("103")?.label)
    }

    @Test
    fun `after the last episode, a film, a special or a title the cache doesn't know, there's none`() = runTest {
        assertNull(lookup.after("202"))
        assertNull(lookup.after("555"))
        assertNull(lookup.after("900"))
        assertNull(lookup.after("404"))
    }

    @Test
    fun `a kid is offered the next episode when it's inside their ceiling`() = runTest {
        assertEquals("103", lookup.after("102", ceiling = kid)?.ratingKey)
        assertEquals("201", lookup.after("103", ceiling = kid)?.ratingKey)
    }

    @Test
    fun `a kid gets no card for a next episode above their ceiling, or with no rating anywhere`() = runTest {
        assertNull("TV-MA", lookup.after("302", ceiling = kid))
        assertNull("unrated, so its show's TV-14", lookup.after("301", ceiling = kid))
        assertNull("unrated, and so is its show", lookup.after("401", ceiling = kid))
        assertEquals("an older kid's ceiling takes TV-14", "302", lookup.after("301", ceiling = kid + "TV-14")?.ratingKey)
    }

    @Test
    fun `an adult is offered every next episode, as before`() = runTest {
        assertEquals("302", lookup.after("301", ceiling = null)?.ratingKey)
        assertEquals("303", lookup.after("302")?.ratingKey)
        assertEquals("402", lookup.after("401")?.ratingKey)
    }
}
