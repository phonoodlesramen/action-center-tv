package com.phonoodlesramen.actioncenter.plex.cache

import com.phonoodlesramen.actioncenter.plex.PlexItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Up Next credits fix (10/27/2026): what counts as finished, and what takes its place. */
class FinishedTest {
    private val minute = 60_000L

    /** The operator's case: a 56-minute first episode, its credits from 54:00. */
    private val episodeMs = 56 * minute
    private val creditsMs = 54 * minute

    @Test
    fun `stopped in the credits is finished, part-way in isn't`() {
        assertTrue(Finished.at(offsetMs = 55 * minute, durationMs = episodeMs, creditsMs = creditsMs))
        assertTrue("the credits, to the second", Finished.at(creditsMs, episodeMs, creditsMs))
        assertFalse("where the 10/27 sync saw it, about 25% left", Finished.at(42 * minute, episodeMs, creditsMs))
        assertFalse("50 of 56 minutes is short of Plex's 90%", Finished.at(50 * minute, episodeMs, creditsMs))
    }

    @Test
    fun `credits that start before the played point count from where they start`() {
        // A 22-minute episode whose credits and preview fill its last 3 minutes (86%).
        val credits = 19 * minute
        assertTrue(Finished.at(19 * minute + 30_000, 22 * minute, credits))
        assertFalse("without a marker, 89% isn't Plex's played point", Finished.at(19 * minute + 30_000, 22 * minute))
    }

    @Test
    fun `without credits, Plex's 90 percent is the played point, and a late marker doesn't move it`() {
        assertTrue(Finished.at(offsetMs = 90 * minute, durationMs = 100 * minute))
        assertFalse(Finished.at(offsetMs = 90 * minute - 1, durationMs = 100 * minute))
        // Plex's default: the earlier of its threshold and the first credits marker.
        assertTrue(Finished.at(offsetMs = 92 * minute, durationMs = 100 * minute, creditsMs = 97 * minute))
    }

    @Test
    fun `not started, or no length to measure, is never finished`() {
        assertFalse("a next episode has no position", Finished.at(offsetMs = null, durationMs = episodeMs, creditsMs = creditsMs))
        assertFalse(Finished.at(offsetMs = 0, durationMs = episodeMs, creditsMs = 0))
        assertFalse(Finished.at(offsetMs = 50 * minute, durationMs = null))
        assertFalse("a marker at 0 is no marker", Finished.at(offsetMs = 10 * minute, durationMs = null, creditsMs = 0))
    }

    @Test
    fun `the next episode is the next in the season, else the first of a later one, specials aside`() {
        val show = listOf(episode("e12", 1, 2), episode("e21", 2, 1), episode("e11", 1, 1), episode("s01", 0, 1), episode("e13", 1, 3))

        assertEquals("e12", Finished.nextEpisode(1, 1, show)?.ratingKey)
        assertEquals("e21", Finished.nextEpisode(1, 3, show)?.ratingKey)
        assertNull("the finale has none", Finished.nextEpisode(2, 1, show))
        assertNull("a special has none", Finished.nextEpisode(0, 1, show))
        assertNull("no numbers, no order", Finished.nextEpisode(null, 1, show))
    }

    @Test
    fun `an episode finished in its credits makes way for the next, a finished film for nothing, in Plex's order`() = runTest {
        val pilot = PlexItem(
            "e11", "episode", "We Is Us", showTitle = "Harbour Lights", season = 1, episode = 1, durationMs = episodeMs,
            viewOffsetMs = 55 * minute, lastViewedAt = 1_793_000_000, contentRating = "TV-MA",
            thumb = "/library/metadata/e11/thumb", showArt = "/library/metadata/s1/art", showKey = "s1",
        )
        val film = PlexItem("m1", "movie", "Arrival", durationMs = 116 * minute, viewOffsetMs = 112 * minute)
        val resume = PlexItem("m2", "movie", "Heat", durationMs = 170 * minute, viewOffsetMs = 60 * minute)
        val next = PlexItem("e33", "episode", "Sundae", showTitle = "The Bear", season = 3, episode = 3, showKey = "s2")
        val asked = mutableListOf<String>()

        val upNext = Finished.upNext(listOf(pilot, film, resume, next), credits = mapOf("e11" to creditsMs)) { show ->
            asked += show
            listOf(episode("e11", 1, 1), episode("e12", 1, 2, title = "Pirate Lady", rating = null))
        }

        assertEquals(listOf("e12", "m2", "e33"), upNext.map { it.ratingKey })
        val second = upNext.first()
        assertEquals(listOf("Pirate Lady", "Harbour Lights", "/library/metadata/s1/art", "s1"), listOf(second.title, second.showTitle, second.showArt, second.showKey))
        assertEquals(1 to 2, second.season to second.episode)
        assertNull("not started", second.viewOffsetMs)
        assertEquals(episodeMs, second.durationMs)
        assertEquals("its still", "/thumb/e12", second.thumb)
        assertEquals("when you last watched the show", 1_793_000_000L, second.lastViewedAt)
        assertEquals("the show's rating when the episode has none", "TV-MA", second.contentRating)
        assertEquals("only the finished episode's show is read", listOf("s1"), asked)
    }

    @Test
    fun `a finale, or a next episode Plex already lists, leaves nothing in its place`() = runTest {
        val finale = PlexItem("e12", "episode", "Finale", season = 1, episode = 2, durationMs = episodeMs, viewOffsetMs = 55 * minute, showKey = "s1")
        val other = PlexItem("e21", "episode", "Premiere", season = 2, episode = 1, durationMs = episodeMs, viewOffsetMs = 55 * minute, showKey = "s2")
        val listedNext = PlexItem("e22", "episode", "Second", season = 2, episode = 2, showKey = "s2")
        val episodes = mapOf("s1" to listOf(episode("e11", 1, 1), episode("e12", 1, 2)), "s2" to listOf(episode("e21", 2, 1), episode("e22", 2, 2)))

        val upNext = Finished.upNext(listOf(finale, other, listedNext), emptyMap()) { episodes.getValue(it) }

        assertEquals(listOf("e22"), upNext.map { it.ratingKey })
    }

    private fun episode(key: String, season: Int, episode: Int, title: String = key, rating: String? = "TV-14") = ItemEntity(
        ratingKey = key, sectionKey = "2", type = "episode", title = title, year = 2025, durationMs = episodeMs, viewCount = 0,
        lastViewedAt = null, addedAt = 1_790_000_000, updatedAt = 1_790_000_000, contentRating = rating, audienceRating = null,
        rating = null, thumb = "/thumb/$key", art = null, genres = "", syncedAt = 0, showKey = "s1", showTitle = "Harbour Lights",
        season = season, episode = episode,
    )
}
