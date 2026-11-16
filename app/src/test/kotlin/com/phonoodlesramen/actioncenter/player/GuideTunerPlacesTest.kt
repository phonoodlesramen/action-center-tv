package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.player.FakeGuideVideo.Companion.play
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping your place (P5) in the tuner, against [SampleGuide]'s weeknight lineup with a fake
 * player and virtual time: TV Mix at 8:41 PM is Ridge Line (8:30-9:22, 52 minutes, no file
 * length, so the slot's), 11 minutes in; Channel up from TV Mix (3) is Classics (4). The fake
 * player can't say where it is, so the picture is where the schedule, or behind live, says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideTunerPlacesTest {
    private val minute = 60_000L
    private val weeknight = SampleGuide.WEEKNIGHT
    private val ridgeLineKey = "sample-tv-mix-3"
    private val ridgeLine = SampleGuide.ready(weeknight).channels.single { it.id == "tv-mix" }.slotAt(weeknight)!!

    private class Rig(scope: TestScope, startMillis: Long, val kept: MutableStateFlow<SavedSpot?>) {
        val videos = mutableListOf<FakeGuideVideo>()
        val leftOff = mutableListOf<Pair<String, Long>>()
        val clock = { startMillis + scope.testScheduler.currentTime }
        val tuner = GuideTuner(
            lineup = MutableStateFlow<GuideCardState>(SampleGuide.ready(startMillis)),
            sources = FakeSlotSources(),
            openVideo = { events -> FakeGuideVideo(events).also { videos += it } },
            context = StandardTestDispatcher(scope.testScheduler),
            clock = clock,
            log = {},
            kept = kept,
            onLeftOff = { slot: GuideSlot, position: Long -> leftOff += slot.itemKey!! to position },
        )
        val video get() = videos.last()
        val state get() = tuner.state.value
        val plays get() = video.calls.filter { it.startsWith("play") }
    }

    private fun TestScope.rig(channel: String = "tv-mix", kept: SavedSpot? = null, test: Rig.() -> Unit) {
        val rig = Rig(this, weeknight, MutableStateFlow(kept))
        try {
            rig.tuner.select(channel)
            rig.tuner.start()
            runCurrent()
            rig.video.events.onPlaying(true)
            rig.test()
        } finally {
            rig.tuner.close()
        }
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    /** Channel up, then the debounce, and frames moving on the new channel. */
    private fun TestScope.surf(rig: Rig, step: Int) {
        rig.tuner.surf(step)
        advance(GuideTuning.SURF_DEBOUNCE_MILLIS)
        rig.video.events.onPlaying(true)
    }

    @Test
    fun `a slip of Channel up and straight back carries on where it was`() = runTest {
        rig {
            advance(5 * minute)
            surf(this, +1)
            assertEquals("classics", state.channel?.id)
            advance(minute)
            tuner.surf(-1)
            advance(GuideTuning.SURF_DEBOUNCE_MILLIS)
            // Left at 16:00 in, a minute ago: Ridge Line from there, playing, a minute behind live.
            assertEquals(play(ridgeLineKey, 16 * minute), plays.last())
            val behind = state.behind!!
            assertTrue(behind.pickedUp)
            assertFalse(behind.paused)
            assertNull(state.pickUp)
        }
    }

    @Test
    fun `back later, it tunes in live and the pick-up card offers where you left it`() = runTest {
        rig {
            advance(5 * minute)
            surf(this, +1)
            advance(3 * minute)
            tuner.surf(-1)
            advance(GuideTuning.SURF_DEBOUNCE_MILLIS)
            // Two surfs' debounces on from 8:46: live is 19:00.8 in.
            assertEquals(play(ridgeLineKey, 19 * minute + 2 * GuideTuning.SURF_DEBOUNCE_MILLIS), plays.last())
            assertNull(state.behind)
            assertEquals(16 * minute, state.pickUp?.place?.positionMs)

            assertTrue(tuner.takePickUp())
            runCurrent()
            assertEquals(play(ridgeLineKey, 16 * minute), plays.last())
            assertTrue(state.behind!!.pickedUp)
            assertNull(state.pickUp)
        }
    }

    @Test
    fun `the pick-up card goes by itself, or with Back, and the channel carries on live`() = runTest {
        rig {
            advance(5 * minute)
            surf(this, +1)
            advance(3 * minute)
            surf(this, -1)
            assertTrue(state.pickUp != null)
            advance(PlaceRules.CARD_MILLIS)
            assertNull(state.pickUp)
            assertNull(state.behind)

            surf(this, +1)
            advance(3 * minute)
            surf(this, -1)
            assertTrue(state.pickUp != null)
            tuner.dismissPickUp()
            assertNull(state.pickUp)
            assertFalse(tuner.takePickUp())
        }
    }

    @Test
    fun `surfing past leaves nothing to pick up`() = runTest {
        rig {
            advance(minute)
            surf(this, +1)
            advance(3 * minute)
            surf(this, -1)
            assertNull(state.pickUp)
            assertNull(state.behind)
        }
    }

    @Test
    fun `Back keeps a program you were watching, and a peek leaves nothing`() = runTest {
        rig {
            advance(minute)
            assertNull(tuner.placeToKeep())
            advance(4 * minute)
            assertEquals(SavedSpot("tv-mix", ridgeLine.startMillis, ridgeLineKey, 16 * minute, weeknight + 5 * minute), tuner.placeToKeep())
        }
    }

    @Test
    fun `pausing, even a moment in, is watching`() = runTest {
        rig {
            advance(10_000)
            tuner.pause()
            assertEquals(11 * minute + 10_000, tuner.placeToKeep()?.positionMs)
        }
    }

    @Test
    fun `the home's place is offered on its channel as it tunes in`() = runTest {
        val spot = SavedSpot("tv-mix", ridgeLine.startMillis, ridgeLineKey, 2 * minute, weeknight - 9 * minute)
        rig(kept = spot) {
            assertEquals(play(ridgeLineKey, 11 * minute), plays.last())
            assertEquals(2 * minute, state.pickUp?.place?.positionMs)
            assertTrue(tuner.takePickUp())
            runCurrent()
            assertEquals(play(ridgeLineKey, 2 * minute), plays.last())
        }
    }

    @Test
    fun `the home's place arriving a moment after the tune-in is still offered`() = runTest {
        rig {
            assertNull(state.pickUp)
            kept.value = SavedSpot("tv-mix", ridgeLine.startMillis, ridgeLineKey, 2 * minute, weeknight - 9 * minute)
            runCurrent()
            assertEquals(2 * minute, state.pickUp?.place?.positionMs)
        }
    }

    @Test
    fun `another channel's place isn't offered here`() = runTest {
        rig(channel = "classics", kept = SavedSpot("tv-mix", ridgeLine.startMillis, ridgeLineKey, 2 * minute, weeknight)) {
            assertNull(state.pickUp)
        }
    }

    @Test
    fun `Plex hears where you left a program you watched from its start, not one you joined midway`() = runTest {
        rig {
            // Joined 11 minutes in, five minutes watched: nothing.
            advance(5 * minute)
            surf(this, +1)
            assertEquals(emptyList<Pair<String, Long>>(), leftOff)

            surf(this, -1)
            tuner.startOver()
            runCurrent()
            video.events.onPlaying(true)
            advance(20 * minute)
            surf(this, +1)
            assertEquals(listOf(ridgeLineKey to 20 * minute), leftOff)
        }
    }
}
