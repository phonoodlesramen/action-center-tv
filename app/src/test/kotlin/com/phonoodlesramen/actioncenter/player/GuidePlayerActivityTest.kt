package com.phonoodlesramen.actioncenter.player

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.handoff.Handoffs
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.player.FakeGuideVideo.Companion.play
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.player.KeepWatching
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The player's activity with a fake ExoPlayer ([PlayerTestApp]): the sample lineup at the
 * weeknight's 8:41 PM, a clock that stands still, and keys sent as the remote sends them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P, application = PlayerTestApp::class)
class GuidePlayerActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<PlayerTestApp>()
    private val video get() = app.videos.last()
    private var scenario: ActivityScenario<GuidePlayerActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch(channel: String? = null): ActivityScenario<GuidePlayerActivity> {
        val intent = if (channel == null) Intent(app, GuidePlayerActivity::class.java) else GuidePlayerActivity.intent(app, channel)
        return ActivityScenario.launch<GuidePlayerActivity>(intent).also {
            scenario = it
            compose.waitForIdle()
        }
    }

    private fun ActivityScenario<GuidePlayerActivity>.press(keyCode: Int) {
        onActivity {
            it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        compose.waitForIdle()
    }

    private fun waitFor(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        compose.waitForIdle()
    }

    private fun text(text: String) = compose.onNodeWithText(text)

    @Test
    fun `it tunes in to the program on now, joining it midway, with the banner`() {
        launch("tv-mix")

        assertEquals(listOf(play("sample-tv-mix-3", 11 * 60_000L)), video.calls)
        text("TV Mix").assertExists()
        text("Ridge Line").assertExists()
        text("41m left").assertExists()
        text("Tuning in…").assertExists()
    }

    @Test
    fun `the banner hides after a few seconds, and OK shows it again`() {
        launch("tv-mix").run {
            waitFor(5_000)
            text("Ridge Line").assertDoesNotExist()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            text("Ridge Line").assertExists()
        }
    }

    @Test
    fun `Up shows the next channel at once and opens its file after the debounce`() {
        launch("tv-mix").run {
            press(KeyEvent.KEYCODE_DPAD_UP)
            text("Classics").assertExists()
            text("The Silver Coast").assertExists()
            assertEquals(1, video.calls.size)

            waitFor(GuideTuning.SURF_DEBOUNCE_MILLIS)
            assertEquals(play("sample-classics-1", 11 * 60_000L), video.calls.last())
        }
    }

    @Test
    fun `Down from the first channel wraps to the last`() {
        launch().run {
            assertEquals(listOf(play("sample-movies-1", 11 * 60_000L)), video.calls)
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            text("Ridge Line Marathon").assertExists()
            waitFor(GuideTuning.SURF_DEBOUNCE_MILLIS)
            assertEquals(play("sample-marathon-ridge-line-2", 11 * 60_000L), video.calls.last())
        }
    }

    @Test
    fun `Channel up goes the same way as Up`() {
        launch("tv-mix").run {
            press(KeyEvent.KEYCODE_CHANNEL_UP)
            text("Classics").assertExists()
        }
    }

    @Test
    fun `Channel down goes the same way as Down`() {
        launch().run {
            press(KeyEvent.KEYCODE_CHANNEL_DOWN)
            text("Ridge Line Marathon").assertExists()
        }
    }

    @Test
    fun `a trailer slot plays the trailer's own file, then the Up next card`() {
        launch("scifi").run {
            assertEquals(listOf(play("trailer-sample-scifi-0", 10 * 60_000L)), video.calls)
            onActivity { video.events.onEnded() }
            compose.waitForIdle()
            // Orbit of Glass at 9:00, in 19 minutes.
            text("in 19:00").assertExists()
        }
    }

    @Test
    fun `an Up next slot shows the card and plays nothing`() {
        app.nowMillis = SampleGuide.WEEKNIGHT + 14 * 60_000L
        launch("comedy")

        assertTrue(video.calls.isEmpty())
        text("in 5:00").assertExists()
    }

    @Test
    fun `an off-air slot says why and plays nothing`() {
        app.nowMillis = SampleGuide.WEEKNIGHT + 4 * 60 * 60_000L
        launch("classics")

        assertTrue(video.calls.isEmpty())
        text("This title is no longer in your library").assertExists()
    }

    @Test
    fun `a missing file shows the off-air card`() {
        app.sources.missing["sample-tv-mix-3"] = SlotSource.NoFile
        launch("tv-mix")

        assertTrue(video.calls.isEmpty())
        text("This title is no longer in your library").assertExists()
    }

    @Test
    fun `a player error shows the off-air card in plain words, without its code`() {
        launch("tv-mix").run {
            onActivity { video.events.onError(VideoError(OffAirReason.OFFLINE, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")) }
            compose.waitForIdle()
            text("Can't reach Plex").assertExists()
            text("Check that your Plex server is on. Trying again…").assertExists()
            compose.onNodeWithText("IO_NETWORK_CONNECTION_FAILED", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `a file Plex can't give right now says so, and the program comes back on its own`() {
        launch("tv-mix").run {
            onActivity { video.events.onError(VideoError(OffAirReason.UNAVAILABLE, "ERROR_CODE_IO_BAD_HTTP_STATUS")) }
            compose.waitForIdle()
            // Ridge Line is an episode.
            text("This episode isn't available right now").assertExists()
            text("It may be on a drive that's off or restarting. Trying again…").assertExists()
            compose.onNodeWithText("Error", substring = true).assertDoesNotExist()
            waitFor(GuideTuning.RETRY_MILLIS)
            assertEquals(2, video.calls.size)
            playing()
            text("This episode isn't available right now").assertDoesNotExist()
        }
    }

    @Test
    fun `with no lineup yet it says there are no channels`() {
        app.lineup.value = GuideCardState.NotSetUp
        launch("tv-mix")

        assertTrue(video.calls.isEmpty())
        text("No channels yet").assertExists()
    }

    @Test
    fun `the screen is kept on only while video plays`() {
        launch("tv-mix").run {
            fun keptOn(): Boolean {
                var on = false
                onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
                return on
            }
            assertFalse(keptOn())
            onActivity { video.events.onPlaying(true) }
            compose.waitForIdle()
            assertTrue(keptOn())
            onActivity { video.events.onEnded() }
            compose.waitForIdle()
            assertFalse(keptOn())
        }
    }

    @Test
    fun `stopping releases the player, and coming back tunes in again`() {
        launch("tv-mix").run {
            val first = video
            moveToState(Lifecycle.State.CREATED)
            assertTrue(first.released)
            moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(2, app.videos.size)
            assertEquals(listOf(play("sample-tv-mix-3", 11 * 60_000L)), video.calls)
        }
    }

    @Test
    fun `Back finishes it`() {
        var finishing = false
        launch("tv-mix").onActivity {
            it.onBackPressedDispatcher.onBackPressed()
            finishing = it.isFinishing
        }
        assertTrue(finishing)
    }

    // 2.7: the options, start over and pause, the mini guide.

    /** Holds [keyCode] down long enough to repeat (a long press), then lets go. */
    private fun ActivityScenario<GuidePlayerActivity>.hold(keyCode: Int) {
        onActivity {
            it.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
            it.dispatchKeyEvent(KeyEvent(0, 500, KeyEvent.ACTION_DOWN, keyCode, 1))
        }
        // The sheet opens and takes focus while the key is still down.
        compose.waitForIdle()
        onActivity {
            it.dispatchKeyEvent(KeyEvent(0, 550, KeyEvent.ACTION_DOWN, keyCode, 2))
            it.dispatchKeyEvent(KeyEvent(0, 600, KeyEvent.ACTION_UP, keyCode, 0))
        }
        compose.waitForIdle()
    }

    private fun ActivityScenario<GuidePlayerActivity>.playing() {
        onActivity { video.events.onPlaying(true) }
        compose.waitForIdle()
    }

    private fun ActivityScenario<GuidePlayerActivity>.keptOn(): Boolean {
        var on = false
        onActivity { on = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return on
    }

    private fun ActivityScenario<GuidePlayerActivity>.finishing(): Boolean {
        var finishing = false
        onActivity { finishing = it.isFinishing }
        return finishing
    }

    @Test
    fun `holding OK opens the options on Start over, and letting go doesn't pick it`() {
        launch("tv-mix").run {
            playing()
            hold(KeyEvent.KEYCODE_DPAD_CENTER)
            text("QUICK ACTIONS").assertExists()
            text("Start over").assertIsFocused()
            text("Details").assertExists()
            text("Back to live").assertDoesNotExist()
            // No tracks yet, so no Audio or Subtitles.
            text("Audio").assertDoesNotExist()
            assertEquals(1, video.calls.size)
        }
    }

    @Test
    fun `Menu opens the options, Back closes them and the player stays`() {
        launch("tv-mix").run {
            press(KeyEvent.KEYCODE_MENU)
            text("QUICK ACTIONS").assertExists()
            // Under the sheet, Down moves in it and never surfs.
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            waitFor(GuideTuning.SURF_DEBOUNCE_MILLIS)
            assertEquals(1, video.calls.size)
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            text("QUICK ACTIONS").assertDoesNotExist()
            assertFalse(finishing())
        }
    }

    @Test
    fun `Start over from the options plays the program from its start, and the banner says so`() {
        launch("tv-mix").run {
            playing()
            press(KeyEvent.KEYCODE_MENU)
            text("Start over").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals(play("sample-tv-mix-3", 0), video.calls.last())
            text("STARTED OVER · 11 MIN BEHIND LIVE").assertExists()
            press(KeyEvent.KEYCODE_MENU)
            text("Back to live").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals(play("sample-tv-mix-3", 11 * 60_000L), video.calls.last())
            text("ON NOW").assertExists()
        }
    }

    @Test
    fun `Start over from the intent's extra plays the program from its start`() {
        val intent = GuidePlayerActivity.intent(app, "tv-mix", startOver = true)
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()

        assertEquals(listOf(play("sample-tv-mix-3", 0)), video.calls)
        text("STARTED OVER · 11 MIN BEHIND LIVE").assertExists()
    }

    @Test
    fun `Audio and subtitles lists Plex's names, ticks the ones on, and a choice goes to the player`() {
        launch("tv-mix").run {
            playing()
            onActivity {
                video.events.onTracks(
                    TrackMenu(
                        audio = listOf(TrackOption("10", "English (TRUEHD 7.1)", true), TrackOption("11", "Commentary (English AC3 Stereo)", false)),
                        subtitles = listOf(TrackOption("20", "English Forced (PGS)", false)),
                    ),
                )
            }
            // Both kinds: one row, one list (Phase 13 keeps the options to five rows).
            press(KeyEvent.KEYCODE_MENU)
            text("Audio").assertDoesNotExist()
            text("Audio and subtitles").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            text("English (TRUEHD 7.1)").assertIsFocused()
            text("Commentary (English AC3 Stereo)").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals("track AUDIO 11", video.calls.last())
            // The subtitles follow under their own eyebrow, with Off, ticked while none is on.
            press(KeyEvent.KEYCODE_MENU)
            text("Audio and subtitles").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            text("SUBTITLES").assertExists()
            text("Off").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals("track SUBTITLES null", video.calls.last())
        }
    }

    @Test
    fun `Play pause pauses, lets the screen sleep, and the banner counts how far behind live`() {
        launch("tv-mix").run {
            playing()
            assertTrue(keptOn())
            press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            assertEquals("pause", video.calls.last())
            assertFalse(keptOn())
            app.nowMillis += 12 * 60_000L
            waitFor(1_000)
            text("PAUSED · 12 MIN BEHIND LIVE").assertExists()
            press(KeyEvent.KEYCODE_MEDIA_PLAY)
            assertEquals("resume", video.calls.last())
            playing()
            assertTrue(keptOn())
            text("12 MIN BEHIND LIVE").assertExists()
        }
    }

    @Test
    fun `Left opens the mini guide, Down picks the next channel without tuning, OK tunes in`() {
        launch("tv-mix").run {
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            text("CHANNELS").assertExists()
            compose.onNode(isSelected()).assert(hasText("TV Mix"))
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNode(isSelected()).assert(hasText("Classics"))
            waitFor(GuideTuning.SURF_DEBOUNCE_MILLIS)
            assertEquals(1, video.calls.size)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            text("CHANNELS").assertDoesNotExist()
            assertEquals(play("sample-classics-1", 11 * 60_000L), video.calls.last())
            text("The Silver Coast").assertExists()
        }
    }

    @Test
    fun `the mini guide closes with Back, or by itself after six seconds`() {
        launch("tv-mix").run {
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            text("CHANNELS").assertDoesNotExist()
            assertFalse(finishing())
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor(GuideTuning.MINI_GUIDE_MILLIS - 1_000)
            text("CHANNELS").assertExists()
            waitFor(1_000)
            text("CHANNELS").assertDoesNotExist()
            assertEquals(1, video.calls.size)
        }
    }

    @Test
    fun `Details shows over the picture, and Back closes it`() {
        launch("tv-mix").run {
            waitFor(5_000)
            press(KeyEvent.KEYCODE_MENU)
            text("Details").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            text("QUICK ACTIONS").assertDoesNotExist()
            text("Ridge Line").assertExists()
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            text("Ridge Line").assertDoesNotExist()
            assertFalse(finishing())
        }
    }

    @Test
    fun `Watch in Plezy hands a program off once the server is known, and says so when nothing can`() {
        // The details of a program that isn't on screen: nothing to write, so it hands off at once.
        val other = SampleGuide.ready().channels.single { it.id == "tv-mix" }.slotAt(SampleGuide.WEEKNIGHT)!!.copy(itemKey = "7303", startMillis = 0)
        launch("tv-mix").run {
            var watch: KeepWatching? = null
            onActivity { watch = it.actions.keepWatching(other) }
            assertNull("no server, no row", watch)
            app.serverId.value = "abc123"
            compose.waitForIdle()
            onActivity { it.actions.keepWatching(other)!!.run() }
            assertEquals(Handoffs.play(PlayIn.PLEZY, "abc123", "7303", profile = "u-owner"), app.handoffs.last())
            assertEquals(emptyList<String>(), app.plexWrites)
            onActivity { assertEquals("com.edde746.plezy", shadowOf(it).nextStartedActivity.`package`) }
            app.resolves = false
            onActivity { it.actions.keepWatching(other)!!.run() }
            compose.waitForIdle()
            text("Nothing on this Shield can open Ridge Line").assertExists()
        }
    }

    // Phase 11: Watch in Plezy or Plex, and the credits card.

    /** The sample lineup with Plex-like keys: a ratingKey is digits. */
    private fun numbered(): GuideCardState.Ready {
        val ready = SampleGuide.ready()
        var next = 7000
        val keys = mutableMapOf<String, String>()
        return ready.copy(
            channels = ready.channels.map { channel ->
                channel.copy(slots = channel.slots.map { slot -> slot.copy(itemKey = slot.itemKey?.let { keys.getOrPut(it) { (next++).toString() } }) })
            },
        )
    }

    /**
     * TV Mix with numbered keys, its program's frames showing, the server known: Ridge Line,
     * 11 minutes in, with [credits] its marker and [next] its next episode.
     */
    private fun watchingRidgeLine(credits: Long? = null, next: NextEpisode? = null): Pair<ActivityScenario<GuidePlayerActivity>, String> {
        val lineup = numbered()
        val key = lineup.channels.single { it.id == "tv-mix" }.slotAt(SampleGuide.WEEKNIGHT)!!.itemKey!!
        credits?.let { app.credits[key] = it }
        next?.let { app.nextEpisodes[key] = it }
        app.lineup.value = lineup
        app.serverId.value = "abc123"
        val scenario = launch("tv-mix")
        scenario.onActivity { video.events.onPlaying(true) }
        compose.waitForIdle()
        return scenario to key
    }

    private val paperMoons = NextEpisode("8105", "S2 E5", "Paper Moons", art = null)

    @Test
    fun `the options' first row is Watch in Plezy, or Watch in Plex by the setting`() {
        val (scenario, _) = watchingRidgeLine()
        scenario.run {
            press(KeyEvent.KEYCODE_MENU)
            text("Watch in Plezy").assertExists()
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            app.playIn.value = PlayIn.PLEX
            compose.waitForIdle()
            press(KeyEvent.KEYCODE_MENU)
            text("Watch in Plex").assertExists()
            text("Watch in Plezy").assertDoesNotExist()
        }
    }

    @Test
    fun `Watch in Plezy writes where the picture is first, then hands off to the profile's Plex user`() {
        val (scenario, key) = watchingRidgeLine()
        scenario.run {
            video.position = 11 * minute
            press(KeyEvent.KEYCODE_MENU)
            text("Watch in Plezy").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()

            assertEquals(listOf("progress $key @${11 * minute} as owner-token"), app.plexWrites)
            assertEquals(Handoffs.play(PlayIn.PLEZY, "abc123", key, profile = "u-owner"), app.handoffs.last())
            assertNull("no toast when Plex took it", ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `when Plex doesn't take the write, it hands off anyway and says where the app will start`() {
        val (scenario, key) = watchingRidgeLine()
        app.plexDown = true
        app.playIn.value = PlayIn.PLEX
        compose.waitForIdle()
        scenario.run {
            video.position = 11 * minute
            onActivity { it.actions.keepWatching(ridgeLine.copy(itemKey = key))!!.run() }
            compose.waitForIdle()

            assertEquals(Handoffs.play(PlayIn.PLEX, "abc123", key, profile = "u-owner"), app.handoffs.last())
            assertEquals("Plex starts where Plex last had it", ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `past the credits, Watch in marks it watched, so the title opens from the start`() {
        val (scenario, key) = watchingRidgeLine(credits = 49 * minute)
        scenario.run {
            video.position = 50 * minute
            onActivity { it.actions.keepWatching(ridgeLine.copy(itemKey = key))!!.run() }
            compose.waitForIdle()

            assertEquals(listOf("watched $key as owner-token"), app.plexWrites)
        }
    }

    @Test
    fun `at an episode's credits, the card offers the next one, and OK hands it off`() {
        val (scenario, _) = watchingRidgeLine(credits = 49 * minute, next = paperMoons)
        scenario.run {
            video.position = 49 * minute + 2_000
            waitFor(GuideViewing.TICK_MILLIS)
            text("NEXT: S2 E5").assertExists()
            text("Paper Moons").assertExists()

            press(KeyEvent.KEYCODE_DPAD_CENTER)

            text("Paper Moons").assertDoesNotExist()
            assertEquals(Handoffs.play(PlayIn.PLEZY, "abc123", "8105", profile = "u-owner"), app.handoffs.last())
            assertEquals("the next episode starts from where Plex has it", emptyList<String>(), app.plexWrites)
        }
    }

    @Test
    fun `Back takes the credits card away and the channel carries on`() {
        val (scenario, _) = watchingRidgeLine(next = paperMoons)
        scenario.run {
            // No marker: the last minute is the credits.
            video.position = 51 * minute + 30_000
            waitFor(GuideViewing.TICK_MILLIS)
            text("Paper Moons").assertExists()
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            text("Paper Moons").assertDoesNotExist()
            assertFalse(finishing())
            assertTrue(app.handoffs.isEmpty())
        }
    }

    // Phase 7: ⏪ and ⏩ (P1), the control row (P2), the saved spot (P3).

    private val minute = 60_000L
    private val ridgeLine get() = SampleGuide.ready().channels.single { it.id == "tv-mix" }.slotAt(SampleGuide.WEEKNIGHT)!!

    private fun seeks() = video.calls.filter { it.startsWith("seek") }

    /** Holds [keyCode] for [heldMillis], a repeat every 500 ms as the system sends them, without letting go. */
    private fun ActivityScenario<GuidePlayerActivity>.holdDown(keyCode: Int, heldMillis: Long) {
        onActivity {
            it.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
            var at = 500L
            var repeat = 1
            while (at <= heldMillis) {
                it.dispatchKeyEvent(KeyEvent(0, at, KeyEvent.ACTION_DOWN, keyCode, repeat++))
                at += 500
            }
        }
        compose.waitForIdle()
    }

    private fun ActivityScenario<GuidePlayerActivity>.letGo(keyCode: Int, atMillis: Long) {
        onActivity { it.dispatchKeyEvent(KeyEvent(0, atMillis, KeyEvent.ACTION_UP, keyCode, 0)) }
        compose.waitForIdle()
    }

    @Test
    fun `rewind goes back 10 seconds behind live, and fast forward to live is Back to live`() {
        launch("tv-mix").run {
            playing()
            press(KeyEvent.KEYCODE_MEDIA_REWIND)
            assertEquals(listOf(FakeGuideVideo.seek(11 * minute - 10_000)), seeks())
            text("UNDER A MINUTE BEHIND LIVE").assertExists()
            press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            assertEquals(play("sample-tv-mix-3", 11 * minute), video.calls.last())
            text("ON NOW").assertExists()
        }
    }

    @Test
    fun `holding rewind moves the marker, faster as it's held, and only letting go seeks`() {
        launch("tv-mix").run {
            playing()
            holdDown(KeyEvent.KEYCODE_MEDIA_REWIND, heldMillis = 500)
            // ×2 for half a second: 15 s back, at 10:45.
            text("REWIND ×2 · UNDER A MINUTE BEHIND LIVE").assertExists()
            text("10:45 in").assertExists()
            holdDown(KeyEvent.KEYCODE_MEDIA_REWIND, heldMillis = 2_500)
            // 2 s at ×2 and half a second at ×4: 90 s back.
            text("REWIND ×4 · 1 MIN BEHIND LIVE").assertExists()
            text("9:30 in").assertExists()
            assertTrue(seeks().isEmpty())
            letGo(KeyEvent.KEYCODE_MEDIA_REWIND, atMillis = 2_600)
            assertEquals(listOf(FakeGuideVideo.seek(9 * minute + 30_000)), seeks())
            text("1 MIN BEHIND LIVE").assertExists()
        }
    }

    @Test
    fun `under the options, rewind and fast forward do nothing`() {
        launch("tv-mix").run {
            playing()
            press(KeyEvent.KEYCODE_MENU)
            press(KeyEvent.KEYCODE_MEDIA_REWIND)
            assertTrue(seeks().isEmpty())
            text("QUICK ACTIONS").assertExists()
        }
    }

    @Test
    fun `OK shows the control row with Pause focused, Left and Right move along it, and OK presses`() {
        launch("tv-mix").run {
            playing()
            waitFor(5_000)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            text("Pause").assertIsFocused()
            text("Back to live").assertDoesNotExist()
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithContentDescription("Back 10 seconds").assertIsFocused()
            // The mini guide waits while the row's up.
            text("CHANNELS").assertDoesNotExist()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(listOf(FakeGuideVideo.seek(11 * minute - 10_000)), seeks())
            text("UNDER A MINUTE BEHIND LIVE").assertExists()
            repeat(3) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
            text("Back to live").assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(play("sample-tv-mix-3", 11 * minute), video.calls.last())
            // Live, Back to live goes and Pause has focus.
            text("Back to live").assertDoesNotExist()
            text("Pause").assertIsFocused()
        }
    }

    @Test
    fun `Pause in the row pauses, and Play carries on`() {
        launch("tv-mix").run {
            playing()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals("pause", video.calls.last())
            text("Play").assertIsFocused()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals("resume", video.calls.last())
            text("Pause").assertIsFocused()
        }
    }

    @Test
    fun `the row goes with the banner 5 s after the last press, and then Left opens the mini guide`() {
        launch("tv-mix").run {
            playing()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor(4_000)
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            waitFor(4_000)
            compose.onNodeWithContentDescription("Forward 30 seconds").assertIsFocused()
            waitFor(1_000)
            text("Pause").assertDoesNotExist()
            text("Ridge Line").assertDoesNotExist()
            press(KeyEvent.KEYCODE_DPAD_LEFT)
            text("CHANNELS").assertExists()
        }
    }

    @Test
    fun `Back with the row up takes it away, and the next Back leaves`() {
        launch("tv-mix").run {
            playing()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            text("Pause").assertDoesNotExist()
            assertFalse(finishing())
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertTrue(finishing())
        }
    }

    @Test
    fun `Up and Down still surf with the row up`() {
        launch("tv-mix").run {
            playing()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            press(KeyEvent.KEYCODE_DPAD_UP)
            text("Classics").assertExists()
            text("Pause").assertDoesNotExist()
        }
    }

    @Test
    fun `over filler OK shows the banner without the row`() {
        launch("scifi").run {
            playing()
            waitFor(5_000)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            text("Sci-Fi").assertExists()
            text("Pause").assertDoesNotExist()
        }
    }

    @Test
    fun `Home pauses where it is and saves the spot`() {
        launch("tv-mix").run {
            playing()
            // Home closes the player at once: stopped and destroyed, never by Back.
            close()
            scenario = null
        }
        assertEquals(SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 11 * minute, SampleGuide.WEEKNIGHT), app.spots.value)
    }

    @Test
    fun `Back leaves no spot`() {
        launch("tv-mix").run {
            playing()
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            close()
            scenario = null
        }
        assertNull(app.spots.value)
    }

    @Test
    fun `stopped, it saves the spot and comes back on that frame paused, and Back then takes the spot back`() {
        launch("tv-mix").run {
            playing()
            moveToState(Lifecycle.State.CREATED)
            assertEquals(11 * minute, app.spots.value?.positionMs)
            moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            assertEquals(listOf(play("sample-tv-mix-3", 11 * minute), "pause"), video.calls)
            text("PAUSED · UNDER A MINUTE BEHIND LIVE").assertExists()
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
        assertNull(app.spots.value)
    }

    @Test
    fun `a spot opens paused on its frame, saying how far behind live, and Play carries on`() {
        val spot = SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 2 * minute, SampleGuide.WEEKNIGHT - 9 * minute)
        scenario = ActivityScenario.launch(GuidePlayerActivity.resume(app, spot))
        compose.waitForIdle()

        assertEquals(listOf(play("sample-tv-mix-3", 2 * minute), "pause"), video.calls)
        text("PAUSED · 9 MIN BEHIND LIVE").assertExists()
        text("Ridge Line").assertExists()
        scenario!!.press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        assertEquals("resume", video.calls.last())
        scenario!!.playing()
        text("9 MIN BEHIND LIVE").assertExists()
    }

    @Test
    fun `the resume intent carries the spot`() {
        val spot = SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 2 * minute, SampleGuide.WEEKNIGHT)
        assertEquals(spot, GuidePlayerActivity.spotOf(GuidePlayerActivity.resume(app, spot)))
        assertNull(GuidePlayerActivity.spotOf(GuidePlayerActivity.intent(app, "tv-mix")))
    }

    // P5: keeping your place, whichever button you leave by.

    @Test
    fun `tuning in to another channel keeps the spot`() {
        val spot = SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 2 * minute, SampleGuide.WEEKNIGHT)
        runBlocking { app.spots.save(spot) }
        launch("tv-mix").run {
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            assertEquals(spot, app.spots.value)
        }
    }

    @Test
    fun `Back keeps the place of a program you were watching`() {
        launch("tv-mix").run {
            playing()
            // Paused, a moment in: you were watching, not surfing past.
            press(KeyEvent.KEYCODE_MEDIA_PAUSE)
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            close()
            scenario = null
        }
        assertEquals(SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 11 * minute, SampleGuide.WEEKNIGHT), app.spots.value)
    }

    @Test
    fun `back on its channel, the pick-up card offers the place, and OK picks it up`() {
        runBlocking { app.spots.save(SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 2 * minute, SampleGuide.WEEKNIGHT - 9 * minute)) }
        launch("tv-mix").run {
            // It tunes in live underneath.
            assertEquals(play("sample-tv-mix-3", 11 * minute), video.calls.last())
            text("PICK UP WHERE YOU LEFT OFF").assertExists()
            text("2:00 in · 9 min ago").assertExists()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            assertEquals(play("sample-tv-mix-3", 2 * minute), video.calls.last())
            text("PICK UP WHERE YOU LEFT OFF").assertDoesNotExist()
            text("PICKED UP · 9 MIN BEHIND LIVE").assertExists()
        }
    }

    @Test
    fun `Back takes the pick-up card away, and the channel carries on live`() {
        runBlocking { app.spots.save(SavedSpot("tv-mix", ridgeLine.startMillis, "sample-tv-mix-3", 2 * minute, SampleGuide.WEEKNIGHT - 9 * minute)) }
        launch("tv-mix").run {
            text("PICK UP WHERE YOU LEFT OFF").assertExists()
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            text("PICK UP WHERE YOU LEFT OFF").assertDoesNotExist()
            assertEquals(play("sample-tv-mix-3", 11 * minute), video.calls.last())
            assertFalse(finishing())
        }
    }
}
