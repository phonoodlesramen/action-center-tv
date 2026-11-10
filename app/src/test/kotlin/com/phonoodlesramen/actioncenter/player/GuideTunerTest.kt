package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.player.FakeGuideVideo.Companion.play
import com.phonoodlesramen.actioncenter.player.FakeGuideVideo.Companion.prepare
import com.phonoodlesramen.actioncenter.plex.PlexStream
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's behaviour against [SampleGuide]'s weeknight lineup (8:41 PM UTC), with a
 * fake player and virtual time, so slot boundaries and the surf debounce are exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideTunerTest {
    private val minute = 60_000L
    private val weeknight = SampleGuide.WEEKNIGHT

    private class Rig(
        scope: TestScope,
        val lineup: MutableStateFlow<GuideCardState>,
        startMillis: Long,
        val streams: SlotStreams = FakeSlotStreams(),
    ) {
        val sources = FakeSlotSources()
        val videos = mutableListOf<FakeGuideVideo>()
        val logs = mutableListOf<String>()
        val clock = { startMillis + scope.testScheduler.currentTime }
        val favorites = MutableStateFlow<List<String>>(emptyList())
        val tuner = GuideTuner(
            lineup = lineup,
            sources = sources,
            openVideo = { events -> FakeGuideVideo(events).also { videos += it } },
            context = StandardTestDispatcher(scope.testScheduler),
            clock = clock,
            log = { logs += it },
            streams = streams,
            favorites = favorites,
        )
        val video get() = videos.last()
        val state get() = tuner.state.value
    }

    /** A started tuner on [channel] at [now], with the sample lineup made at the weeknight. */
    private fun TestScope.rig(
        channel: String? = null,
        now: Long = weeknight,
        lineup: GuideCardState = SampleGuide.ready(weeknight),
        startOver: Boolean = false,
        streams: SlotStreams = FakeSlotStreams(),
        setUp: Rig.() -> Unit = {},
        test: Rig.() -> Unit,
    ) {
        val rig = Rig(this, MutableStateFlow(lineup), now, streams)
        rig.setUp()
        try {
            rig.tuner.select(channel, startOver)
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

    @Test
    fun `it tunes in to the program on now, joining it midway, and shows the banner`() = runTest {
        rig("tv-mix") {
            // Ridge Line, 8:30-9:22, joined at 8:41.
            assertEquals(listOf(play("sample-tv-mix-3", 11 * minute)), video.calls)
            assertEquals("tv-mix", state.channel?.id)
            assertEquals("Ridge Line", state.slot?.title)
            assertEquals("sample-tv-mix-4", state.next?.itemKey)
            assertEquals(Picture.Tuning, state.picture)
            assertTrue(state.banner)
        }
    }

    @Test
    fun `the picture shows, and the screen stays on, only once frames move`() = runTest {
        rig("tv-mix") {
            assertFalse(state.playing)
            video.events.onPlaying(true)
            assertEquals(Picture.Video, state.picture)
            assertTrue(state.playing)
            video.events.onPlaying(false)
            assertEquals(Picture.Video, state.picture)
            assertFalse(state.playing)
        }
    }

    @Test
    fun `the banner hides after five seconds, and OK brings it back`() = runTest {
        rig("tv-mix") {
            advance(4_999)
            assertTrue(state.banner)
            advance(1)
            assertFalse(state.banner)
            tuner.showBanner()
            assertTrue(state.banner)
        }
    }

    @Test
    fun `with no channel chosen, or one that's gone, it plays the first channel`() = runTest {
        rig(channel = "gone") {
            assertEquals("movies", state.channel?.id)
            // Quiet Field, 8:30-10:41.
            assertEquals(listOf(play("sample-movies-1", 11 * minute)), video.calls)
        }
    }

    @Test
    fun `it follows the schedule, opening each file 30 seconds ahead and switching at the boundary`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            // Ridge Line ends at 9:22; the Up next filler after it has no file to open.
            advance(41 * minute - 1)
            assertEquals(1, video.calls.size)
            assertEquals(Picture.Video, state.picture)
            advance(1)
            assertEquals(Picture.UpNext, state.picture)
            assertFalse(state.playing)
            assertEquals("Kitchen Table", state.next?.title)
            // Kitchen Table at 9:30: opened at 9:29:30, played at 9:30 from the top.
            advance(7 * minute + 30_000 - 1)
            assertEquals(1, video.calls.size)
            advance(1)
            assertEquals(prepare("sample-tv-mix-4"), video.calls.last())
            advance(30_000)
            assertEquals(play("sample-tv-mix-4", 0), video.calls.last())
            assertEquals("Kitchen Table", state.slot?.title)
            // The file opened ahead is used: no second lookup.
            assertEquals(1, sources.lookups.count { it == "sample-tv-mix-4" })
        }
    }

    @Test
    fun `a program runs straight into the trailer after it`() = runTest {
        rig("movies") {
            video.events.onPlaying(true)
            // Quiet Field ends at 10:41, then a trailer until 11:00.
            advance(2 * 60 * minute - 30_000)
            assertEquals(prepare("trailer-sample-movies-1"), video.calls.last())
            advance(30_000)
            assertEquals(play("trailer-sample-movies-1", 0), video.calls.last())
            // The picture never went to Tuning: the file was already open.
            assertEquals(Picture.Video, state.picture)
            assertEquals("Trailer: The Long Signal", state.slot?.title)
        }
    }

    @Test
    fun `a trailer slot plays the trailer's own file, then the Up next card once it ends`() = runTest {
        rig("scifi") {
            // The trailer after The Ninth Moon, 8:31-9:00, joined 10 minutes in.
            assertEquals(listOf(play("trailer-sample-scifi-0", 10 * minute)), video.calls)
            video.events.onPlaying(true)
            video.events.onEnded()
            assertEquals(Picture.UpNext, state.picture)
            assertFalse(state.playing)
            assertEquals("Orbit of Glass", state.next?.title)
            // Orbit of Glass at 9:00, opened half a minute ahead.
            advance(19 * minute)
            assertEquals(play("sample-scifi-1", 0), video.calls.last())
        }
    }

    @Test
    fun `a program whose file ends a moment early keeps its last frame until the switch`() = runTest {
        rig("movies") {
            video.events.onPlaying(true)
            // Quiet Field's file ends 2 s before its slot (10:41).
            advance(2 * 60 * minute - 2_000)
            video.events.onPlaying(false)
            video.events.onEnded()
            assertEquals(Picture.Video, state.picture)
            assertFalse(state.playing)
            advance(2_000)
            assertEquals(play("trailer-sample-movies-1", 0), video.calls.last())
        }
    }

    @Test
    fun `a trailer that's already over shows the Up next card without opening it`() = runTest {
        rig("scifi", setUp = { sources.durations["trailer-sample-scifi-0"] = 150_000 }) {
            assertEquals(emptyList<String>(), video.calls)
            assertEquals(Picture.UpNext, state.picture)
        }
    }

    @Test
    fun `an Up next slot shows the card and plays nothing until the next program`() = runTest {
        // Comedy at 8:55: Up next until Open House at 9:00.
        rig("comedy", now = weeknight + 14 * minute) {
            assertEquals(emptyList<String>(), video.calls)
            assertEquals(Picture.UpNext, state.picture)
            assertEquals("S4 E3 · Curb Appeal", state.next?.episode)
            advance(4 * minute + 30_000)
            assertEquals(listOf(prepare("sample-comedy-5")), video.calls)
            advance(30_000)
            assertEquals(play("sample-comedy-5", 0), video.calls.last())
        }
    }

    @Test
    fun `an off-air slot shows why, then the channel moves on at the next slot`() = runTest {
        // Classics at 12:41 AM: Harbor Lights (12:30-2:11) is off air; a trailer follows.
        rig("classics", now = weeknight + 4 * 60 * minute) {
            assertEquals(emptyList<String>(), video.calls)
            assertEquals(Picture.OffAir(OffAirReason.OFF_AIR, "This title is no longer in your library"), state.picture)
            advance(90 * minute)
            assertEquals(play("trailer-sample-classics-3", 0), video.calls.last())
            assertEquals(Picture.Tuning, state.picture)
        }
    }

    @Test
    fun `a missing file, no Plex or an unreachable server each show their off-air card`() = runTest {
        rig("tv-mix", setUp = { sources.missing["sample-tv-mix-3"] = SlotSource.NoFile }) {
            // As the guide says it once the lineup knows: not the Shield's fault.
            assertEquals(Picture.OffAir(OffAirReason.OFF_AIR, "This title is no longer in your library"), state.picture)
            assertTrue(video.calls.isEmpty())
        }
        rig("tv-mix", setUp = { sources.missing["sample-tv-mix-3"] = SlotSource.NotLinked }) {
            assertEquals(Picture.OffAir(OffAirReason.NOT_LINKED), state.picture)
        }
        rig("tv-mix", setUp = { sources.missing["sample-tv-mix-3"] = SlotSource.Offline }) {
            assertEquals(Picture.OffAir(OffAirReason.OFFLINE), state.picture)
            // Still unreachable all through Ridge Line, so the next program tunes in as usual: Kitchen Table at 9:30.
            advance(49 * minute)
            assertEquals(play("sample-tv-mix-4", 0), video.calls.last())
        }
    }

    @Test
    fun `filler that can't play shows the Up next card rather than off air, and isn't tried again`() = runTest {
        rig("scifi", setUp = { sources.missing["trailer-sample-scifi-0"] = SlotSource.Offline }) {
            assertEquals(Picture.UpNext, state.picture)
            advance(GuideTuning.RETRY_MILLIS)
            assertEquals(1, sources.lookups.count { it == "trailer-sample-scifi-0" })
        }
        rig("scifi") {
            video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
            assertEquals(Picture.UpNext, state.picture)
            advance(GuideTuning.RETRY_MILLIS)
            assertEquals(1, video.calls.size)
        }
    }

    @Test
    fun `a player error shows the off-air card until the next slot`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            video.events.onError(VideoError(OffAirReason.CANT_PLAY, "ERROR_CODE_DECODING_FAILED"))
            // The code is for the log, not the card.
            assertEquals(Picture.OffAir(OffAirReason.CANT_PLAY), state.picture)
            assertTrue("player: ERROR_CODE_DECODING_FAILED" in logs)
            assertFalse(state.playing)
            // Late events from the failed file change nothing.
            video.events.onPlaying(true)
            assertEquals(Picture.OffAir(OffAirReason.CANT_PLAY), state.picture)
            advance(49 * minute)
            assertEquals(play("sample-tv-mix-4", 0), video.calls.last())
        }
        rig("tv-mix") {
            video.events.onError(VideoError(OffAirReason.OFFLINE, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
            assertEquals(Picture.OffAir(OffAirReason.OFFLINE), state.picture)
        }
        rig("tv-mix") {
            video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
            assertEquals(Picture.OffAir(OffAirReason.UNAVAILABLE), state.picture)
        }
    }

    // Trying again: offline and unavailable, while the program's on.

    @Test
    fun `an unavailable file is tried again every 30 seconds, and comes back where the program is by then`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            // Ridge Line, joined at 8:41; at 8:46 the NAS drive goes away and Plex answers with an error.
            advance(5 * minute)
            video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
            assertEquals(Picture.OffAir(OffAirReason.UNAVAILABLE), state.picture)
            assertFalse(state.playing)
            advance(GuideTuning.RETRY_MILLIS - 1)
            assertEquals(1, video.calls.size)
            // 8:46:30: looked up and opened again, 16.5 minutes in. The card stays while it opens.
            advance(1)
            assertEquals(play("sample-tv-mix-3", 16 * minute + 30_000), video.calls.last())
            assertEquals(Picture.OffAir(OffAirReason.UNAVAILABLE), state.picture)
            assertFalse(state.banner)
            // Still not back.
            video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
            assertEquals(Picture.OffAir(OffAirReason.UNAVAILABLE), state.picture)
            // 8:47: the drive's back, and the program plays on from 17 minutes in.
            advance(GuideTuning.RETRY_MILLIS)
            assertEquals(play("sample-tv-mix-3", 17 * minute), video.calls.last())
            video.events.onPlaying(true)
            assertEquals(Picture.Video, state.picture)
            assertTrue(state.playing)
            assertEquals(3, sources.lookups.count { it == "sample-tv-mix-3" })
            assertEquals(listOf("player: channel tv-mix, retry 1", "player: channel tv-mix, retry 2"), logs.filter { "retry " in it })
            assertTrue("player: channel tv-mix playing again after 2 retries" in logs)
            // Back on schedule: Kitchen Table at 9:30, opened ahead.
            advance(43 * minute)
            assertEquals(play("sample-tv-mix-4", 0), video.calls.last())
        }
    }

    @Test
    fun `when Plex can't be reached, the program is tried again until it answers`() = runTest {
        rig("tv-mix", setUp = { sources.missing["sample-tv-mix-3"] = SlotSource.Offline }) {
            assertEquals(Picture.OffAir(OffAirReason.OFFLINE), state.picture)
            advance(GuideTuning.RETRY_MILLIS)
            assertEquals(2, sources.lookups.count { it == "sample-tv-mix-3" })
            assertEquals(Picture.OffAir(OffAirReason.OFFLINE), state.picture)
            assertTrue(video.calls.isEmpty())
            sources.missing.clear()
            advance(GuideTuning.RETRY_MILLIS)
            assertEquals(listOf(play("sample-tv-mix-3", 12 * minute)), video.calls)
            video.events.onPlaying(true)
            assertEquals(Picture.Video, state.picture)
        }
    }

    @Test
    fun `when the player loses Plex, the program is tried again too`() = runTest {
        rig("tv-mix") {
            video.events.onError(VideoError(OffAirReason.OFFLINE, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))
            assertEquals(Picture.OffAir(OffAirReason.OFFLINE), state.picture)
            advance(GuideTuning.RETRY_MILLIS)
            assertEquals(play("sample-tv-mix-3", 11 * minute + 30_000), video.calls.last())
        }
    }

    @Test
    fun `a file the Shield can't play isn't tried again`() = runTest {
        rig("tv-mix") {
            video.events.onError(VideoError(OffAirReason.CANT_PLAY, "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"))
            advance(10 * minute)
            assertEquals(1, video.calls.size)
            assertEquals(1, sources.lookups.size)
            assertEquals(Picture.OffAir(OffAirReason.CANT_PLAY), state.picture)
            assertTrue(logs.none { "retry" in it })
        }
    }

    @Test
    fun `retrying stops at the slot's end, and the next program tunes in as usual`() = runTest {
        rig("tv-mix", setUp = { sources.missing["sample-tv-mix-3"] = SlotSource.Offline }) {
            // Ridge Line ends at 9:22: tried at 8:41 and every 30 s to 9:21, the last whose
            // try comes before the next slot would open ahead (9:21:30).
            advance(41 * minute - 1)
            assertEquals(1 + 80, sources.lookups.count { it == "sample-tv-mix-3" })
            assertEquals(Picture.OffAir(OffAirReason.OFFLINE), state.picture)
            advance(1)
            assertEquals(Picture.UpNext, state.picture)
            advance(8 * minute)
            assertEquals(listOf(prepare("sample-tv-mix-4"), play("sample-tv-mix-4", 0)), video.calls)
            assertEquals(81, sources.lookups.count { it == "sample-tv-mix-3" })
        }
    }

    @Test
    fun `a failure in a program's last minute isn't tried again, and the next file opens ahead as usual`() = runTest {
        rig("movies") {
            video.events.onPlaying(true)
            // Quiet Field ends at 10:41; it fails at 10:40:10.
            advance(2 * 60 * minute - 50_000)
            video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
            advance(50_000)
            assertEquals(
                listOf(play("sample-movies-1", 11 * minute), prepare("trailer-sample-movies-1"), play("trailer-sample-movies-1", 0)),
                video.calls,
            )
        }
    }

    @Test
    fun `changing channel ends the retries`() = runTest {
        rig("tv-mix") {
            video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
            tuner.surf(+1)
            advance(GuideTuning.SURF_DEBOUNCE_MILLIS)
            assertEquals(play("sample-classics-1", 11 * minute + 400), video.calls.last())
            advance(5 * minute)
            assertEquals(2, video.calls.size)
            assertEquals(1, sources.lookups.count { it == "sample-tv-mix-3" })
        }
    }

    @Test
    fun `stopping ends the retries, and coming back tunes in afresh`() = runTest {
        rig("tv-mix", setUp = { sources.missing["sample-tv-mix-3"] = SlotSource.Offline }) {
            tuner.stop()
            advance(5 * minute)
            assertEquals(1, sources.lookups.size)
            // Coming back tunes in afresh, and tries again from there.
            tuner.start()
            runCurrent()
            assertEquals(2, sources.lookups.size)
            advance(GuideTuning.RETRY_MILLIS)
            assertEquals(3, sources.lookups.size)
        }
    }

    @Test
    fun `behind live, a failure still rejoins live rather than trying again`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.startOver()
            runCurrent()
            advance(minute)
            video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
            runCurrent()
            assertNull(state.behind)
            assertEquals(play("sample-tv-mix-3", 12 * minute), video.calls.last())
        }
    }

    @Test
    fun `a file opened ahead that fails is looked up again at its own slot`() = runTest {
        rig("comedy", now = weeknight + 14 * minute) {
            advance(4 * minute + 30_000)
            assertEquals(listOf(prepare("sample-comedy-5")), video.calls)
            // The Up next card is still up: the error isn't the card's.
            video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
            assertEquals(Picture.UpNext, state.picture)
            advance(30_000)
            assertEquals(play("sample-comedy-5", 0), video.calls.last())
            assertEquals(2, sources.lookups.count { it == "sample-comedy-5" })
        }
    }

    @Test
    fun `a player that throws shows the off-air card, never a crash`() = runTest {
        val rig = Rig(this, MutableStateFlow<GuideCardState>(SampleGuide.ready(weeknight)), weeknight)
        try {
            rig.tuner.select("tv-mix")
            rig.tuner.start()
            // The player exists from start; the lineup is read once the dispatcher runs.
            rig.video.failPlay = IllegalStateException("boom")
            runCurrent()
            assertEquals(Picture.OffAir(OffAirReason.CANT_PLAY), rig.state.picture)
            assertTrue(rig.logs.any { "boom" in it })
        } finally {
            rig.tuner.close()
        }
    }

    @Test
    fun `a player that can't be made shows the off-air card`() = runTest {
        val tuner = GuideTuner(
            lineup = MutableStateFlow(SampleGuide.ready(weeknight)),
            sources = FakeSlotSources(),
            openVideo = { throw IllegalStateException("no decoder") },
            context = StandardTestDispatcher(testScheduler),
            clock = { weeknight },
            log = {},
        )
        try {
            tuner.start()
            runCurrent()
            assertEquals(Picture.OffAir(OffAirReason.CANT_PLAY), tuner.state.value.picture)
        } finally {
            tuner.close()
        }
    }

    @Test
    fun `Up moves the channel at once, but opens its file only once the presses stop`() = runTest {
        rig("movies") {
            tuner.surf(+1)
            advance(200)
            tuner.surf(+1)
            advance(150)
            tuner.surf(+1)
            // Classics shows straight away, with the banner, but nothing opens on the way.
            assertEquals("classics", state.channel?.id)
            assertEquals("The Silver Coast", state.slot?.title)
            assertEquals(Picture.Tuning, state.picture)
            assertTrue(state.banner)
            advance(399)
            assertEquals(1, video.calls.size)
            advance(1)
            assertEquals(listOf(play("sample-movies-1", 11 * minute), play("sample-classics-1", 11 * minute + 750)), video.calls)
            assertEquals(listOf("sample-movies-1", "sample-classics-1"), sources.lookups)
        }
    }

    @Test
    fun `Down from the first channel wraps to the last`() = runTest {
        rig("movies") {
            tuner.surf(-1)
            assertEquals("marathon-ridge-line", state.channel?.id)
            advance(400)
            assertEquals(play("sample-marathon-ridge-line-2", 11 * minute + 400), video.calls.last())
        }
    }

    @Test
    fun `surfing stops the channel being left at once`() = runTest {
        rig("movies") {
            video.events.onPlaying(true)
            val stops = video.stops
            tuner.surf(+1)
            assertTrue(video.stops > stops)
            assertFalse(state.playing)
        }
    }

    @Test
    fun `choosing a channel again tunes in at once`() = runTest {
        rig("movies") {
            tuner.select("comedy")
            runCurrent()
            assertEquals(play("sample-comedy-4", 11 * minute), video.calls.last())
            assertTrue(state.banner)
        }
    }

    @Test
    fun `with no lineup it says there are no channels yet, and tunes in once one arrives`() = runTest {
        rig("tv-mix", lineup = GuideCardState.NotSetUp) {
            assertEquals(Picture.OffAir(OffAirReason.NO_CHANNELS), state.picture)
            assertEquals(null, state.channel)
            tuner.surf(+1)
            tuner.showBanner()
            assertFalse(state.banner)
            lineup.value = SampleGuide.ready(weeknight)
            runCurrent()
            assertEquals(listOf(play("sample-tv-mix-3", 11 * minute)), video.calls)
        }
    }

    @Test
    fun `a lineup update retunes only when what's on now changed`() = runTest {
        rig("tv-mix") {
            val ready = SampleGuide.ready(weeknight)
            lineup.value = ready.copy(updatedAtMillis = weeknight)
            runCurrent()
            assertEquals(1, video.calls.size)
            // Ridge Line's file is reported gone.
            lineup.value = ready.copy(
                channels = ready.channels.map { channel ->
                    channel.copy(slots = channel.slots.map { if (it.itemKey == "sample-tv-mix-3") it.copy(offAir = "Gone") else it })
                },
            )
            runCurrent()
            assertEquals(Picture.OffAir(OffAirReason.OFF_AIR, "Gone"), state.picture)
        }
    }

    @Test
    fun `stopping releases the player, and starting again tunes in afresh`() = runTest {
        rig("tv-mix") {
            val first = video
            tuner.stop()
            assertTrue(first.released)
            assertFalse(state.banner)
            advance(minute)
            tuner.start()
            runCurrent()
            assertEquals(2, videos.size)
            assertEquals(listOf(play("sample-tv-mix-3", 12 * minute)), video.calls)
            assertTrue(state.banner)
        }
    }

    @Test
    fun `nothing it logs carries the token or the address`() = runTest {
        rig("tv-mix") {
            video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS"))
            advance(GuideTuning.RETRY_MILLIS)
            video.events.onPlaying(true)
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.none { "secret" in it || "plex.direct" in it })
        }
    }

    // Start over and pause (G11).

    @Test
    fun `Start over plays the program on now from its start, behind live, with the banner saying so`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            assertTrue(state.canStartOver)
            tuner.startOver()
            runCurrent()
            assertEquals(play("sample-tv-mix-3", 0), video.calls.last())
            // The file already open is played again, not looked up.
            assertEquals(1, sources.lookups.size)
            val behind = state.behind!!
            assertTrue(behind.startedOver)
            assertEquals(11 * minute, behind.behindAt(clock()))
            assertEquals("Ridge Line", state.slot?.title)
            assertTrue(state.banner)
        }
    }

    @Test
    fun `behind live nothing opens ahead or switches at the boundary, and the program's end rejoins live`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.startOver()
            runCurrent()
            video.events.onPlaying(true)
            // Ridge Line's slot ends at 9:22 and Kitchen Table's file would open at 9:29:30: neither happens.
            advance(49 * minute)
            assertEquals(listOf(play("sample-tv-mix-3", 11 * minute), play("sample-tv-mix-3", 0)), video.calls)
            assertEquals("Ridge Line", state.slot?.title)
            assertEquals(Picture.Video, state.picture)
            // Started over at 8:41, it ends at 9:33: Kitchen Table is on, 3 minutes in.
            advance(3 * minute)
            assertEquals(play("sample-tv-mix-4", 3 * minute), video.calls.last())
            assertNull(state.behind)
            assertEquals("Kitchen Table", state.slot?.title)
        }
    }

    @Test
    fun `when the file ends first, it rejoins live at once`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.startOver()
            runCurrent()
            advance(20 * minute)
            video.events.onEnded()
            runCurrent()
            assertNull(state.behind)
            assertEquals(play("sample-tv-mix-3", 31 * minute), video.calls.last())
        }
    }

    @Test
    fun `Start over from the intent's extra, before the lineup is known, starts the program over once it is`() = runTest {
        rig("tv-mix", startOver = true) {
            assertEquals(listOf(play("sample-tv-mix-3", 0)), video.calls)
            assertTrue(state.behind!!.startedOver)
        }
    }

    @Test
    fun `filler and off-air programs have nothing to start over, so they play live`() = runTest {
        // Sci-Fi's trailer at 8:41.
        rig("scifi", startOver = true) {
            assertEquals(listOf(play("trailer-sample-scifi-0", 10 * minute)), video.calls)
            assertNull(state.behind)
            assertFalse(state.canStartOver)
        }
        // Classics at 12:41 AM: Harbor Lights is off air.
        rig("classics", now = weeknight + 4 * 60 * minute) {
            tuner.startOver()
            runCurrent()
            assertNull(state.behind)
            assertFalse(state.canStartOver)
        }
    }

    @Test
    fun `pause holds the picture, keeps the banner, lets the screen sleep, and holds the schedule`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            advance(6_000)
            assertFalse(state.banner)
            tuner.pause()
            assertEquals("pause", video.calls.last())
            assertFalse(state.playing)
            assertTrue(state.behind!!.paused)
            assertTrue(state.banner)
            // A late report from before the pause doesn't hold the screen on.
            video.events.onPlaying(true)
            assertFalse(state.playing)
            // Twelve minutes later: still paused, the banner still up, nothing switched at 9:22.
            advance(42 * minute)
            assertTrue(state.banner)
            assertEquals(42 * minute, state.behind!!.behindAt(clock()))
            assertEquals(2, video.calls.size)
        }
    }

    @Test
    fun `after a long pause, Next is what's on when the program ends and the channel rejoins live`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            assertEquals("Kitchen Table", state.next?.title)
            tuner.pause()
            // Two hours later, Kitchen Table (from 9:22) is long over. Ridge Line has 41 minutes
            // left, so playing on now rejoins live at 11:22.
            advance(120 * minute)
            tuner.showBanner()
            val rejoin = clock() + 41 * minute
            val onThen = state.channel!!.slots.first { !it.filler && it.endMillis > rejoin }
            assertEquals(onThen, state.next)
            assertNotEquals("Kitchen Table", state.next?.title)
        }
    }

    @Test
    fun `resuming carries on behind live until the program ends, then rejoins`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.playPause()
            advance(12 * minute)
            tuner.playPause()
            assertEquals("resume", video.calls.last())
            video.events.onPlaying(true)
            assertTrue(state.playing)
            assertFalse(state.behind!!.paused)
            assertEquals(12 * minute, state.behind!!.behindAt(clock()))
            advance(5_000)
            assertFalse(state.banner)
            // Paused at 8:41 for 12 minutes: Ridge Line ends at 9:34, 4 minutes into Kitchen Table.
            advance(41 * minute - 5_000 - 1)
            assertTrue(state.behind != null)
            advance(1)
            assertNull(state.behind)
            assertEquals(play("sample-tv-mix-4", 4 * minute), video.calls.last())
        }
    }

    @Test
    fun `nothing pauses until frames move`() = runTest {
        rig("tv-mix") {
            tuner.pause()
            assertNull(state.behind)
            assertEquals(1, video.calls.size)
        }
    }

    @Test
    fun `Back to live, or surfing, rejoins at once`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.pause()
            advance(5 * minute)
            tuner.backToLive()
            runCurrent()
            assertNull(state.behind)
            assertEquals(play("sample-tv-mix-3", 16 * minute), video.calls.last())
            video.events.onPlaying(true)
            tuner.startOver()
            runCurrent()
            tuner.surf(+1)
            assertNull(state.behind)
            assertEquals("classics", state.channel?.id)
            advance(GuideTuning.SURF_DEBOUNCE_MILLIS)
            assertEquals(play("sample-classics-1", 16 * minute + 400), video.calls.last())
        }
    }

    @Test
    fun `stopping behind live keeps the place, paused, and coming back opens the file held there`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.startOver()
            runCurrent()
            video.events.onPlaying(true)
            advance(5 * minute)
            tuner.stop()
            advance(10 * minute)
            tuner.start()
            runCurrent()
            assertEquals(2, videos.size)
            assertEquals(listOf(play("sample-tv-mix-3", 5 * minute), "pause"), video.calls)
            val behind = state.behind!!
            assertTrue(behind.paused)
            assertEquals(Picture.Video, state.picture)
            assertTrue(state.banner)
            // Play carries on from there.
            tuner.playPause()
            assertEquals("resume", video.calls.last())
        }
    }

    // Tracks (G13, G14).

    private val ridgeLineStreams = listOf(
        stream("10", PlexStream.AUDIO, 1, title = "English (EAC3 5.1)", selected = true),
        stream("20", PlexStream.SUBTITLES, 2, title = "English Forced (SRT)", forced = true),
    )

    @Test
    fun `each program's streams are read once and go to the video with its file`() = runTest {
        val streams = FakeSlotStreams(mutableMapOf("sample-tv-mix-3" to ridgeLineStreams))
        rig("tv-mix", streams = streams) {
            assertEquals(listOf(ridgeLineStreams), video.streams)
            video.events.onPlaying(true)
            tuner.startOver()
            runCurrent()
            assertEquals(listOf(ridgeLineStreams, ridgeLineStreams), video.streams)
            assertEquals(listOf("sample-tv-mix-3"), streams.lookups)
        }
    }

    @Test
    fun `the next program's streams are read when its file opens ahead`() = runTest {
        val streams = FakeSlotStreams()
        rig("tv-mix", streams = streams) {
            // Kitchen Table's file opens at 9:29:30.
            advance(48 * minute + 30_000)
            assertEquals(prepare("sample-tv-mix-4"), video.calls.last())
            assertEquals(listOf("sample-tv-mix-3", "sample-tv-mix-4"), streams.lookups)
        }
    }

    @Test
    fun `filler's streams are never asked for`() = runTest {
        val streams = FakeSlotStreams()
        rig("movies", streams = streams) {
            video.events.onPlaying(true)
            // Quiet Field, then its trailer at 10:41.
            advance(2 * 60 * minute)
            assertEquals(play("trailer-sample-movies-1", 0), video.calls.last())
            assertEquals(listOf("sample-movies-1"), streams.lookups)
        }
    }

    @Test
    fun `a slow answer from Plex doesn't hold the file up`() = runTest {
        val slow = SlotStreams {
            delay(10_000)
            ridgeLineStreams
        }
        rig("tv-mix", streams = slow) {
            assertTrue(video.calls.isEmpty())
            advance(GuideTuning.STREAMS_TIMEOUT_MILLIS)
            assertEquals(listOf(play("sample-tv-mix-3", 11 * minute + 1_500)), video.calls)
            assertEquals(listOf(null), video.streams)
        }
    }

    @Test
    fun `the options list the video's tracks, and a choice is for this program only`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            assertEquals(TrackMenu(), state.tracks)
            tuner.openTracks(TrackKind.AUDIO)
            assertEquals(Overlay.None, state.overlay)
            val menu = TrackMenu(audio = listOf(TrackOption("10", "English", true), TrackOption("11", "Deutsch", false)))
            video.events.onTracks(menu)
            assertEquals(menu, state.tracks)
            tuner.openTracks(TrackKind.AUDIO)
            assertEquals(Overlay.Tracks(TrackKind.AUDIO), state.overlay)
            tuner.chooseTrack(TrackKind.AUDIO, "11")
            assertEquals("track AUDIO 11", video.calls.last())
            assertEquals(Overlay.None, state.overlay)
            // Kitchen Table at 9:30 starts on its own defaults: the list waits for its tracks.
            advance(49 * minute)
            assertEquals(play("sample-tv-mix-4", 0), video.calls.last())
            assertEquals(TrackMenu(), state.tracks)
        }
    }

    // The mini guide (G12) and the overlays.

    @Test
    fun `the mini guide opens on the channel playing, Up and Down move it without tuning, and OK tunes in`() = runTest {
        rig("tv-mix") {
            tuner.openMiniGuide()
            assertEquals(Overlay.MiniGuide("tv-mix"), state.overlay)
            assertFalse(state.banner)
            assertEquals(6, state.lineup.size)
            tuner.moveMiniGuide(+1)
            assertEquals(Overlay.MiniGuide("classics"), state.overlay)
            tuner.moveMiniGuide(-1)
            tuner.moveMiniGuide(-1)
            tuner.moveMiniGuide(-1)
            tuner.moveMiniGuide(-1)
            // Up from the first wraps to the last.
            assertEquals(Overlay.MiniGuide("marathon-ridge-line"), state.overlay)
            assertEquals(1, video.calls.size)
            tuner.chooseMiniGuide()
            runCurrent()
            assertEquals(Overlay.None, state.overlay)
            assertEquals("marathon-ridge-line", state.channel?.id)
            assertEquals(play("sample-marathon-ridge-line-2", 11 * minute), video.calls.last())
            assertTrue(state.banner)
        }
    }

    @Test
    fun `the mini guide lists favorites first, while Up and Down over the picture still go by number`() = runTest {
        rig("tv-mix", setUp = { favorites.value = listOf("comedy", "tv-mix") }) {
            assertEquals(listOf("comedy", "tv-mix", "movies", "scifi", "classics", "marathon-ridge-line"), state.miniGuide.map { it.id })
            assertTrue(state.favorite)
            tuner.openMiniGuide()
            tuner.moveMiniGuide(-1)
            assertEquals(Overlay.MiniGuide("comedy"), state.overlay)
            tuner.moveMiniGuide(-1)
            // Up from the first wraps to the last, Ridge Line Marathon.
            assertEquals(Overlay.MiniGuide("marathon-ridge-line"), state.overlay)
            tuner.moveMiniGuide(+1)
            tuner.moveMiniGuide(+1)
            tuner.moveMiniGuide(+1)
            assertEquals(Overlay.MiniGuide("movies"), state.overlay)
            tuner.closeOverlay()

            // Channel up from TV Mix (3) is Classics (4), by number.
            tuner.surf(+1)
            assertEquals("classics", state.channel?.id)
            assertFalse(state.favorite)
            favorites.value = listOf("classics")
            runCurrent()
            assertTrue(state.favorite)
        }
    }

    @Test
    fun `OK on the channel playing just closes the mini guide`() = runTest {
        rig("tv-mix") {
            tuner.openMiniGuide()
            tuner.chooseMiniGuide()
            runCurrent()
            assertEquals(Overlay.None, state.overlay)
            assertEquals(1, video.calls.size)
        }
    }

    @Test
    fun `the mini guide closes after six seconds without a key`() = runTest {
        rig("tv-mix") {
            tuner.openMiniGuide()
            advance(GuideTuning.MINI_GUIDE_MILLIS - 1)
            tuner.moveMiniGuide(+1)
            advance(GuideTuning.MINI_GUIDE_MILLIS - 1)
            tuner.keepOverlay()
            advance(GuideTuning.MINI_GUIDE_MILLIS - 1)
            assertTrue(state.overlay is Overlay.MiniGuide)
            advance(1)
            assertEquals(Overlay.None, state.overlay)
        }
    }

    @Test
    fun `the options, the details and their closing`() = runTest {
        rig("tv-mix") {
            video.events.onPlaying(true)
            tuner.pause()
            tuner.openOptions()
            assertEquals(Overlay.Options, state.overlay)
            assertFalse(state.banner)
            tuner.openDetails()
            assertEquals(Overlay.Details, state.overlay)
            assertEquals("Ridge Line", state.detailsSlot?.title)
            // Closing over a paused picture brings its banner back.
            tuner.closeOverlay()
            assertEquals(Overlay.None, state.overlay)
            assertTrue(state.banner)
        }
        // While filler is on, Details is about the next program.
        rig("comedy", now = weeknight + 14 * minute) {
            assertEquals("Open House", state.detailsSlot?.title)
            assertEquals("S4 E3 · Curb Appeal", state.detailsSlot?.episode)
        }
    }

    @Test
    fun `left alone over the picture, a sheet or the details close after two minutes, and a key keeps them`() = runTest {
        rig("tv-mix") {
            tuner.openOptions()
            advance(GuideTuning.OVERLAY_IDLE_MILLIS - 1)
            tuner.keepOverlay()
            advance(GuideTuning.OVERLAY_IDLE_MILLIS - 1)
            assertEquals(Overlay.Options, state.overlay)
            advance(1)
            assertEquals(Overlay.None, state.overlay)
        }
    }
}
