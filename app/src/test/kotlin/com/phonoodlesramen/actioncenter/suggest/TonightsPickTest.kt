package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexMedia
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up titles. [now] is 26 October 2026, 07:33 UTC. */
class TonightsPickTest {
    private val now = 1_793_000_000_000L
    private val day = 86_400L

    /** Epoch seconds, [days] before [now]. */
    private fun ago(days: Long) = now / 1_000 - days * day

    private fun movie(
        key: String,
        genres: List<String> = emptyList(),
        audience: Double? = null,
        viewCount: Int = 0,
        offsetMinutes: Long? = null,
        file: Boolean = true,
        rating: Double? = null,
        added: Long = ago(400),
    ) = PlexItem(
        key, "movie", key, genres = genres, audienceRating = audience, viewCount = viewCount, lastViewedAt = if (viewCount > 0 || offsetMinutes != null) ago(30) else null,
        viewOffsetMs = offsetMinutes?.times(60_000), userRating = rating, lastRatedAt = rating?.let { ago(30) }, addedAt = added,
        media = if (file) PlexMedia("/library/parts/$key/1/file.mkv") else null,
    ).toEntity("1", 0)

    private fun show(key: String, title: String = key, genres: List<String> = emptyList()) =
        PlexItem(key, "show", title, genres = genres, addedAt = ago(400)).toEntity("2", 0)

    /** A season's episodes, the first [watched] of them finished [daysAgo] (later ones a little later). */
    private fun season(show: String, season: Int, episodes: Int, watched: Int = 0, daysAgo: Long = 0) = (1..episodes).map { n ->
        val seen = n <= watched
        EpisodeRow("$show-$season-$n", show, season, n, 1_500_000, if (seen) 1 else 0, if (seen) ago(daysAgo) + n else null, null, ago(400))
    }

    private fun input(
        titles: List<ItemEntity>,
        episodes: List<EpisodeRow> = emptyList(),
        plays: List<PlayEntity> = emptyList(),
        hidden: Set<String> = emptySet(),
        at: Long = now,
    ): Pair<RunInput, Taste> {
        val library = Library(titles, episodes, plays, emptyList())
        val signals = Signals.from(library, emptyList(), at)
        val taste = Taste.from(library, signals, at)
        return RunInput(library, signals, Hides(notTonight = hidden), taste, at, ZoneOffset.UTC) to taste
    }

    private fun pick(input: Pair<RunInput, Taste>) = TonightsPick.pick(input.first, input.second)

    private val films = (1..6).map { movie("f$it") }

    @Test
    fun `a show close to a season's end comes first, with its next episode`() {
        val harbour = show("s1", "Night Harbour")
        val episodes = season("s1", 1, 3, watched = 3, daysAgo = 100) + season("s1", 2, 10, watched = 8, daysAgo = 2) + season("s1", 3, 8)

        val picks = pick(input(films + harbour, episodes))

        assertEquals(
            Picked("s1-2-9", "You're 2 episodes from the end of Night Harbour's season 2", listOf("Last watched 2 days ago"), episodesLeft = 2),
            picks.first(),
        )
        assertEquals(6, picks.size)
    }

    @Test
    fun `the closest to the end wins, then the latest, and only within 4 episodes and 30 days`() {
        val shows = listOf(show("near", "Glass Coast"), show("older", "Salt Flats"), show("two-left", "Iron Bay"), show("stale", "Fog Line"), show("far", "Red Mesa"))
        val episodes = season("near", 1, 6, watched = 5, daysAgo = 1) +
            season("older", 1, 6, watched = 5, daysAgo = 5) +
            season("two-left", 1, 6, watched = 4, daysAgo = 0) +
            season("stale", 1, 6, watched = 5, daysAgo = 40) +
            season("far", 1, 10, watched = 5, daysAgo = 0)

        assertEquals(
            Picked("near-1-6", "You're 1 episode from the end of Glass Coast", listOf("Last watched yesterday"), episodesLeft = 1),
            pick(input(shows, episodes)).first(),
        )

        val withoutNear = pick(input(shows.drop(1), episodes.filter { it.showKey != "near" })).first()
        assertEquals("1 left beats 2 left, though watched longer ago", "older-1-6", withoutNear.ratingKey)

        val leftOver = pick(input(listOf(shows[3], shows[4]), episodes.filter { it.showKey in setOf("stale", "far") }))
        assertTrue("40 days ago and 5 left don't count: $leftOver", leftOver.none { it.reason.startsWith("You're") })
        assertTrue("only the finish pick has episodes left", leftOver.none { it.episodesLeft != null })
    }

    @Test
    fun `a show 4 episodes from the end is offered too, below one that's closer, with its episodes left for the mode (M3)`() {
        val four = show("four", "Copper Hill")
        val three = show("three", "Tin Sky")
        val episodes = season("four", 2, 10, watched = 6, daysAgo = 1) + season("three", 1, 8, watched = 5, daysAgo = 3)

        val alone = pick(input(films + four, episodes.filter { it.showKey == "four" }))
        assertEquals(
            Picked("four-2-7", "You're 4 episodes from the end of Copper Hill", listOf("Last watched yesterday"), episodesLeft = 4),
            alone.first(),
        )
        assertEquals(6, alone.size)

        val both = pick(input(films + four + three, episodes))
        assertEquals("3 left beats 4 left", Picked("three-1-6", "You're 3 episodes from the end of Tin Sky", listOf("Last watched 3 days ago"), episodesLeft = 3), both.first())
        assertTrue(both.toString(), both.none { it.ratingKey.startsWith("four") })
    }

    @Test
    fun `only the season you're watching counts, not an older one with gaps`() {
        // Seen on the Shield: a sketch show's season 49 had 3 left while you were on season 52.
        val sketch = show("s9", "Late Sketch")
        val episodes = season("s9", 49, 21, watched = 18, daysAgo = 300) + season("s9", 52, 12, watched = 1, daysAgo = 1)

        val picks = pick(input(films + sketch, episodes))

        assertTrue(picks.toString(), picks.none { it.reason.startsWith("You're") })
    }

    @Test
    fun `one series you haven't started sits third and plays its first episode`() {
        val open = show("s5", "Open Water")
        val specials = listOf(EpisodeRow("s5-0-1", "s5", 0, 1, 1_500_000, 0, null, null, ago(400)))
        val started = show("s6", "Long Road")
        val episodes = specials + season("s5", 2, 3) + season("s5", 1, 4) + season("s6", 1, 5, watched = 1, daysAgo = 90)
        val (input, taste) = input(films + open + started, episodes)

        val picks = TonightsPick.pick(input, taste)

        assertEquals(6, picks.size)
        assertEquals(Picked("s5-1-1", Reasons.of(open, taste, input).reason, Reasons.of(open, taste, input).evidence), picks[2])
        assertEquals("the rest are films", 5, picks.count { it.ratingKey.startsWith("f") })

        assertEquals("with one film, the series comes next", listOf("f1", "s5-1-1"), pick(input(listOf(movie("f1"), open), episodes)).map { it.ratingKey })
    }

    @Test
    fun `hidden titles are left out, and so are started, watched, played, disliked and file-less films`() {
        val titles = listOf(
            movie("ok"),
            movie("hidden"),
            movie("started", offsetMinutes = 20),
            movie("watched", viewCount = 1),
            movie("played"),
            movie("no-file", file = false),
            movie("disliked", rating = 2.0),
            show("hidden-show"),
            show("near", "Glass Coast"),
            show("near-episode", "Iron Bay"),
        )
        val episodes = season("hidden-show", 1, 3) + season("near", 1, 6, watched = 5, daysAgo = 1) + season("near-episode", 1, 6, watched = 5, daysAgo = 1)
        val plays = listOf(PlayEntity("h1", "played", "movie", "played", ago(50), null))

        val picks = pick(input(titles, episodes, plays, hidden = setOf("hidden", "hidden-show", "near", "near-episode-1-6")))

        assertEquals(listOf("ok"), picks.map { it.ratingKey })
    }

    @Test
    fun `a pick that shares its top genre with one above drops a little, so the list varies`() {
        val titles = listOf(
            movie("crime-a", listOf("Crime", "Drama"), audience = 9.0),
            movie("crime-b", listOf("Crime"), audience = 8.5),
            movie("comedy", listOf("Comedy", "Crime"), audience = 8.0),
        )
        val (input, taste) = input(titles)
        assertTrue(Scoring.score(titles[1], taste, input) > Scoring.score(titles[2], taste, input))

        assertEquals(listOf("crime-a", "comedy", "crime-b"), TonightsPick.pick(input, taste).map { it.ratingKey })
    }

    @Test
    fun `the score is taste, a nudge for the rating and a little for being new`() {
        val (input, taste) = input(emptyList())

        assertEquals(0.2 * 0.4 + 0.15, Scoring.score(movie("new", audience = 9.0, added = ago(30)), taste, input), 1e-9)
        assertEquals(0.2 * -0.2, Scoring.score(movie("old", audience = 3.0, added = ago(31)), taste, input), 1e-9)
        assertEquals("unrated counts as 5", 0.0, Scoring.score(movie("plain"), taste, input), 1e-9)
    }

    @Test
    fun `the same list all day, and a different one tomorrow`() {
        // Numeric keys, as Plex's are: alike but for a digit, they'd keep their order without the mix.
        val titles = (4101..4110).map { movie("$it") }
        val today = pick(input(titles))

        assertEquals(today, pick(input(titles)))
        assertEquals("three hours later, the same day", today, pick(input(titles, at = now + 3 * 3_600_000L)))
        assertNotEquals(today, pick(input(titles, at = now + day * 1_000)))
        assertEquals(6, today.size)
    }
}
