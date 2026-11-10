package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import java.lang.ref.WeakReference
import java.time.ZoneOffset
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog lives as long as its library and no longer: a run's library and catalog go
 * when the run ends, so an idle home keeps none of them between the runs after each sync.
 * Made-up titles.
 */
class CatalogTest {
    private val now = 1_793_000_000_000L

    private fun library(): Library {
        val films = (1..40).map { n ->
            PlexItem(
                "film$n", "movie", "Film $n", year = 1980 + n % 40, genres = listOf("Drama", "Crime").take(1 + n % 2),
                collections = listOf("Saga ${n % 4}"), directors = listOf("Director ${n % 7}"), actors = listOf("Actor ${n % 9}"),
                viewCount = n % 3, lastViewedAt = if (n % 3 > 0) now / 1_000 - n * 86_400L else null, addedAt = now / 1_000 - 400 * 86_400L,
            ).toEntity("1", 0)
        }
        val show = PlexItem("show1", "show", "A Show", genres = listOf("Comedy"), addedAt = now / 1_000 - 400 * 86_400L).toEntity("2", 0)
        val episodes = (1..12).map { EpisodeRow("show1-$it", "show1", 1, it, 1_500_000, if (it < 4) 1 else 0, null, null, now / 1_000) }
        return Library(films + show, episodes, emptyList(), emptyList())
    }

    /** A whole run over a library nothing else holds, leaving only a weak reference to its catalog. */
    private fun runOnce(): WeakReference<Catalog> {
        val library = library()
        val picks = SuggestionEngine.picks(library, emptyList(), now, ZoneOffset.UTC, Profiles.OWNER)
        assertTrue("the run picked nothing, so it proves nothing", picks.isNotEmpty())
        return WeakReference(Catalog.of(library))
    }

    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(20) {
            System.gc()
            if (ref.get() == null) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `a run's catalog goes when the run ends`() {
        val catalog = runOnce()
        assertTrue("a finished run's catalog is still held, and with it its library's titles", collected(catalog))
    }

    @Test
    fun `each run's catalog goes, not just the last`() {
        val catalogs = List(3) { runOnce() }
        assertTrue("an earlier run's catalog is still held", catalogs.all(::collected))
    }

    @Test
    fun `a library's catalog is worked out once, and is its own`() {
        val one = library()
        val other = library()
        assertSame(Catalog.of(one), Catalog.of(one))
        assertNotSame(Catalog.of(one), Catalog.of(other))
    }
}
