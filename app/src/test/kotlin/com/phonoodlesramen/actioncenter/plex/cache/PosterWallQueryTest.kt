package com.phonoodlesramen.actioncenter.plex.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.media.SharedFolders
import com.phonoodlesramen.actioncenter.wallpaper.AndroidWallpaperSources
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Poster wall (L1) reads random posters from the Plex cache. */
@RunWith(RobolectricTestRunner::class)
class PosterWallQueryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PlexDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.dao()

    @After
    fun tearDown() = db.close()

    private fun item(key: String, type: String, thumb: String?) =
        ItemEntity(key, "1", type, key, 2000, null, 0, null, 0, null, null, null, null, thumb, "/art/$key", "", 0)

    @Test
    fun postersComeFromMoviesAndShowsWithAThumbOnly() = runTest {
        dao.upsertItems(
            (1..20).map { item("m$it", "movie", "/library/metadata/m$it/thumb") } +
                (1..10).map { item("s$it", "show", "/library/metadata/s$it/thumb") } +
                listOf(item("bare", "movie", null), item("ep", "episode", "/episode/thumb")),
        )
        val all = dao.randomPosters(100)
        assertEquals(30, all.size)
        assertEquals(30, all.toSet().size)
        assertTrue(all.none { it == "/episode/thumb" })
        assertTrue(all.any { "/s" in it } && all.any { "/m" in it })
        assertEquals(12, dao.randomPosters(12).size)
    }

    @Test
    fun theOrderChangesFromOneAskToTheNext() = runTest {
        dao.upsertItems((1..40).map { item("m$it", "movie", "/thumb/$it") })
        val orders = (1..5).map { dao.randomPosters(40) }.toSet()
        assertTrue("five shuffles of 40 shouldn't all match", orders.size > 1)
    }

    @Test
    fun anEmptyCacheHasNoPosters() = runTest {
        assertEquals(emptyList<String>(), dao.randomPosters(30))
    }

    @Test
    fun theWallpaperSourcesAskTheCache() = runTest {
        dao.upsertItems((1..3).map { item("m$it", "movie", "/thumb/$it") })
        val sources = AndroidWallpaperSources(SharedFolders(File("missing")) { false }) { dao.randomPosters(it) }
        assertEquals(setOf("/thumb/1", "/thumb/2", "/thumb/3"), sources.posters(30).toSet())
        assertEquals(2, sources.posters(2).size)
    }
}
