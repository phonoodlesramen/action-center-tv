package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexMedia
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Made-up titles. [now] is 26 October 2026, 07:33 UTC, so "this month" is October. */
class ReasonsTest {
    private val now = 1_793_000_000_000L
    private val day = 86_400L

    /** Epoch seconds, [days] before [now]. */
    private fun ago(days: Long) = now / 1_000 - days * day

    private fun movie(
        key: String,
        title: String = key,
        year: Int? = null,
        release: String? = null,
        genres: List<String> = emptyList(),
        collections: List<String> = emptyList(),
        directors: List<String> = emptyList(),
        actors: List<String> = emptyList(),
        viewCount: Int = 0,
        lastViewed: Long? = if (viewCount > 0) ago(20) else null,
        offsetMinutes: Long? = null,
        rating: Double? = null,
        audience: Double? = null,
        added: Long? = ago(400),
    ) = PlexItem(
        key, "movie", title, year = year, releaseDate = release, genres = genres, collections = collections, directors = directors,
        actors = actors, viewCount = viewCount, lastViewedAt = lastViewed, viewOffsetMs = offsetMinutes?.times(60_000), userRating = rating,
        lastRatedAt = rating?.let { ago(5) }, audienceRating = audience, addedAt = added, media = PlexMedia("/library/parts/$key/1/file.mkv"),
    ).toEntity("1", 0)

    private fun show(key: String, title: String = key, genres: List<String> = emptyList(), actors: List<String> = emptyList()) =
        PlexItem(key, "show", title, genres = genres, actors = actors, addedAt = ago(400)).toEntity("2", 0)

    private fun episode(show: String, number: Int, viewCount: Int = 0, lastViewed: Long? = null, added: Long = ago(400)) =
        EpisodeRow("$show-$number", show, 1, number, 1_500_000, viewCount, lastViewed, null, added)

    private class World(val library: Library, val taste: Taste, val input: RunInput) {
        fun line(key: String) = Reasons.line(library.byKey.getValue(key), taste, input)
        fun of(key: String) = Reasons.of(library.byKey.getValue(key), taste, input)
    }

    private fun world(titles: List<ItemEntity>, episodes: List<EpisodeRow> = emptyList(), feedback: List<FeedbackEntity> = emptyList()): World {
        val library = Library(titles, episodes, emptyList(), emptyList())
        val signals = Signals.from(library, feedback, now)
        val taste = Taste.from(library, signals, now)
        return World(library, taste, RunInput(library, signals, Hides(), taste, now, ZoneOffset.UTC))
    }

    @Test
    fun `the next film in a collection you've started, else from the collection`() {
        val world = world(
            listOf(
                movie("sw0", "Sandworm Origins", year = 2019, collections = listOf("Sandworm")),
                movie("sw1", "Sandworm", release = "2021-10-22", collections = listOf("Sandworm"), viewCount = 1),
                movie("sw2", "Sandworm Rising", release = "2024-03-01", collections = listOf("Sandworm")),
                movie("sw3", "Sandworm Reborn", release = "2026-01-15", collections = listOf("Sandworm")),
            ),
        )

        assertEquals("Next in the Sandworm collection", world.line("sw2"))
        assertEquals("From the Sandworm collection", world.line("sw3"))
        assertEquals("an earlier one by year", "From the Sandworm collection", world.line("sw0"))
    }

    @Test
    fun `a director's film you watched, the one you loved first`() {
        val world = world(
            listOf(
                movie("recent", "Arrival Gate", directors = listOf("Dena Varga"), viewCount = 1, lastViewed = ago(10)),
                movie("loved", "Low Tide", directors = listOf("Dena Varga"), viewCount = 2, lastViewed = ago(200)),
                movie("other", "Quiet Signal", directors = listOf("Dena Varga")),
                movie("solo", "Paper Lantern", directors = listOf("Ivo Park")),
                movie("theirs", "Paper Moon", directors = listOf("Ivo Park"), viewCount = 1),
            ),
        )

        assertEquals("Because you watched Dena Varga's Low Tide", world.line("other"))
        assertEquals("Because you watched Ivo Park's Paper Moon", world.line("solo"))
    }

    @Test
    fun `a title it shares two things with, liked when rated 4 stars or finished twice`() {
        fun lineFor(viewCount: Int, rating: Double? = null) = world(
            listOf(
                movie("seen", "Night Shift", year = 1995, genres = listOf("Crime", "Thriller"), viewCount = viewCount, rating = rating),
                movie("two", "Back Alley", genres = listOf("Crime", "Thriller")),
                movie("decade", "Long Count", year = 1998, genres = listOf("Crime")),
                movie("one", "Short Fuse", genres = listOf("Crime")),
            ),
        ).let { world -> listOf("two", "decade", "one").map(world::line) }

        assertEquals(listOf("Because you liked Night Shift", "Because you liked Night Shift", null), lineFor(viewCount = 2))
        assertEquals(listOf("Because you liked Night Shift", "Because you liked Night Shift", null), lineFor(viewCount = 1, rating = 9.0))
        assertEquals(listOf("Because you finished Night Shift", "Because you finished Night Shift", null), lineFor(viewCount = 1))
    }

    @Test
    fun `an actor from a title you finished`() {
        val world = world(
            listOf(
                movie("seen", "Harbour Run", actors = listOf("Mara Quint", "Theo Lind"), viewCount = 1),
                movie("next", "Cold Front", actors = listOf("Ana Bell", "Theo Lind")),
            ),
        )

        assertEquals("Starring Theo Lind, from Harbour Run", world.line("next"))
    }

    @Test
    fun `three or more finishes in a genre this month, else this year, films or shows`() {
        fun crime(key: String, daysAgo: Long) = movie(key, genres = listOf("Crime"), viewCount = 1, lastViewed = ago(daysAgo))
        val candidate = movie("next", genres = listOf("Drama", "Crime"))

        assertEquals(
            "Because you finished three crime films this month",
            world(listOf(crime("c1", 2), crime("c2", 5), crime("c3", 10), candidate)).line("next"),
        )
        assertEquals(
            "Because you finished three crime films this year",
            world(listOf(crime("c1", 2), crime("c2", 5), crime("c3", 100), candidate)).line("next"),
        )
        assertNull("two aren't enough", world(listOf(crime("c1", 2), crime("c2", 5), crime("c3", 400), candidate)).line("next"))

        val shows = (1..3).map { show("s$it", genres = listOf("Crime")) } + show("next", genres = listOf("Crime"))
        val episodes = (1..3).flatMap { n -> (1..3).map { episode("s$n", it, viewCount = 1, lastViewed = ago(3)) } }
        assertEquals("Because you finished three crime shows this month", world(shows, episodes).line("next"))

        assertEquals(listOf("three", "nine", "10"), listOf(3, 9, 10).map(Reasons::spelled))
    }

    @Test
    fun `the fallbacks say when it came to Plex, its rating, or that it's new to you`() {
        val world = world(
            listOf(
                movie("today", added = now / 1_000 - 3_600),
                movie("yesterday", added = ago(1)),
                movie("days", added = ago(12)),
                movie("rated", audience = 8.4, added = ago(45)),
                movie("plain", added = ago(45)),
                movie("rated-new", audience = 8.4, added = ago(3)),
            ),
        )

        assertEquals(
            listOf(
                "Added to Plex today", "Added to Plex yesterday", "Added to Plex 12 days ago", "Rated 8.4 · never played", "New to you",
                "Added to Plex 3 days ago",
            ),
            listOf("today", "yesterday", "days", "rated", "plain", "rated-new").map { world.of(it).reason },
        )
        assertEquals(listOf("Rated 8.4"), world.of("rated-new").evidence)
        assertEquals("the rating isn't said twice", emptyList<String>(), world.of("rated").evidence)
        assertNull("a fallback isn't a taste reason", world.line("plain"))
    }

    @Test
    fun `a taste reason comes with the title's rating and newness`() {
        val world = world(
            listOf(
                movie("seen", "Harbour Run", actors = listOf("Theo Lind"), viewCount = 1),
                movie("new", actors = listOf("Theo Lind"), audience = 7.25, added = ago(3)),
                movie("month", actors = listOf("Theo Lind"), added = ago(20)),
            ),
        )

        assertEquals(Picked("new", "Starring Theo Lind, from Harbour Run", listOf("Rated 7.3", "New this week")), world.of("new"))
        assertEquals(listOf("New this month"), world.of("month").evidence)
    }

    @Test
    fun `the most specific evidence wins, collection, director, a liked title, cast, then genre`() {
        val world = world(
            listOf(
                movie("tide", "Tidewater", year = 2001, collections = listOf("Tide"), viewCount = 1),
                movie("road", "Paper Moon Road", directors = listOf("Ana Bell"), viewCount = 1),
                movie("fog", "The Long Fog", year = 1984, genres = listOf("Mystery"), viewCount = 2),
                movie("sky", "Copper Sky", actors = listOf("Ivo Park"), viewCount = 1),
                movie("d1", genres = listOf("Drama"), viewCount = 1, lastViewed = ago(2)),
                movie("d2", genres = listOf("Drama"), viewCount = 1, lastViewed = ago(4)),
                movie("d3", genres = listOf("Drama"), viewCount = 1, lastViewed = ago(6)),
                movie("all", year = 1987, genres = listOf("Mystery", "Drama"), collections = listOf("Tide"), directors = listOf("Ana Bell"), actors = listOf("Ivo Park")),
                movie("no-collection", year = 1987, genres = listOf("Mystery", "Drama"), directors = listOf("Ana Bell"), actors = listOf("Ivo Park")),
                movie("no-director", year = 1987, genres = listOf("Mystery", "Drama"), actors = listOf("Ivo Park")),
                movie("no-decade", genres = listOf("Mystery", "Drama"), actors = listOf("Ivo Park")),
                movie("genres-only", genres = listOf("Mystery", "Drama")),
            ),
        )

        assertEquals(
            listOf(
                "From the Tide collection",
                "Because you watched Ana Bell's Paper Moon Road",
                "Because you liked The Long Fog",
                "Starring Ivo Park, from Copper Sky",
                "Because you finished three drama films this month",
            ),
            listOf("all", "no-collection", "no-director", "no-decade", "genres-only").map(world::line),
        )
    }

    @Test
    fun `a reason never names a title you abandoned, disliked or stopped`() {
        val feedback = listOf(FeedbackEntity(Profiles.OWNER, "removed", FeedbackKind.UP_NEXT_REMOVED, "", now - 3 * day * 1_000, 1, Long.MAX_VALUE))
        val world = world(
            titles = listOf(
                movie("removed", "Gone Quiet", directors = listOf("Ana Bell"), viewCount = 1, lastViewed = ago(10)),
                movie("disliked", "Sour Note", directors = listOf("Ana Bell"), genres = listOf("Drama"), viewCount = 1, rating = 2.0),
                movie("half", "Half Way", directors = listOf("Ana Bell"), lastViewed = ago(25), offsetMinutes = 30),
                show("stopped", "Dim Harbour", actors = listOf("Ivo Park")),
                movie("next", directors = listOf("Ana Bell"), actors = listOf("Ivo Park"), genres = listOf("Drama")),
            ),
            episodes = listOf(
                episode("stopped", 1, viewCount = 1, lastViewed = ago(72)),
                episode("stopped", 2, viewCount = 1, lastViewed = ago(71)),
                episode("stopped", 3, viewCount = 1, lastViewed = ago(70)),
                episode("stopped", 4, added = ago(100)),
            ),
            feedback = feedback,
        )

        assertNull(world.line("next"))
        assertEquals("New to you", world.of("next").reason)
    }
}
