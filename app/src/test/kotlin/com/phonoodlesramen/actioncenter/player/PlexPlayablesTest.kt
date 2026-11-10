package com.phonoodlesramen.actioncenter.player

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.plex.PlexHeaders
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexMedia
import com.phonoodlesramen.actioncenter.plex.PlexPage
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import com.phonoodlesramen.actioncenter.plex.PlexSection
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.SHIELD_PLEX_DEVICE
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.toEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where a guide slot's file comes from, and its length (G20): a trailer isn't cached, so its
 * length comes from its details, and one joined after it's over shows the Up next card
 * ([GuideTuning.startPosition], tested in [GuideTunerTest]).
 */
@RunWith(RobolectricTestRunner::class)
class PlexPlayablesTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PlexDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val access = ServerAccess("https://10-0-0-5.abc.plex.direct:32400/", "server-secret")

    @After
    fun tearDown() = db.close()

    private class FakeServer(val details: Map<String, PlexItem>) : PlexServerApi {
        val asked = mutableListOf<String>()
        override suspend fun continueWatching(access: ServerAccess, count: Int) = emptyList<PlexItem>()
        override suspend fun sections(access: ServerAccess) = emptyList<PlexSection>()
        override suspend fun items(access: ServerAccess, section: String, type: Int, start: Int, size: Int, updatedSince: Long?) =
            PlexPage(emptyList<PlexItem>(), 0, 0)
        override suspend fun history(access: ServerAccess, sinceSeconds: Long, accountId: Int, size: Int) = emptyList<PlexPlay>()
        override suspend fun metadata(access: ServerAccess, ratingKey: String): PlexItem? {
            asked += ratingKey
            return details[ratingKey]
        }
    }

    private fun playables(server: PlexServerApi) = PlexPlayables(flowOf(access), db.dao(), server, PlexHeaders({ "client-1" }, "0.3.0", SHIELD_PLEX_DEVICE))

    private val trailer = PlexItem("1073", "clip", "Big Atlas trailer", durationMs = 150_000, media = PlexMedia("/library/parts/1073/1/trailer.mp4"))

    @Test
    fun `a trailer's file and its length come from its details`() = runTest {
        val server = FakeServer(mapOf("1073" to trailer))

        val source = playables(server).source("1073")!!

        assertEquals("https://10-0-0-5.abc.plex.direct:32400/library/parts/1073/1/trailer.mp4", source.uri)
        assertEquals(150_000L, source.durationMs)
        assertEquals(listOf("1073"), server.asked)
    }

    @Test
    fun `a cached title plays from the cache without asking the server`() = runTest {
        val movie = PlexItem("3", "movie", "Quiet Field", durationMs = 7_860_000, media = PlexMedia("/library/parts/3/1/file.mkv"))
        db.dao().upsertItems(listOf(movie.toEntity("1", 0)))
        val server = FakeServer(emptyMap())

        val source = playables(server).source("3")!!

        assertEquals(7_860_000L, source.durationMs)
        assertEquals(emptyList<String>(), server.asked)
    }

    @Test
    fun `details without a file, or a title that's gone, have nothing to play`() = runTest {
        val server = FakeServer(mapOf("1074" to trailer.copy(ratingKey = "1074", media = null)))

        assertNull(playables(server).source("1074"))
        assertNull(playables(server).source("9999"))
    }
}
