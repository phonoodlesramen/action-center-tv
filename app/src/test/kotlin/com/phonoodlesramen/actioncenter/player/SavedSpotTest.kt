package com.phonoodlesramen.actioncenter.player

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.KidGuide
import com.phonoodlesramen.actioncenter.guide.StarterLineup
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved spot (P3): what a stopped player leaves, how long it holds against the lineup,
 * where resuming opens, and its DataStore file, which outlives the process.
 */
class SavedSpotTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val weeknight = SampleGuide.WEEKNIGHT
    private val lineup = SampleGuide.ready(weeknight)
    private val tvMix = lineup.channels.single { it.id == "tv-mix" }

    /** Ridge Line on TV Mix, 8:30-9:22. */
    private val ridgeLine = tvMix.slotAt(weeknight)!!

    /** Paused 12 minutes into Ridge Line at 8:42, as Home leaves it. */
    private val spot = SavedSpot("tv-mix", ridgeLine.startMillis, ridgeLine.itemKey!!, 12 * minute, weeknight + minute)

    // What a stopped player leaves.

    @Test
    fun `a picture held behind live on a program is the spot, where it's held`() {
        val held = BehindLive.pausedLive(ridgeLine, weeknight + minute, positionMs = 12 * minute)
        assertEquals(spot, SavedSpot.of(tvMix, held, weeknight + minute))
        // Saved later, it's still the frame where it was held.
        assertEquals(12 * minute, SavedSpot.of(tvMix, held, weeknight + 5 * minute)?.positionMs)
    }

    @Test
    fun `nothing held, filler, or a picture still playing leaves no spot`() {
        assertNull(SavedSpot.of(tvMix, null, weeknight))
        val trailer = lineup.channels.single { it.id == "scifi" }.slotAt(weeknight)!!
        assertTrue(trailer.filler)
        assertNull(SavedSpot.of(tvMix, BehindLive.pausedLive(trailer, weeknight), weeknight))
        assertNull(SavedSpot.of(tvMix, BehindLive.startedOver(ridgeLine, weeknight), weeknight))
        assertNull(SavedSpot.of(null, BehindLive.pausedLive(ridgeLine, weeknight), weeknight))
    }

    // Resuming.

    @Test
    fun `resuming opens the frame it was left on, paused, behind live by how long it's been`() {
        // Back 9 minutes later, at 8:51.
        val now = weeknight + 10 * minute
        val behind = spot.behindLive(ridgeLine, now)
        assertTrue(behind.paused)
        assertFalse(behind.startedOver)
        assertEquals(12 * minute, behind.positionAt(now))
        assertEquals(12 * minute, behind.positionAt(now + hour))
        // 8:51 is 21 minutes into the slot; the picture is 12 in.
        assertEquals(9 * minute, behind.behindAt(now))
        // Play carries on from there.
        assertEquals(12 * minute + 30_000, behind.resumedAt(now).positionAt(now + 30_000))
    }

    @Test
    fun `a position past the program's end is held at its end`() {
        assertEquals(52 * minute, spot.copy(positionMs = 70 * minute).behindLive(ridgeLine, weeknight).positionMs)
    }

    // How long it holds.

    @Test
    fun `it holds for four hours from when it was saved`() {
        val saved = spot.savedAtMillis
        assertNotNull(SpotRules.backTo(spot, lineup, saved))
        assertNotNull(SpotRules.backTo(spot, lineup, saved + 4 * hour - 1))
        assertNull(SpotRules.backTo(spot, lineup, saved + 4 * hour))
        assertTrue(SpotRules.fresh(spot, saved - minute))
    }

    @Test
    fun `it holds with its channel and program`() {
        val backTo = SpotRules.backTo(spot, lineup, weeknight + 10 * minute)!!
        assertEquals("TV Mix", backTo.channel.name)
        assertEquals(ridgeLine, backTo.slot)
        assertEquals(12f / 52f, backTo.progress, 0.0001f)
    }

    @Test
    fun `it's gone once its program has left the schedule's window`() {
        // Five hours on, the lineup no longer has 8:30's programs.
        val later = SampleGuide.ready(weeknight + 5 * hour)
        assertNull(later.channels.single { it.id == "tv-mix" }.slots.firstOrNull(spot::isFor))
        assertNull(SpotRules.backTo(spot, later, weeknight + 3 * hour))
    }

    @Test
    fun `it's gone when its slot plays something else, its file is gone, or its channel is`() {
        fun withRidgeLine(change: (com.phonoodlesramen.actioncenter.guide.GuideSlot) -> com.phonoodlesramen.actioncenter.guide.GuideSlot) =
            lineup.copy(channels = lineup.channels.map { channel -> channel.copy(slots = channel.slots.map { if (it == ridgeLine) change(it) else it }) })
        assertNull(SpotRules.backTo(spot, withRidgeLine { it.copy(itemKey = "another") }, weeknight))
        assertNull(SpotRules.backTo(spot, withRidgeLine { it.copy(offAir = "This title is no longer in your library") }, weeknight))
        assertNull(SpotRules.backTo(spot, lineup.copy(channels = lineup.channels.filter { it.id != "tv-mix" }), weeknight))
        assertNull(SpotRules.backTo(spot, GuideCardState.Building, weeknight))
        assertNull(SpotRules.backTo(null, lineup, weeknight))
    }

    @Test
    fun `for a kid it holds only on a channel that's kid-safe for them`() {
        val ceiling = StarterLineup.FAMILY_RATINGS.toSet()
        val kids = KidGuide.of(SampleGuide.withKidChannels(weeknight), ceiling)
        assertNull(SpotRules.backTo(spot, kids, weeknight))
        val family = (kids as GuideCardState.Ready).channels.single { it.id == "family" }
        val program = family.slots.first { !it.filler && it.endMillis > weeknight }
        val familySpot = SavedSpot("family", program.startMillis, program.itemKey!!, 5 * minute, weeknight)
        assertEquals("Family", SpotRules.backTo(familySpot, kids, weeknight)?.channel?.name)
    }

    // The DataStore file.

    private fun TestScope.store(file: File, scope: CoroutineScope = backgroundScope) =
        DataStoreSpots(PreferenceDataStoreFactory.create(scope = scope) { file })

    private fun file() = File(Files.createTempDirectory("spots").toFile(), "player_spot.preferences_pb")

    @Test
    fun `saved, it reads back, and clearing forgets it`() = runTest {
        val spots = store(file())
        assertNull(spots.spot.first())
        spots.save(spot)
        assertEquals(spot, spots.spot.first())
        spots.clear()
        assertNull(spots.spot.first())
    }

    @Test
    fun `clearing one spot leaves a newer one`() = runTest {
        val spots = store(file())
        spots.save(spot)
        val newer = spot.copy(positionMs = 20 * minute, savedAtMillis = weeknight + 10 * minute)
        spots.save(newer)
        spots.clear(only = spot)
        assertEquals(newer, spots.spot.first())
        spots.clear(only = newer)
        assertNull(spots.spot.first())
    }

    @Test
    fun `another channel playing forgets it, its own channel doesn't`() = runTest {
        val spots = store(file())
        spots.save(spot)
        spots.channelPlays("tv-mix")
        assertEquals(spot, spots.spot.first())
        spots.channelPlays("classics")
        assertNull(spots.spot.first())
        // With nothing saved, nothing happens.
        spots.channelPlays("movies")
        assertNull(spots.spot.first())
    }

    @Test
    fun `it survives the process, as a new store on the same file reads it`() = runTest {
        val file = file()
        val first = Job()
        store(file, CoroutineScope(backgroundScope.coroutineContext + first)).save(spot)
        // The first process's store is gone.
        first.cancelAndJoin()
        assertEquals(spot, store(file).spot.first())
    }

    @Test
    fun `the memory store follows the same rules`() = runTest {
        val spots = MemorySpots(spot)
        spots.channelPlays("tv-mix")
        assertEquals(spot, spots.value)
        spots.clear(only = spot.copy(positionMs = 1))
        assertEquals(spot, spots.value)
        spots.channelPlays("movies")
        assertNull(spots.value)
    }
}
