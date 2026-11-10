package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexMedia
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Made-up titles. */
class MoreLikeThisTest {
    private val now = 1_793_000_000_000L
    private val day = 86_400L

    private fun movie(
        key: String,
        title: String = key,
        year: Int? = null,
        genres: List<String> = emptyList(),
        collections: List<String> = emptyList(),
        directors: List<String> = emptyList(),
        actors: List<String> = emptyList(),
        viewCount: Int = 0,
        file: Boolean = true,
    ) = PlexItem(
        key, "movie", title, year = year, genres = genres, collections = collections, directors = directors, actors = actors,
        viewCount = viewCount, lastViewedAt = if (viewCount > 0) now / 1_000 - 20 * day else null,
        media = if (file) PlexMedia("/library/parts/$key/1/file.mkv") else null,
    ).toEntity("1", 0)

    private fun show(key: String, title: String = key, genres: List<String> = emptyList()) = PlexItem(key, "show", title, genres = genres).toEntity("2", 0)

    private fun episode(show: String, number: Int, viewCount: Int = 0) =
        EpisodeRow("$show-$number", show, 1, number, 1_500_000, viewCount, if (viewCount > 0) now / 1_000 - 20 * day else null, null, null)

    private fun setup(titles: List<ItemEntity>, episodes: List<EpisodeRow> = emptyList(), hidden: Set<String> = emptySet()): Pair<RunInput, Taste> {
        val library = Library(titles, episodes, emptyList(), emptyList())
        val signals = Signals.from(library, emptyList(), now)
        val taste = Taste.from(library, signals, now)
        return RunInput(library, signals, Hides(notTonight = hidden), taste, now, ZoneOffset.UTC) to taste
    }

    private val heatwave = movie(
        "source", "Heatwave", year = 1995, genres = listOf("Crime", "Drama"), collections = listOf("Heat Run"), directors = listOf("Dena Varga"),
        actors = listOf("Mara Quint", "Theo Lind", "Ivo Park", "Ana Bell"),
    )

    @Test
    fun `a shared collection ranks first, then a director, cast and genres, counting three cast at most`() {
        val (input, taste) = setup(
            listOf(
                heatwave,
                movie("genres", year = 1997, genres = listOf("Crime", "Drama")),
                movie("cast2", actors = listOf("Theo Lind", "Mara Quint")),
                movie("dir", directors = listOf("Dena Varga"), genres = listOf("Crime")),
                movie("cast4", actors = listOf("Mara Quint", "Theo Lind", "Ivo Park", "Ana Bell")),
                // Released before Heatwave, so it isn't the next one.
                movie("coll", year = 1989, collections = listOf("Heat Run"), genres = listOf("Crime")),
                movie("decade-only", year = 1992),
                movie("nothing", genres = listOf("Musical")),
            ),
        )

        val like = MoreLikeThis.like(heatwave, input, taste)

        assertEquals(
            listOf(
                Picked("coll", "From the Heat Run collection"),
                Picked("cast4", "Also starring Mara Quint"),
                Picked("dir", "Also Dena Varga"),
                Picked("cast2", "Also starring Mara Quint"),
                Picked("genres", "Crime · 1990s, like Heatwave"),
            ),
            like,
        )
        assertEquals(2, MoreLikeThis.like(heatwave, input, taste, limit = 2).size)
    }

    @Test
    fun `the film right after it, or after one you finished, is next in the collection`() {
        val first = movie("sw1", "Sandworm", year = 2021, collections = listOf("Sandworm"))
        val (input, taste) = setup(
            listOf(
                first,
                movie("sw2", "Sandworm Rising", year = 2024, collections = listOf("Sandworm")),
                movie("sw3", "Sandworm Reborn", year = 2026, collections = listOf("Sandworm"), viewCount = 1),
                movie("sw4", "Sandworm Rests", year = 2028, collections = listOf("Sandworm")),
                movie("sw5", "Sandworm Returns", year = 2030, collections = listOf("Sandworm")),
            ),
        )

        assertEquals(
            mapOf(
                "sw2" to "Next in the Sandworm collection",
                "sw4" to "Next in the Sandworm collection",
                "sw5" to "From the Sandworm collection",
                "sw3" to "From the Sandworm collection",
            ),
            MoreLikeThis.like(first, input, taste).associate { it.ratingKey to it.reason },
        )
    }

    @Test
    fun `never the title itself, nothing hidden, no film without a file, and only films for a film`() {
        val (input, taste) = setup(
            listOf(
                heatwave,
                movie("hidden", directors = listOf("Dena Varga")),
                movie("no-file", directors = listOf("Dena Varga"), file = false),
                show("crime-show", genres = listOf("Crime")),
                movie("kept", genres = listOf("Drama")),
            ),
            episodes = listOf(episode("crime-show", 1)),
            hidden = setOf("hidden"),
        )

        assertEquals(listOf(Picked("kept", "Drama, like Heatwave")), MoreLikeThis.like(heatwave, input, taste))
    }

    @Test
    fun `at the same likeness, the ones you haven't seen come first`() {
        val (input, taste) = setup(
            listOf(
                heatwave,
                movie("seen-1", genres = listOf("Crime"), viewCount = 1),
                movie("seen-2", genres = listOf("Crime"), viewCount = 3),
                movie("unseen", genres = listOf("Crime")),
            ),
        )

        assertEquals("unseen", MoreLikeThis.like(heatwave, input, taste).first().ratingKey)
    }

    @Test
    fun `each pick gets its own list, and an episode's is its show's`() {
        val (input, taste) = setup(
            listOf(
                heatwave,
                movie("other", genres = listOf("Crime")),
                show("harbour", "Night Harbour", genres = listOf("Mystery")),
                show("coast", "Glass Coast", genres = listOf("Mystery")),
                show("bay", "Iron Bay", genres = listOf("Mystery")),
            ),
            episodes = listOf(episode("harbour", 1), episode("coast", 1, viewCount = 1), episode("coast", 2), episode("bay", 1)),
        )
        val picks = listOf(Picked("source", "x"), Picked("harbour-1", "y"))

        val like = MoreLikeThis.forPicks(picks, input, taste)

        assertEquals(setOf("source", "harbour-1"), like.keys)
        assertEquals(listOf("other"), like.getValue("source").map { it.ratingKey })
        val shows = like.getValue("harbour-1")
        assertEquals("a show plays its next episode", setOf("bay-1", "coast-2"), shows.map { it.ratingKey }.toSet())
        assertFalse(shows.any { it.ratingKey.startsWith("harbour") })
        assertEquals(listOf("Mystery, like Night Harbour"), shows.map { it.reason }.distinct())
    }
}
