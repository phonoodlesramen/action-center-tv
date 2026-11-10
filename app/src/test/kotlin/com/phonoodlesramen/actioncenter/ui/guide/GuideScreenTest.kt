package com.phonoodlesramen.actioncenter.ui.guide

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The full guide's keys (plan §5, "Testing strategy"): focus by time across channels,
 * paging past the window both ways and its limits, OK (tune in, or a later program's
 * details), hold OK and Menu (2.7), Back, screen care and each wait before there are
 * channels (G20 B). The sample lineup at 8:41 PM, in UTC.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class GuideScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tunedIn = mutableListOf<String>()
    private val done = mutableListOf<String>()
    private var backs = 0
    private val care = GuideScreenCare()

    private val actions = object : HomeActions {
        override fun tuneIn(channelId: String) {
            tunedIn += channelId
        }

        override fun startOver(channelId: String) {
            done += "start over $channelId"
        }

        override fun openDetails(channelId: String, startMillis: Long) {
            done += "details $channelId ${GuideTime.clock(startMillis, ZoneOffset.UTC)}"
        }

        override fun play(id: String, title: String) {
            done += "play $title"
        }

        override fun setFavorite(channel: GuideChannel, favorite: Boolean) {
            done += "favorite ${channel.id} $favorite"
        }
    }

    private var lineup by mutableStateOf<GuideCardState>(SampleGuide.ready())
    private var plexLinked by mutableStateOf(true)

    private var favorites by mutableStateOf<List<String>>(emptyList())

    private fun show(state: GuideCardState = SampleGuide.ready(), focusChannelId: String? = null, openAt: Long? = null) {
        lineup = state
        compose.setContent {
            ActionCenterTheme {
                GuideScreen(
                    state = lineup,
                    nowMillis = SampleGuide.WEEKNIGHT,
                    actions = actions,
                    onBack = { backs++ },
                    zone = ZoneOffset.UTC,
                    focusChannelId = focusChannelId,
                    openAtMillis = openAt,
                    plexLinked = plexLinked,
                    care = care,
                    favorites = favorites,
                )
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /**
     * Holds OK as the remote does: the press, its first repeat (tv-material's long click),
     * then, once whatever that opened has taken focus, the release.
     */
    private fun holdOk() {
        fun ok(action: Int, repeat: Int) =
            compose.onRoot().performKeyPress(KeyEvent(NativeKeyEvent(0L, 0L, action, NativeKeyEvent.KEYCODE_DPAD_CENTER, repeat)))
        ok(NativeKeyEvent.ACTION_DOWN, 0)
        ok(NativeKeyEvent.ACTION_DOWN, 1)
        compose.waitForIdle()
        ok(NativeKeyEvent.ACTION_UP, 0)
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The focused cell is "Channel, Title, time". */
    private fun assertFocused(description: String) =
        compose.onNode(isFocused()).assert(hasContentDescription(description, substring = true))

    // Favorites (Phase 13, C2, C4).

    @Test
    fun `favorites lead the guide, and focus stays on its program as they change`() {
        favorites = SampleHomeState.favorites
        show()
        // Comedy, then TV Mix, then the rest by number: the guide opens on the first, Comedy.
        assertFocused("Comedy, Open House")
        press(Key.DirectionDown, times = 2)
        assertFocused("Movies, Quiet Field")
        press(Key.DirectionDown, times = 2)
        assertFocused("Classics, The Silver Coast")

        // Classics becomes a favorite: it moves up to the top, and focus stays on its program.
        favorites = listOf("classics") + SampleHomeState.favorites
        compose.waitForIdle()
        assertFocused("Classics, The Silver Coast")
        press(Key.DirectionDown)
        assertFocused("Comedy, Open House")
        press(Key.DirectionUp, times = 2)
        assertFocused("Classics, The Silver Coast")
    }

    @Test
    fun `hold OK on a program adds its channel to favorites, or removes a favorite`() {
        favorites = SampleHomeState.favorites
        show()
        press(Key.DirectionDown, times = 2)
        press(Key.Menu)
        compose.onNodeWithText("Add Movies to favorites").assertExists()
        compose.onNodeWithText("Add Movies to favorites").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("favorite movies true"), done)
        assertFocused("Movies, Quiet Field")

        press(Key.DirectionUp)
        press(Key.Menu)
        compose.onNodeWithText("Remove TV Mix from favorites").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals("favorite tv-mix false", done.last())
    }

    @Test
    fun `opens at now with the first channel's program focused`() {
        show()
        assertFocused("Movies, Quiet Field, 8:30 – 10:41 PM")
        compose.onNodeWithText("On now · 2h left").assertIsDisplayed()
        compose.onNodeWithText("8:30 PM").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
    }

    @Test
    fun `Down keeps the time across channels, not the column`() {
        show()
        // To the 10:41 trailer: the window pages to 10:30, and 10:41 is the time Down keeps.
        press(Key.DirectionRight)
        assertFocused("Movies, Trailer: The Long Signal")
        press(Key.DirectionDown)
        assertFocused("Sci-Fi, Orbit of Glass")
        press(Key.DirectionDown)
        assertFocused("TV Mix, Night Shift")
        press(Key.DirectionDown)
        assertFocused("Classics, A Train at Midnight")
        press(Key.DirectionUp, times = 3)
        assertFocused("Movies, Trailer: The Long Signal")
    }

    @Test
    fun `Right past the window pages forward, Left before it pages back`() {
        show()
        press(Key.DirectionRight)
        compose.onNodeWithText("10:30 PM").assertIsDisplayed()
        compose.onNodeWithText("8:30 PM").assertDoesNotExist()
        // Quiet Field still shows at the window's left: no page.
        press(Key.DirectionLeft)
        assertFocused("Movies, Quiet Field")
        compose.onNodeWithText("10:30 PM").assertIsDisplayed()
        // The trailer before it ended at 8:30: the window pages back to 7:00-8:30.
        press(Key.DirectionLeft)
        assertFocused("Movies, Trailer: Quiet Field")
        compose.onNodeWithText("7:00 PM").assertIsDisplayed()
    }

    @Test
    fun `Left stops at the earliest slot, Right at the end of the 24 hours`() {
        show()
        press(Key.DirectionLeft, times = 6)
        assertFocused("Movies, Glowing Reef, 6:30 – 8:06 PM")
        compose.onNodeWithText("6:30 PM").assertIsDisplayed()

        press(Key.DirectionRight, times = 40)
        // The half hour after 8:41 PM tomorrow ends the last window: 7:30-9:00 PM.
        compose.onNodeWithText("7:30 PM").assertIsDisplayed()
        compose.onNodeWithText("Tomorrow").assertIsDisplayed()
        assertFocused("Movies, Trailer: Paper Harbor, 8:44 – 9:00 PM")
    }

    @Test
    fun `OK on something on now tunes in to its channel`() {
        show()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertEquals(listOf("tv-mix"), tunedIn)
    }

    @Test
    fun `OK on a later program opens its details, and doesn't tune in`() {
        show()
        press(Key.DirectionRight, times = 2)
        assertFocused("Movies, The Long Signal, 11:00 PM")
        compose.onNodeWithText("Starts in 2h 19m").assertIsDisplayed()
        compose.onNodeWithText("OK for details").assertIsDisplayed()
        press(Key.DirectionCenter)
        assertEquals(listOf("details movies 11:00"), done)
        assertTrue(tunedIn.isEmpty())
    }

    @Test
    fun `opened at a program (Back from its details), that program has focus`() {
        val later = SampleGuide.ready().channels.first { it.id == "tv-mix" }.slots.first { it.title == "Kitchen Table" && it.startMillis > SampleGuide.WEEKNIGHT }
        show(focusChannelId = "tv-mix", openAt = later.startMillis)
        assertFocused("TV Mix, Kitchen Table, 9:30 – 9:52 PM")
    }

    @Test
    fun `holding OK on something on now offers Watch, Start over, Details and Open in Plezy`() {
        show()
        holdOk()
        // The release that ended the hold doesn't pick the first action.
        assertEquals(emptyList<String>(), tunedIn)
        compose.onNodeWithText("Watch").assertIsFocused()
        compose.onNodeWithText("Start over").assertExists()
        compose.onNodeWithText("Details").assertExists()
        compose.onNodeWithText("Open in Plezy").assertExists()
        assertTrue(tunedIn.isEmpty())

        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(listOf("start over movies"), done)
        compose.onNodeWithText("Details").assertDoesNotExist()
        assertFocused("Movies, Quiet Field")
    }

    @Test
    fun `Menu on a later program offers its Details and Open in Plezy, and Back returns to it`() {
        show()
        press(Key.DirectionRight, times = 2)
        press(Key.Menu)
        compose.onNodeWithText("Details").assertIsFocused()
        compose.onNodeWithText("Watch").assertDoesNotExist()
        compose.onNodeWithText("Start over").assertDoesNotExist()
        compose.onNodeWithText("Open in Plezy").assertExists()

        back()
        compose.onNodeWithText("Details").assertDoesNotExist()
        assertFocused("Movies, The Long Signal")
        assertEquals(0, backs)

        press(Key.Menu)
        press(Key.DirectionCenter)
        assertEquals(listOf("details movies 11:00"), done)
        press(Key.Menu)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals("play The Long Signal", done.last())
    }

    @Test
    fun `filler's quick actions leave out Start over and Open in Plezy`() {
        show()
        press(Key.DirectionDown)
        assertFocused("Sci-Fi, Trailer: Orbit of Glass")
        press(Key.Menu)
        compose.onNodeWithText("Watch").assertIsFocused()
        compose.onNodeWithText("Details").assertExists()
        compose.onNodeWithText("Start over").assertDoesNotExist()
        compose.onNodeWithText("Open in Plezy").assertDoesNotExist()
    }

    @Test
    fun `Back calls onBack`() {
        show()
        back()
        assertEquals(1, backs)
    }

    @Test
    fun `without a lineup it says the channels come with the library, and Back still works`() {
        show(GuideCardState.NotSetUp)
        compose.onNodeWithText("No channels yet: they appear after your library syncs").assertIsDisplayed()
        back()
        assertEquals(1, backs)
    }

    @Test
    fun `each wait before there are channels has its own line`() {
        plexLinked = false
        show(GuideCardState.NotSetUp)
        compose.onNodeWithText("No channels yet: they appear once Plex is connected").assertIsDisplayed()
        plexLinked = true
        lineup = GuideCardState.Building
        compose.onNodeWithText("No channels yet: they're being made from your library now").assertIsDisplayed()
        lineup = GuideCardState.NothingToFill
        compose.onNodeWithText("No channels yet: not enough in your library to fill a channel").assertIsDisplayed()
        lineup = GuideCardState.Ready(channels = emptyList(), updatedAtMillis = null)
        compose.onNodeWithText("No channels yet: they appear after your library syncs").assertIsDisplayed()
    }

    @Test
    fun `the wording is one family across the Guide card and the full guide`() {
        assertEquals(NoChannelsYet.NOT_CONNECTED, NoChannelsYet.of(GuideCardState.NotSetUp, plexLinked = false))
        assertEquals(NoChannelsYet.NOT_SYNCED, NoChannelsYet.of(GuideCardState.NotSetUp, plexLinked = true))
        assertEquals(NoChannelsYet.BUILDING, NoChannelsYet.of(GuideCardState.Building, plexLinked = true))
        assertEquals(NoChannelsYet.NOTHING_TO_FILL, NoChannelsYet.of(GuideCardState.NothingToFill, plexLinked = true))
        assertEquals(NoChannelsYet.NONE_ON, NoChannelsYet.of(GuideCardState.NoneOn, plexLinked = true))
        assertEquals(null, NoChannelsYet.of(SampleGuide.ready(), plexLinked = true))
        // Every full-guide line is "No channels yet:" then the card's own line, without its "here" or "yet".
        NoChannelsYet.entries.forEach { why ->
            val card = why.card.replace(" here", "").removeSuffix(" yet").replaceFirstChar(Char::lowercaseChar)
            assertEquals("No channels yet: $card", why.guide)
        }
    }

    @Test
    fun `once dimmed, the first key only wakes the guide`() {
        show()
        compose.runOnUiThread { care.show() }
        compose.waitForIdle()
        press(Key.DirectionDown)
        assertFalse(care.showing)
        assertFocused("Movies, Quiet Field")
        press(Key.DirectionDown)
        assertFocused("Sci-Fi, Trailer: Orbit of Glass")
    }
}
