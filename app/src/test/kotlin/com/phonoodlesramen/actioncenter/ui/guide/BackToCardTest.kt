package com.phonoodlesramen.actioncenter.ui.guide

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.FocusRequest
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.home.backToCardFocus
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The way back to a saved spot (P4) on the home at the weeknight's 8:41 PM: the Guide card as
 * "Back to {channel}" on yours, the first card of a kid's Live row on theirs, their quick
 * actions, and where focus goes. The actions forget the spot as the home would.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class BackToCardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val minute = 60_000L
    private val weeknight = SampleGuide.WEEKNIGHT
    private val lineup = SampleGuide.withKidChannels(weeknight)

    private var focus by mutableStateOf(FocusRequest())
    private var spot by mutableStateOf<SavedSpot?>(null)
    private val done = mutableListOf<String>()

    private val actions = object : HomeActions {
        override fun resume(spot: SavedSpot) {
            done += "resume ${spot.channelId} at ${spot.positionMs / 1_000} s"
        }

        override fun backToLive(channelId: String) {
            done += "live $channelId"
            spot = null
        }

        override fun forgetSpot() {
            done += "forget"
            spot = null
        }

        override fun openGuide(channelId: String?) {
            done += "guide"
        }
    }

    private fun program(channelId: String, at: Long = weeknight) = lineup.channels.single { it.id == channelId }.slotAt(at)!!

    /** The Ninth Moon on Sci-Fi (6:30-8:31 PM), paused 1:12:30 in, 9 minutes ago. */
    private val ninthMoon by lazy {
        val slot = program("scifi", weeknight - 60 * minute)
        SavedSpot("scifi", slot.startMillis, slot.itemKey!!, 72 * minute + 30_000, weeknight - 9 * minute)
    }

    /** Tide Pool Friends on Cartoon Club (8:30-8:52 PM), paused 6:30 in, 4 minutes ago: kid-safe for Maya. */
    private val tidePool by lazy {
        val slot = program("cartoons")
        SavedSpot("cartoons", slot.startMillis, slot.itemKey!!, 6 * minute + 30_000, weeknight - 4 * minute)
    }

    private fun show(state: HomeUiState = SampleHomeState.state(), initial: SavedSpot? = ninthMoon) {
        spot = initial
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, actions, focus = focus, guide = lineup, spot = spot)
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun text(text: String) = compose.onNodeWithText(text)

    private fun lineupCard() = compose.onNodeWithText("Coming up at", substring = true)

    private fun live(name: String) = compose.onNodeWithContentDescription("$name,", substring = true)

    // Yours: the Guide card.

    @Test
    fun `while a spot holds the Guide card is Back to its channel, and Play keeps focus`() {
        show()
        text("Play S2E9").assertIsFocused()
        text("BACK TO SCI-FI").assertIsDisplayed()
        text("The Ninth Moon").assertIsDisplayed()
        text("Paused 1:12:30 in · 9 min ago").assertIsDisplayed()
        lineupCard().assertDoesNotExist()
    }

    @Test
    fun `OK on it resumes the spot`() {
        show()
        text("BACK TO SCI-FI").requestFocus()
        press(Key.DirectionCenter)
        assertEquals(listOf("resume scifi at 4350 s"), done)
    }

    @Test
    fun `its quick actions are Resume, Back to live and Forget this, and Back to live tunes the channel in`() {
        show()
        text("BACK TO SCI-FI").requestFocus()
        press(Key.Menu)
        text("QUICK ACTIONS").assertExists()
        text("Resume").assertIsFocused()
        text("Forget this").assertExists()
        press(Key.DirectionDown)
        text("Back to live").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("live scifi"), done)
    }

    @Test
    fun `Forget this puts the card back to its lineup, with focus still on it`() {
        show()
        text("BACK TO SCI-FI").requestFocus()
        press(Key.Menu)
        press(Key.DirectionDown, times = 2)
        text("Forget this").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("forget"), done)
        text("BACK TO SCI-FI").assertDoesNotExist()
        lineupCard().assertIsFocused()
        // OK is the full guide's again.
        press(Key.DirectionCenter)
        assertEquals(listOf("forget", "guide"), done)
    }

    @Test
    fun `an old spot, or one whose program has gone, leaves the card as it was`() {
        show(initial = ninthMoon.copy(savedAtMillis = weeknight - 4 * 60 * minute))
        text("BACK TO SCI-FI").assertDoesNotExist()
        lineupCard().assertExists()
        spot = ninthMoon.copy(slotStartMillis = weeknight - 10 * 60 * minute)
        compose.waitForIdle()
        text("BACK TO SCI-FI").assertDoesNotExist()
        spot = ninthMoon
        compose.waitForIdle()
        text("BACK TO SCI-FI").assertExists()
    }

    @Test
    fun `Back from a resumed player lands on the Guide card`() {
        // Resuming took the spot.
        show(initial = null)
        focus = backToCardFocus(kid = false, channelId = "scifi").copy(serial = 1)
        compose.waitForIdle()
        lineupCard().assertIsFocused()
    }

    // A kid's: the first card of their Live row.

    @Test
    fun `a kid's Back-to card is first in their Live row, and OK resumes it`() {
        show(SampleHomeState.kid(), tidePool)
        text("Play").assertIsFocused()
        // No Guide card on a kid's home.
        text("BACK TO CARTOON CLUB").assertDoesNotExist()
        press(Key.DirectionDown, times = 2)
        compose.onNode(isFocused()).assert(hasContentDescription("Back to Cartoon Club, Tide Pool Friends, Paused 6:30 in · 4 min ago"))
        text("Paused 6:30 in · 4 min ago").assertIsDisplayed()
        press(Key.DirectionCenter)
        assertEquals(listOf("resume cartoons at 390 s"), done)
        press(Key.DirectionRight)
        live("Family").assertIsFocused()
    }

    @Test
    fun `a kid gets no Back-to card for a channel that isn't kid-safe for them`() {
        show(SampleHomeState.kid(), ninthMoon)
        press(Key.DirectionDown, times = 2)
        live("Family").assertIsFocused()
        compose.onNodeWithContentDescription("Back to", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Forget this on a kid's card takes it away, and focus moves on to the next card`() {
        show(SampleHomeState.kid(), tidePool)
        press(Key.DirectionDown, times = 2)
        press(Key.Menu)
        text("Resume").assertIsFocused()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertEquals(listOf("forget"), done)
        compose.onNodeWithContentDescription("Back to", substring = true).assertDoesNotExist()
        live("Family").assertIsFocused()
    }

    @Test
    fun `Back from a kid's resumed player lands on the Live card of that channel`() {
        show(SampleHomeState.kid(), initial = null)
        focus = backToCardFocus(kid = true, channelId = "cartoons").copy(serial = 1)
        compose.waitForIdle()
        live("Cartoon Club").assertIsFocused()
    }
}
