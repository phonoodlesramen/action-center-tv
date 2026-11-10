package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guide viewing in Plex (Phase 11, K1, K2) and the credits card (K5), against
 * [SampleGuide]'s TV Mix: Ridge Line, 8:30 to 9:22 (52 minutes, no file length, so the
 * slot's), joined live at 8:41. Its watched point without a marker is 90%, 46:48 in; three
 * quarters is 39 minutes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideTunerViewingTest {
    private val minute = 60_000L
    private val ridgeLine = "sample-tv-mix-3"

    private class Rig(scope: TestScope, startMillis: Long, val credits: Map<String, Long>, val next: Map<String, NextEpisode>) {
        val videos = mutableListOf<FakeGuideVideo>()
        val watched = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val tuner = GuideTuner(
            lineup = MutableStateFlow<GuideCardState>(SampleGuide.ready(SampleGuide.WEEKNIGHT)),
            sources = FakeSlotSources(),
            openVideo = { events -> FakeGuideVideo(events).also { videos += it } },
            context = StandardTestDispatcher(scope.testScheduler),
            clock = { startMillis + scope.testScheduler.currentTime },
            log = { logs += it },
            credits = SlotCredits { credits[it] },
            nextEpisode = { next[it] },
            onWatched = { watched += it.itemKey!! },
        )
        val video get() = videos.last()
        val state get() = tuner.state.value
    }

    private fun TestScope.rig(
        channel: String = "tv-mix",
        now: Long = SampleGuide.WEEKNIGHT,
        credits: Map<String, Long> = emptyMap(),
        next: Map<String, NextEpisode> = emptyMap(),
        test: Rig.() -> Unit,
    ) {
        val rig = Rig(this, now, credits, next)
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

    /** Start over: Ridge Line from its start, its frames playing. */
    private fun TestScope.startOver(rig: Rig) {
        rig.tuner.startOver()
        runCurrent()
    }

    @Test
    fun `the whole program counts once, at 90 percent without a credits marker`() = runTest {
        rig {
            startOver(this)
            advance(46 * minute)
            assertTrue(watched.isEmpty())
            advance(minute)
            assertEquals(listOf(ridgeLine), watched)

            advance(5 * minute)
            assertEquals("once only", listOf(ridgeLine), watched)
            assertTrue(logs.any { "$ridgeLine watched" in it })
        }
    }

    @Test
    fun `with a credits marker, it counts at the credits`() = runTest {
        rig(credits = mapOf(ridgeLine to 44 * minute)) {
            startOver(this)
            advance(44 * minute - 5_000)
            assertTrue(watched.isEmpty())
            advance(5_000)
            assertEquals(listOf(ridgeLine), watched)
        }
    }

    @Test
    fun `joined 11 minutes in, it counts once three quarters has played, past the watched point`() = runTest {
        rig {
            // 9:16:48 is 90%, but only 35:48 has played; 39 minutes have at 9:20.
            advance(36 * minute)
            assertTrue(watched.isEmpty())
            advance(3 * minute)
            assertEquals(listOf(ridgeLine), watched)
        }
    }

    @Test
    fun `joined too late, catching the end counts for nothing`() = runTest {
        // 8:55: Ridge Line is 25 minutes in, so at most 27 minutes of it can play.
        rig(now = SampleGuide.WEEKNIGHT + 14 * minute) {
            advance(27 * minute)
            assertTrue(watched.isEmpty())
        }
    }

    @Test
    fun `paused time adds nothing`() = runTest {
        rig(now = SampleGuide.WEEKNIGHT + 14 * minute) {
            advance(10 * minute)
            tuner.pause()
            advance(30 * minute)
            tuner.resume()
            video.events.onPlaying(true)
            runCurrent()
            // Behind live, the last 17 minutes play to the end: 27 in all, not 57.
            advance(17 * minute - 1_000)
            assertTrue(state.behind != null)
            assertTrue(watched.isEmpty())
        }
    }

    @Test
    fun `skipping ahead to the credits adds nothing`() = runTest {
        // 9:15, Ridge Line 45 minutes in: started over, then ⏩ to 44 minutes, then three minutes played.
        rig(now = SampleGuide.WEEKNIGHT + 34 * minute) {
            startOver(this)
            repeat(88) { tuner.skip(forward = true) }
            runCurrent()
            assertEquals(44 * minute, state.behind?.positionAt(SampleGuide.WEEKNIGHT + 34 * minute + testScheduler.currentTime))
            advance(3 * minute)
            assertTrue(watched.isEmpty())
        }
    }

    @Test
    fun `filler never counts`() = runTest {
        // Sci-Fi at 8:41: a trailer, then Orbit of Glass at 9:00.
        rig(channel = "scifi") {
            advance(18 * minute)
            assertTrue(watched.isEmpty())
        }
    }

    @Test
    fun `stopping at the watched point between checks still counts`() = runTest {
        rig {
            startOver(this)
            // The last check was at 46:45; 90% is 46:48.
            advance(46 * minute + 49_000)
            assertTrue(watched.isEmpty())
            tuner.stop()
            assertEquals(listOf(ridgeLine), watched)
        }
    }

    @Test
    fun `a branch past the credits isn't counted again`() = runTest {
        rig {
            startOver(this)
            // Past 90% (46:48), with no check since 46:45.
            advance(46 * minute + 49_000)
            val point = tuner.branchPoint(state.slot!!)!!
            assertEquals(ResumeWrite.Watched, ResumeWrite.of(point))
            tuner.markCounted(state.slot!!)
            advance(minute)
            assertTrue(watched.isEmpty())
        }
    }

    @Test
    fun `a branch reads the picture's place in the program on screen, and nothing for another`() = runTest {
        rig {
            val slot = state.slot!!
            assertEquals(BranchPoint(11 * minute, (52 * minute * 0.9).toLong()), tuner.branchPoint(slot))
            video.position = 12 * minute
            assertEquals(12 * minute, tuner.branchPoint(slot)?.positionMs)
            assertNull(tuner.branchPoint(state.next!!))
        }
    }

    private val thaw = NextEpisode("sample-next", "S3 E2", "Snowmelt", art = null)

    @Test
    fun `at an episode's credits the next one shows for 15 seconds, once`() = runTest {
        rig(credits = mapOf(ridgeLine to 50 * minute), next = mapOf(ridgeLine to thaw)) {
            // Live at 8:41 is 11 minutes in: the credits are 39 minutes away.
            advance(39 * minute - 5_000)
            assertNull(state.creditsCard)
            advance(5_000)
            assertEquals(thaw, state.creditsCard?.next)
            advance(GuideViewing.CARD_MILLIS)
            assertNull(state.creditsCard)
            advance(minute)
            assertNull("once", state.creditsCard)
        }
    }

    @Test
    fun `without a marker, the card shows in the last minute, and Back takes it away`() = runTest {
        rig(next = mapOf(ridgeLine to thaw)) {
            advance(40 * minute - 5_000)
            assertNull(state.creditsCard)
            advance(5_000)
            assertEquals(thaw, state.creditsCard?.next)
            tuner.dismissCreditsCard()
            assertNull(state.creditsCard)
        }
    }

    @Test
    fun `OK takes the card, once`() = runTest {
        rig(next = mapOf(ridgeLine to thaw)) {
            advance(40 * minute)
            assertEquals(thaw, tuner.takeCreditsCard()?.next)
            assertNull(state.creditsCard)
            assertNull(tuner.takeCreditsCard())
        }
    }

    @Test
    fun `after the last episode there's no card`() = runTest {
        rig(credits = mapOf(ridgeLine to 50 * minute)) {
            advance(40 * minute)
            assertNull(state.creditsCard)
        }
    }

    @Test
    fun `the options take the card away`() = runTest {
        rig(credits = mapOf(ridgeLine to 50 * minute), next = mapOf(ridgeLine to thaw)) {
            advance(39 * minute)
            assertEquals(thaw, state.creditsCard?.next)
            tuner.openOptions()
            assertNull(state.creditsCard)
        }
    }

    @Test
    fun `surfing takes the card away`() = runTest {
        rig(credits = mapOf(ridgeLine to 50 * minute), next = mapOf(ridgeLine to thaw)) {
            advance(39 * minute)
            assertEquals(thaw, state.creditsCard?.next)
            tuner.surf(+1)
            assertNull(state.creditsCard)
        }
    }
}
