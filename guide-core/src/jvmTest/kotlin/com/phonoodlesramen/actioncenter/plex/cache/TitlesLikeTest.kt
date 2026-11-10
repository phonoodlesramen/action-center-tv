package com.phonoodlesramen.actioncenter.plex.cache

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Search's fallback while Plex can't be reached (Phase 15): titles on this TV by name. Made-up titles. */
class TitlesLikeTest {
    private val db = Room.inMemoryDatabaseBuilder<PlexDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val dao = db.dao()

    @After
    fun tearDown() = db.close()

    private fun title(key: String, name: String, type: String = "movie") = ItemEntity(
        ratingKey = key, sectionKey = "1", type = type, title = name, year = 2000, durationMs = null, viewCount = 0,
        lastViewedAt = null, addedAt = null, updatedAt = null, contentRating = null, audienceRating = null, rating = null,
        thumb = null, art = null, genres = "", syncedAt = 0,
    )

    @Test
    fun `movies and shows holding the words, shortest title first, in any case, and never an episode`() = runTest {
        dao.upsertItems(
            listOf(
                title("1", "The Harbor at Night"),
                title("2", "Harbor"),
                title("3", "Harbor Watch", type = "show"),
                title("4", "Harbor Days", type = "episode"),
                title("5", "Lighthouse"),
            ),
        )

        assertEquals(listOf("Harbor", "Harbor Watch", "The Harbor at Night"), dao.titlesLike("hARBOR", 10).map { it.title })
        assertEquals(listOf("Harbor"), dao.titlesLike("harbor", 1).map { it.title })
    }

    @Test
    fun `a percent sign or underscore typed is only that character`() = runTest {
        dao.upsertItems(listOf(title("1", "100% Wolf"), title("2", "1000 Wolves"), title("3", "My_Show")))

        assertEquals(listOf("100% Wolf"), dao.titlesLike("100\\%", 10).map { it.title })
        assertEquals(listOf("My_Show"), dao.titlesLike("y\\_s", 10).map { it.title })
    }
}
