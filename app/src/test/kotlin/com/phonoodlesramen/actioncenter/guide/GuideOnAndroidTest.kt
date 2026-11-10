package com.phonoodlesramen.actioncenter.guide

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.SyncStateEntity
import java.time.ZoneOffset
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 8: the guide's tests run on the JVM in `:guide-core`. This one runs the engine as the
 * TVs do, on Room's Android path (the platform's SQLite, the classes AppContainer builds),
 * a rule's raw query included.
 */
@RunWith(RobolectricTestRunner::class)
class GuideOnAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plex = Room.inMemoryDatabaseBuilder(context, PlexDatabase::class.java).allowMainThreadQueries().build()
    private val guide = Room.inMemoryDatabaseBuilder(context, GuideDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun tearDown() {
        guide.close()
        plex.close()
    }

    private fun movie(key: String, minutes: Int) = ItemEntity(
        ratingKey = key, sectionKey = "1", type = "movie", title = "Film $key", year = 2018, durationMs = minutes * 60_000L,
        viewCount = 0, lastViewedAt = null, addedAt = 1_780_000_000, updatedAt = 1_780_000_000, contentRating = "PG-13",
        audienceRating = null, rating = null, thumb = null, art = null, genres = "", syncedAt = 1,
        partKey = "/library/parts/$key/1/file.mkv",
    )

    @Test
    fun `the engine makes a lineup, schedules it and previews a rule through Room on Android`() = runBlocking {
        val now = 1_793_047_260_000L
        plex.dao().upsertItems((1..20).map { movie("m$it", minutes = if (it % 2 == 0) 120 else 80) })
        plex.dao().saveSyncState(SyncStateEntity(lastAttemptAt = now, lastSuccessAt = now, lastFullAt = now))
        val engine = GuideEngine(guide, plex, clock = { now }, zone = { ZoneOffset.UTC }, log = {})

        engine.extend()
        val ready = withTimeout(10_000) { engine.card.filterIsInstance<GuideCardState.Ready>().first() }

        assertEquals(listOf("Movies"), ready.channels.map { it.name })
        assertTrue(ready.channels.single().slots.last().endMillis >= now + GuideEngine.HORIZON_MILLIS)
        // A rule's arguments (a type and a length) bound on Android's SQLite: only the 2-hour films.
        val editor = RoomGuideEditor(guide, plex, engine, log = {})
        val preview = editor.preview(ChannelDraft("Long films", ChannelRule(types = listOf(ChannelRule.MOVIE), minMinutes = 100)))
        val programs = preview.filter { !it.filler }
        assertTrue(programs.isNotEmpty())
        assertTrue(programs.all { it.itemKey!!.removePrefix("m").toInt() % 2 == 0 })
    }
}
