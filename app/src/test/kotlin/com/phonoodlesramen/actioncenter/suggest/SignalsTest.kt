package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Made-up titles: each one is a case from S1 and S2. */
class SignalsTest {
    private val now = 1_793_000_000_000L
    private val day = 86_400L

    /** Epoch seconds, [days] before [now]. */
    private fun ago(days: Long) = now / 1_000 - days * day

    private fun movie(key: String, viewCount: Int = 0, lastViewed: Long? = null, offsetMinutes: Long? = null, rating: Double? = null) =
        PlexItem(
            key, "movie", key, viewCount = viewCount, lastViewedAt = lastViewed, viewOffsetMs = offsetMinutes?.times(60_000),
            userRating = rating, lastRatedAt = rating?.let { ago(5) },
        ).toEntity("1", 0)

    private fun show(key: String) = PlexItem(key, "show", key).toEntity("2", 0)

    private fun episode(key: String, show: String, number: Int, viewCount: Int = 0, lastViewed: Long? = null, added: Long = ago(400), season: Int = 1) =
        EpisodeRow(key, show, season, number, 1_500_000, viewCount, lastViewed, null, added)

    private fun play(key: String, at: Long) = PlayEntity("$key@$at", key, "movie", key, at, null)

    private fun signals(library: Library, feedback: List<FeedbackEntity> = emptyList()) =
        Signals.from(library, feedback, now).map { Triple(it.titleKey, it.kind, it.weight) }

    @Test
    fun `films have a finish, a rewatch older than history, an abandon, and positions that don't count yet`() {
        val library = Library(
            titles = listOf(
                movie("finished", viewCount = 1, lastViewed = ago(10)),
                movie("rewatched", viewCount = 2, lastViewed = ago(500)),
                movie("abandoned", lastViewed = ago(25), offsetMinutes = 30),
                movie("barely-started", lastViewed = ago(25), offsetMinutes = 5),
                movie("in-progress", lastViewed = ago(10), offsetMinutes = 30),
                movie("never"),
            ),
            episodes = emptyList(),
            plays = listOf(play("finished", ago(10))),
            continueWatching = emptyList(),
        )

        assertEquals(
            listOf(
                Triple("abandoned", SignalKind.ABANDONED, -1.0),
                Triple("finished", SignalKind.FINISHED, 1.0),
                Triple("rewatched", SignalKind.FINISHED, 1.0),
                Triple("rewatched", SignalKind.REWATCHED, 0.5),
            ),
            signals(library),
        )
        assertEquals(ago(500) * 1_000, Signals.from(library, emptyList(), now).first { it.kind == SignalKind.REWATCHED }.atMillis)
    }

    @Test
    fun `your ratings count, 4 stars or more for, 2 or fewer against, the middle not at all`() {
        val library = Library(listOf(movie("loved", rating = 9.0), movie("disliked", rating = 2.0), movie("fine", rating = 6.0)), emptyList(), emptyList(), emptyList())

        assertEquals(
            listOf(Triple("disliked", SignalKind.RATED, -1.0), Triple("loved", SignalKind.RATED, 0.5)),
            signals(library),
        )
    }

    @Test
    fun `a show's finishes count in part below 3 episodes, a stopped show is half an abandon, a new season isn't`() {
        val library = Library(
            titles = listOf(show("two-watched"), show("stopped"), show("caught-up"), show("specials-only")),
            episodes = listOf(
                episode("a1", "two-watched", 1, viewCount = 1, lastViewed = ago(3)),
                episode("a2", "two-watched", 2, viewCount = 1, lastViewed = ago(2)),
                episode("b1", "stopped", 1, viewCount = 1, lastViewed = ago(70)),
                episode("b2", "stopped", 2, added = ago(100)),
                episode("c1", "caught-up", 1, viewCount = 1, lastViewed = ago(70)),
                episode("c2", "caught-up", 2, added = ago(10)),
                episode("d0", "specials-only", 1, viewCount = 1, lastViewed = ago(5), season = 0),
            ),
            plays = emptyList(),
            continueWatching = emptyList(),
        )

        val found = signals(library)

        assertEquals(
            listOf(
                Triple("caught-up", SignalKind.FINISHED, 1.0 / 3),
                Triple("stopped", SignalKind.FINISHED, 1.0 / 3),
                Triple("stopped", SignalKind.STALLED, -0.5),
                Triple("two-watched", SignalKind.FINISHED, 2.0 / 3),
            ),
            found,
        )
    }

    @Test
    fun `feedback counts Not tonight twice, a removal on the show, none once played again, and a hide`() {
        val library = Library(
            titles = listOf(movie("skipped"), show("show"), show("back"), movie("stale", lastViewed = ago(30), offsetMinutes = 40), movie("hidden")),
            episodes = listOf(episode("e1", "show", 1), episode("f1", "back", 1, lastViewed = ago(1))),
            plays = emptyList(),
            continueWatching = emptyList(),
        )
        fun row(key: String, kind: FeedbackKind, daysAgo: Long, count: Int = 1) =
            FeedbackEntity(Profiles.OWNER, key, kind, "", now - daysAgo * day * 1_000, count, Long.MAX_VALUE)
        val feedback = listOf(
            row("skipped", FeedbackKind.NOT_TONIGHT, 1, count = 2),
            row("e1", FeedbackKind.UP_NEXT_REMOVED, 3),
            row("f1", FeedbackKind.UP_NEXT_REMOVED, 3),
            row("stale", FeedbackKind.UP_NEXT_REMOVED, 3),
            row("hidden", FeedbackKind.LENS_HIDDEN, 1),
        )

        assertEquals(
            listOf(
                Triple("hidden", SignalKind.HIDDEN, -0.15),
                Triple("show", SignalKind.ABANDONED, -1.0),
                Triple("skipped", SignalKind.NOT_TONIGHT, -0.6),
                Triple("stale", SignalKind.ABANDONED, -1.0),
            ),
            signals(library, feedback),
        )
    }

    @Test
    fun `signals fade to half after the half-life, and Not tonight is gone after 60 days`() {
        val halfLife = Fade.HalfLife(SignalWeights.HALF_LIFE_MILLIS)
        assertEquals(0.5, halfLife.factor(SignalWeights.HALF_LIFE_MILLIS), 1e-9)
        assertEquals(0.25, halfLife.factor(2 * SignalWeights.HALF_LIFE_MILLIS), 1e-9)

        val linear = Fade.Linear(SignalWeights.NOT_TONIGHT_FADE_MILLIS)
        assertEquals(0.5, linear.factor(SignalWeights.NOT_TONIGHT_FADE_MILLIS / 2), 1e-9)
        assertEquals(0.0, linear.factor(2 * SignalWeights.NOT_TONIGHT_FADE_MILLIS), 1e-9)
        assertEquals("a date in the future counts in full", 1.0, linear.factor(-1_000), 1e-9)

        val notTonight = Signal("m1", SignalKind.NOT_TONIGHT, -0.3, now, linear)
        assertEquals(-0.15, notTonight.weightAt(now + SignalWeights.NOT_TONIGHT_FADE_MILLIS / 2), 1e-9)
    }
}
