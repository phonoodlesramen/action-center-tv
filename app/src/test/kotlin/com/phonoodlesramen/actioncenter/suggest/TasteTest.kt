package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up titles; each signal is built by hand so the arithmetic is plain. */
class TasteTest {
    private val now = 1_793_000_000_000L
    private val dayMillis = 86_400_000L
    private val halfLife = Fade.HalfLife(SignalWeights.HALF_LIFE_MILLIS)

    private fun movie(key: String, genres: List<String> = emptyList(), directors: List<String> = emptyList(), rating: Double? = null) =
        PlexItem(key, "movie", key, genres = genres, directors = directors, userRating = rating).toEntity("1", 0)

    private fun show(key: String, genres: List<String> = emptyList()) = PlexItem(key, "show", key, genres = genres).toEntity("2", 0)

    private fun signal(key: String, kind: SignalKind, weight: Double, daysAgo: Long = 0) = Signal(key, kind, weight, now - daysAgo * dayMillis, halfLife)

    private fun finished(key: String, daysAgo: Long = 0) = signal(key, SignalKind.FINISHED, SignalWeights.FINISHED, daysAgo)

    private fun taste(titles: List<ItemEntity>, signals: List<Signal>) = Taste.from(Library(titles, emptyList(), emptyList(), emptyList()), signals, now)

    private val crime = Feature(FeatureKind.GENRE, "Crime")

    @Test
    fun `one finish weighs a third, two weigh a half, and a finish against an abandon cancels out`() {
        val once = taste(listOf(movie("a", listOf("Crime"))), listOf(finished("a")))
        assertEquals(1.0 / 3, once.weight(crime), 1e-9)

        val twice = taste(listOf(movie("a", listOf("Crime")), movie("b", listOf("Crime"))), listOf(finished("a"), finished("b")))
        assertEquals(0.5, twice.weight(crime), 1e-9)

        val cancelled = taste(
            listOf(movie("a", listOf("Crime")), movie("b", listOf("Crime"))),
            listOf(finished("a"), signal("b", SignalKind.ABANDONED, SignalWeights.ABANDONED)),
        )
        assertEquals(0.0, cancelled.weight(crime), 1e-9)

        val faded = taste(listOf(movie("a", listOf("Crime"))), listOf(finished("a", daysAgo = 182)))
        assertEquals("half a finish after the half-life", 0.5 / 2.5, faded.weight(crime), 1e-9)
    }

    @Test
    fun `a genre you keep finishing outweighs one you finished once`() {
        val titles = listOf(
            movie("c1", listOf("Crime")), movie("c2", listOf("Crime")), movie("c3", listOf("Crime")), movie("w1", listOf("Western")),
        )
        val taste = taste(titles, listOf(finished("c1"), finished("c2"), finished("c3"), finished("w1")))

        assertEquals(TasteWeights.GENRE * 3.0 / 5, taste.of(movie("new-crime", listOf("Crime"))), 1e-9)
        assertEquals(TasteWeights.GENRE * 1.0 / 3, taste.of(movie("new-western", listOf("Western"))), 1e-9)
    }

    @Test
    fun `affinity adds each kind's weight times the mean of the title's features of that kind`() {
        val taste = taste(listOf(movie("a", listOf("Crime"), listOf("Dena Varga"))), listOf(finished("a")))

        // Crime a third, Comedy nothing yet: the genres' mean is a sixth. The director is a third.
        val candidate = movie("b", listOf("Crime", "Comedy"), listOf("Dena Varga"))
        assertEquals(TasteWeights.GENRE / 6 + TasteWeights.DIRECTOR / 3, taste.of(candidate), 1e-9)
        assertEquals("a title with nothing in common has no opinion", 0.0, taste.of(movie("c", listOf("Musical"))), 1e-9)
    }

    @Test
    fun `an abandon, a stopped show and Not tonight count against a title's features`() {
        val titles = listOf(movie("liked", listOf("Crime")), movie("dropped", listOf("Crime")), show("stopped", listOf("Crime")), movie("skipped", listOf("Crime")))
        val candidate = movie("next", listOf("Crime"))
        val base = taste(titles, listOf(finished("liked"))).of(candidate)

        listOf(
            signal("dropped", SignalKind.ABANDONED, SignalWeights.ABANDONED),
            signal("stopped", SignalKind.STALLED, SignalWeights.STALLED),
            Signal("skipped", SignalKind.NOT_TONIGHT, SignalWeights.NOT_TONIGHT, now, Fade.Linear(SignalWeights.NOT_TONIGHT_FADE_MILLIS)),
        ).forEach { against ->
            val lowered = taste(titles, listOf(finished("liked"), against)).of(candidate)
            assertTrue("${against.kind} lowers it: $lowered < $base", lowered < base)
        }
    }

    @Test
    fun `a signal for a title that's gone from the library is skipped`() {
        val taste = taste(listOf(movie("a", listOf("Crime"))), listOf(finished("a"), finished("gone"), signal("gone", SignalKind.ABANDONED, -1.0)))

        assertEquals(1.0 / 3, taste.weight(crime), 1e-9)
        assertEquals(setOf("a"), taste.finished.keys)
    }

    @Test
    fun `finishes, rewatches and loved ratings are evidence, and only clean finishes can be named`() {
        val titles = listOf(
            movie("once", listOf("Crime")),
            movie("twice", listOf("Crime")),
            movie("loved", listOf("Crime"), rating = 9.0),
            movie("disliked", listOf("Crime"), rating = 2.0),
            movie("removed", listOf("Crime")),
            movie("rated-only", listOf("Crime"), rating = 8.0),
            show("two-episodes", listOf("Crime")),
            show("three-episodes", listOf("Crime")),
        )
        val signals = listOf(
            finished("once", daysAgo = 3),
            finished("twice", daysAgo = 5), signal("twice", SignalKind.REWATCHED, SignalWeights.REWATCHED, 5),
            finished("loved", daysAgo = 7), signal("loved", SignalKind.RATED, SignalWeights.RATED_LOVED, 7),
            finished("disliked", daysAgo = 2), signal("disliked", SignalKind.RATED, SignalWeights.RATED_DISLIKED, 2),
            finished("removed", daysAgo = 9), signal("removed", SignalKind.ABANDONED, SignalWeights.ABANDONED, 1),
            signal("rated-only", SignalKind.RATED, SignalWeights.RATED_LOVED, 4),
            signal("two-episodes", SignalKind.FINISHED, 2.0 / 3, 1),
            signal("three-episodes", SignalKind.FINISHED, 1.0, 1),
        )
        val taste = taste(titles, signals)

        assertEquals(
            mapOf(
                "loved" to Finish("loved", now - 7 * dayMillis, loved = true),
                "once" to Finish("once", now - 3 * dayMillis, loved = false),
                "three-episodes" to Finish("three-episodes", now - dayMillis, loved = false),
                "twice" to Finish("twice", now - 5 * dayMillis, loved = true),
            ),
            taste.finished.toSortedMap(),
        )
        assertEquals(
            "the abandon counts against, not for",
            setOf("disliked", "loved", "once", "rated-only", "removed", "three-episodes", "twice", "two-episodes"),
            taste.evidence(crime).toSortedSet(),
        )
    }
}
