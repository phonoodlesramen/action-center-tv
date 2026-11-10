package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideEngine
import com.phonoodlesramen.actioncenter.player.FakeGuideVideo.Companion.play
import com.phonoodlesramen.actioncenter.player.FakeGuideVideo.Companion.seek
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
 * Phase 7 in the tuner, against [SampleGuide]'s weeknight lineup (8:41 PM UTC) with a fake
 * player and virtual time, as [GuideTunerTest]: ⏪ and ⏩ (P1), the banner's control row (P2),
 * and the spot a stopped player holds and a resumed one opens (P3).
 *
 * TV Mix at 8:41 is Ridge Line (8:30-9:22), 11 minutes in; the fake player can't say where
 * it is, so the picture is where the schedule, or behind live, says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideTunerControlsTest {
    private val minute = 60_000L
    private val second = 1_000L
    private val weeknight = SampleGuide.WEEKNIGHT
    private val lineup = SampleGuide.ready(weeknight)
    private val ridgeLine = lineup.channels.single { it.id == "tv-mix" }.slotAt(weeknight)!!

    private class Rig(scope: TestScope, val lineup: MutableStateFlow<GuideCardState>, startMillis: Long) {
        val sources = FakeSlotSources()
        val videos = mutableListOf<FakeGuideVideo>()
        val logs = mutableListOf<String>()
        val clock = { startMillis + scope.testScheduler.currentTime }
        val tuner = GuideTuner(
            lineup = lineup,
            sources = sources,
            openVideo = { events -> FakeGuideVideo(events).also { videos += it } },
            context = StandardTestDispatcher(scope.testScheduler),
            clock = clock,
            log = { logs += it },
        )
        val video get() = videos.last()
        val state get() = tuner.state.value
        val seeks get() = video.calls.filter { it.startsWith("seek") }
        val plays get() = video.calls.filter { it.startsWith("play") }
    }

    private fun TestScope.rig(
        channel: String? = null,
        now: Long = weeknight,
        resume: SavedSpot? = null,
        setUp: Rig.() -> Unit = {},
        test: Rig.() -> Unit,
    ) {
        val rig = Rig(this, MutableStateFlow(SampleGuide.ready(weeknight)), now)
        rig.setUp()
        try {
            rig.tuner.select(channel, resume = resume)
            rig.tuner.start()
            runCurrent()
            rig.test()
        } finally {
            rig.tuner.close()
        }
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    // P1: a tap of ⏪ or ⏩.

    @Test
    fun `a tap of rewind goes back 10 s, behind live, and the banner says so`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            advance(6 * second)
            assertFalse(state.banner)
            tuner.skip(forward = false)
            // Live is 11:06 in.
            assertEquals(listOf(seek(11 * minute - 4 * second)), seeks)
            val behind = state.behind!!
            assertFalse(behind.paused)
            assertFalse(behind.startedOver)
            assertEquals(10 * second, behind.behindAt(clock()))
            assertEquals("Ridge Line", state.slot?.title)
            assertTrue(state.banner)
            // The file isn't opened again.
            assertEquals(1, plays.size)
        }
    }

    @Test
    fun `rewind stops at the program's start`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            video.position = 4 * second
            tuner.skip(forward = false)
            assertEquals(listOf(seek(0)), seeks)
            assertEquals(11 * minute, state.behind!!.behindAt(clock()))
        }
    }

    @Test
    fun `fast forward goes 30 s on behind live, and as far as live is Back to live`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            repeat(4) { tuner.skip(forward = false) }
            assertEquals(40 * second, state.behind!!.behindAt(clock()))
            tuner.skip(forward = true)
            assertEquals(seek(11 * minute - 10 * second), seeks.last())
            assertEquals(10 * second, state.behind!!.behindAt(clock()))
            // 30 s on would pass live: it's live, the program tuned in where it is now.
            tuner.skip(forward = true)
            runCurrent()
            assertNull(state.behind)
            assertEquals(play("sample-tv-mix-3", 11 * minute), video.calls.last())
            assertTrue(state.banner)
        }
    }

    @Test
    fun `at live, fast forward only shows the banner`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            advance(6 * second)
            tuner.skip(forward = true)
            assertTrue(seeks.isEmpty())
            assertNull(state.behind)
            assertTrue(state.banner)
        }
    }

    @Test
    fun `nothing seeks on filler, the Up next card, an off-air card, or before frames move`() = runTest {
        // Sci-Fi at 8:41: a trailer plays.
        rig("scifi") {
            video.events.onPlaying(true)
            tuner.skip(forward = false)
            tuner.scrub(forward = false, heldMillis = 3_000)
            tuner.endScrub()
            assertTrue(seeks.isEmpty())
            assertNull(state.behind)
            assertNull(state.scrub)
            assertFalse(state.canSeek)
        }
        // Comedy at 8:55: the Up next card.
        rig("comedy", now = weeknight + 14 * minute) {
            tuner.skip(forward = false)
            assertNull(state.behind)
            assertEquals(Picture.UpNext, state.picture)
        }
        // Classics at 12:41 AM: off air.
        rig("classics", now = weeknight + 4 * 60 * minute) {
            tuner.skip(forward = false)
            assertNull(state.behind)
        }
        // TV Mix still tuning in.
        rig("tv-mix") {
            tuner.skip(forward = false)
            assertTrue(seeks.isEmpty())
            assertNull(state.behind)
        }
    }

    @Test
    fun `seeking while paused stays paused, on the new frame`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.pause()
            advance(minute)
            tuner.skip(forward = false)
            assertEquals(listOf(seek(11 * minute - 10 * second)), seeks)
            val behind = state.behind!!
            assertTrue(behind.paused)
            assertEquals(minute + 10 * second, behind.behindAt(clock()))
            assertEquals("pause", video.calls[1])
            // Still held, the banner still up, ten minutes on.
            advance(10 * minute)
            assertTrue(state.banner)
            assertTrue(state.behind!!.paused)
        }
    }

    @Test
    fun `behind live after a seek, nothing switches at the slot's end, and the program's end at this pace rejoins live`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.skip(forward = false)
            runCurrent()
            // Ridge Line's slot ends at 9:22; 10 s behind, its end comes at 9:22:10.
            advance(41 * minute)
            assertEquals(1, plays.size)
            assertTrue(video.calls.none { it.startsWith("prepare") })
            assertEquals("Ridge Line", state.slot?.title)
            advance(10 * second)
            assertNull(state.behind)
            // What's on now: the Up next card before Kitchen Table.
            assertEquals(Picture.UpNext, state.picture)
        }
    }

    // P1: holding ⏪ or ⏩.

    @Test
    fun `holding moves the marker without seeking, faster the longer it's held, and letting go seeks once`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.scrub(forward = false, heldMillis = 500)
            assertEquals(Scrub(forward = false, speed = 2, fromMs = 11 * minute, targetMs = 11 * minute - 15 * second), state.scrub)
            assertTrue(state.banner)
            tuner.scrub(forward = false, heldMillis = 2_500)
            assertEquals(4, state.scrub?.speed)
            assertEquals(11 * minute - 90 * second, state.scrub?.targetMs)
            tuner.scrub(forward = false, heldMillis = 4_500)
            assertEquals(8, state.scrub?.speed)
            assertEquals(7 * minute, state.scrub?.targetMs)
            assertTrue(seeks.isEmpty())
            assertNull(state.behind)
            // Held, the banner stays.
            advance(10 * second)
            assertTrue(state.banner)
            tuner.endScrub()
            assertEquals(listOf(seek(7 * minute)), seeks)
            assertNull(state.scrub)
            assertEquals(4 * minute + 10 * second, state.behind!!.behindAt(clock()))
            // Let go, the banner hides 5 s later.
            advance(GuideTuning.BANNER_MILLIS)
            assertFalse(state.banner)
        }
    }

    @Test
    fun `the marker stops at the program's start`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.scrub(forward = false, heldMillis = 10_000)
            assertEquals(0L, state.scrub?.targetMs)
            tuner.endScrub()
            assertEquals(listOf(seek(0)), seeks)
        }
    }

    @Test
    fun `holding fast forward does nothing live, and behind live it stops at live, where letting go is Back to live`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.scrub(forward = true, heldMillis = 500)
            assertNull(state.scrub)
            assertTrue(state.banner)
            // Two minutes behind.
            tuner.scrub(forward = false, heldMillis = 3_000)
            tuner.endScrub()
            assertEquals(2 * minute, state.behind!!.behindAt(clock()))
            tuner.scrub(forward = true, heldMillis = 5_000)
            assertEquals(11 * minute, state.scrub?.targetMs)
            tuner.endScrub()
            runCurrent()
            assertNull(state.behind)
            assertEquals(play("sample-tv-mix-3", 11 * minute), video.calls.last())
        }
    }

    // P2: the control row.

    @Test
    fun `OK puts the control row on the banner, and it goes with it 5 s after the last press`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            advance(6 * second)
            tuner.showControls()
            assertTrue(state.banner)
            assertTrue(state.showsControls)
            advance(4 * second)
            tuner.keepControls()
            advance(4 * second)
            assertTrue(state.showsControls)
            advance(1 * second)
            assertFalse(state.banner)
            assertFalse(state.controls)
        }
    }

    @Test
    fun `over filler or a card, OK shows the banner without the row`() = runTest {
        rig("scifi") {
            video.events.onPlaying(true)
            tuner.showControls()
            assertTrue(state.banner)
            assertFalse(state.showsControls)
        }
        rig("comedy", now = weeknight + 14 * minute) {
            tuner.showControls()
            assertTrue(state.banner)
            assertFalse(state.showsControls)
        }
    }

    @Test
    fun `while paused the row stays with the banner, and Back takes the row but not the paused banner`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.showControls()
            tuner.playPause()
            advance(10 * minute)
            assertTrue(state.showsControls)
            tuner.hideControls()
            assertFalse(state.controls)
            assertTrue(state.banner)
            // Live again, Back takes the banner too.
            tuner.backToLive()
            runCurrent()
            video.events.onPlaying(true)
            tuner.showControls()
            tuner.hideControls()
            assertFalse(state.banner)
        }
    }

    @Test
    fun `a channel change or the options take the row away`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.showControls()
            tuner.surf(+1)
            assertTrue(state.banner)
            assertFalse(state.controls)
            advance(GuideTuning.SURF_DEBOUNCE_MILLIS)
            video.events.onPlaying(true)
            tuner.showControls()
            assertTrue(state.showsControls)
            tuner.openOptions()
            assertFalse(state.banner)
            assertFalse(state.controls)
        }
    }

    // P3: stopping holds the spot; a spot opens paused.

    @Test
    fun `stopping while a program plays holds it there, paused behind live, and coming back opens that frame paused`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            advance(minute)
            tuner.stop()
            val held = state.behind!!
            assertTrue(held.paused)
            assertEquals(12 * minute, held.positionMs)
            assertEquals(SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 12 * minute, clock()), SavedSpot.of(state.channel, held, clock()))
            assertTrue(logs.any { "held at 720 s" in it })
            advance(10 * minute)
            tuner.start()
            runCurrent()
            assertEquals(listOf(play("sample-tv-mix-3", 12 * minute), "pause"), video.calls)
            assertEquals(Picture.Video, state.picture)
            assertTrue(state.banner)
            assertEquals(10 * minute, state.behind!!.behindAt(clock()))
        }
    }

    @Test
    fun `filler, a card, or a picture still tuning in hold nothing`() = runTest {
        rig("scifi") {
            video.events.onPlaying(true)
            tuner.stop()
            assertNull(state.behind)
            assertNull(SavedSpot.of(state.channel, state.behind, clock()))
        }
        rig("tv-mix") {
            tuner.stop()
            assertNull(state.behind)
        }
    }

    @Test
    fun `a saved spot opens on its frame, paused behind live, and Play carries on`() = runTest {
        val spot = SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 2 * minute, weeknight - 9 * minute)
        rig("tv-mix", resume = spot) {
            assertEquals(listOf(play("sample-tv-mix-3", 2 * minute), "pause"), video.calls)
            val behind = state.behind!!
            assertTrue(behind.paused)
            assertFalse(behind.startedOver)
            assertEquals(9 * minute, behind.behindAt(clock()))
            assertEquals(Picture.Video, state.picture)
            assertTrue(state.banner)
            assertEquals("Ridge Line", state.slot?.title)
            // Paused, the banner stays.
            advance(minute)
            assertTrue(state.banner)
            tuner.playPause()
            assertEquals("resume", video.calls.last())
            assertFalse(state.behind!!.paused)
            assertEquals(10 * minute, state.behind!!.behindAt(clock()))
        }
    }

    @Test
    fun `a spot whose program has gone, or that's for another channel, tunes in live`() = runTest {
        val gone = SavedSpot("tv-mix", ridgeLine.startMillis - 60 * minute, "sample-tv-mix-3", 2 * minute, weeknight)
        rig("tv-mix", resume = gone) {
            assertEquals(listOf(play("sample-tv-mix-3", 11 * minute)), video.calls)
            assertNull(state.behind)
        }
        val elsewhere = SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 2 * minute, weeknight)
        rig("movies", resume = elsewhere) {
            assertEquals(listOf(play("sample-movies-1", 11 * minute)), video.calls)
            assertNull(state.behind)
        }
    }

    @Test
    fun `a spot whose file can't be had now falls back to the channel live, with its usual card`() = runTest {
        val spot = SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 2 * minute, weeknight)
        rig("tv-mix", resume = spot, setUp = { sources.missing["sample-tv-mix-3"] = SlotSource.NoFile }) {
            assertNull(state.behind)
            assertEquals(Picture.OffAir(OffAirReason.OFF_AIR, GuideEngine.GONE), state.picture)
            assertTrue(video.calls.isEmpty())
        }
    }
}
