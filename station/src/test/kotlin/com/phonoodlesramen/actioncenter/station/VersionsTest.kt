package com.phonoodlesramen.actioncenter.station

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.GuideDatabase
import com.phonoodlesramen.actioncenter.guide.GuideEngine
import com.phonoodlesramen.actioncenter.guide.RoomGuideEditor
import com.phonoodlesramen.actioncenter.guide.station.ChannelPatch
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.SyncStateEntity
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VersionsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `it starts one past the version it kept, and keeps each bump`() {
        val file = temp.root.toPath().resolve("version")
        val first = Versions(file)
        assertEquals(1L, first.current)
        assertEquals(2L, first.bump())
        assertEquals(3L, first.bump())
        // A restart moves on by one: a TV never takes the restarted station's guide for its own.
        assertEquals(4L, Versions(file).current)
    }

    @Test
    fun `changes answers at once when the version is already another`() {
        val versions = Versions(null)
        versions.bump()
        assertEquals(versions.current, versions.awaitChange(versions.current - 1, 60_000L))
        // Lower than the TV's (a station restored from a backup) counts as changed too.
        assertEquals(versions.current, versions.awaitChange(versions.current + 10, 60_000L))
    }

    @Test
    fun `changes waits for the next change`() {
        val versions = Versions(null)
        val after = versions.current
        val waiting = CompletableFuture.supplyAsync { versions.awaitChange(after, 10_000L) }
        Thread.sleep(100)
        assertFalse(waiting.isDone)
        val bumped = versions.bump()
        assertEquals(bumped, waiting.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `changes gives up after its time with no change`() {
        val versions = Versions(null)
        val started = System.nanoTime()
        assertNull(versions.awaitChange(versions.current, 200L))
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(190))
    }

    @Test
    fun `closing answers every waiting call`() {
        val versions = Versions(null)
        val waiting = CompletableFuture.supplyAsync { versions.awaitChange(versions.current, 60_000L) }
        Thread.sleep(100)
        versions.close()
        assertNull(waiting.get(5, TimeUnit.SECONDS))
    }
}

/** The guide's version moves on when what the TVs would read changes, and only then. */
class GuideServiceTest {
    private var now = 1_793_047_260_000L
    private val plex = Room.inMemoryDatabaseBuilder<PlexDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val guide = Room.inMemoryDatabaseBuilder<GuideDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val engine = GuideEngine(
        guide, plex, clock = { now }, zone = { ZoneOffset.UTC }, io = Dispatchers.IO, log = {}, horizonMillis = StationApi.HORIZON_MILLIS,
    )
    private val versions = Versions(null)
    private val service = GuideService(guide, plex, RoomGuideEditor(guide, plex, engine, log = {}), versions, { now }, ZoneOffset.UTC)
    private val movies = ChannelDraft("Movies", ChannelRule(types = listOf(ChannelRule.MOVIE)))

    @After
    fun tearDown() {
        guide.close()
        plex.close()
    }

    private suspend fun synced() {
        plex.dao().upsertItems(Library.films)
        plex.dao().saveSyncState(SyncStateEntity(lastAttemptAt = now, lastSuccessAt = now, itemsUpdatedSince = now / 1000))
    }

    @Test
    fun `reading an unchanged guide keeps its version`() = runTest {
        val start = versions.current
        assertEquals(start, service.refresh())
        assertEquals(start, service.refresh())
        assertEquals(start, service.guide()!!.version)
    }

    @Test
    fun `a new channel moves the version on, with two days of its schedule in the guide`() = runTest {
        synced()
        service.refresh()
        val start = versions.current
        val created = service.create(movies)
        assertEquals(start + 1, created.version)
        val channel = service.guide()!!.channels.single()
        assertEquals(created.id, channel.id)
        assertEquals("Movies", channel.name)
        assertTrue(channel.enabled)
        assertTrue(channel.slots.last().endMillis >= now + StationApi.HORIZON_MILLIS)
        assertTrue(channel.slots.first().startMillis <= now + 5 * 60_000L)
        assertEquals(listOf("PG"), channel.contentRatings)
        assertEquals(ZoneOffset.UTC.id, service.guide()!!.timeZone)
    }

    @Test
    fun `an edit that changes nothing keeps the version`() = runTest {
        synced()
        val id = service.create(movies).id
        val after = versions.current
        assertEquals(after, service.patch(id, ChannelPatch(name = "Movies")).version)
        assertEquals(after + 1, service.patch(id, ChannelPatch(name = "Films")).version)
        assertEquals("Films", service.guide()!!.channels.single().name)
    }

    @Test
    fun `a channel turned off stays in the guide with its schedule`() = runTest {
        synced()
        val id = service.create(movies).id
        service.patch(id, ChannelPatch(enabled = false))
        val channel = service.guide()!!.channels.single()
        assertFalse(channel.enabled)
        assertTrue(channel.slots.isNotEmpty())
    }

    @Test
    fun `the guide reaches back an hour, so the program on now is always in it`() = runTest {
        synced()
        val id = service.create(movies).id
        now += 5 * 60 * 60_000L
        engine.extend()
        service.refresh()
        // Extending also adds the Family channel once (K8): this test looks at its own.
        val slots = service.guide()!!.channels.single { it.id == id }.slots
        assertTrue(slots.all { it.endMillis > now - StationApi.BEHIND_MILLIS })
        assertTrue(slots.any { it.startMillis <= now && it.endMillis > now })
    }

    @Test
    fun `a sync that changes nothing the guide shows keeps the version, and a title losing its file moves it`() = runTest {
        synced()
        service.create(movies)
        val after = versions.current
        synced()
        assertEquals(after, service.refresh())

        val onNow = service.guide()!!.channels.single().slots.first { !it.filler }
        plex.dao().upsertItems(listOf(Library.films.first { it.ratingKey == onNow.itemKey }.copy(partKey = null)))
        assertEquals(after + 1, service.refresh())
        assertEquals(GuideEngine.NO_FILE, service.guide()!!.channels.single().slots.first { it.itemKey == onNow.itemKey }.offAir)
    }

    @Test
    fun `a preview shows a day, though the station schedules two`() = runTest {
        synced()
        val preview = service.preview(movies)
        assertTrue(preview.slots.isNotEmpty())
        assertTrue(preview.slots.all { it.startMillis < now + 24 * 60 * 60_000L })
        assertTrue(preview.slots.last().endMillis >= now + 22 * 60 * 60_000L)
    }
}
