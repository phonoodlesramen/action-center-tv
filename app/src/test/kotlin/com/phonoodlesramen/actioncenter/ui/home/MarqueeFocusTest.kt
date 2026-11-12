package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Marquee's D-pad (spec §4, Marquee): the strip, the title's buttons, the rows, Home and Back. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class MarqueeFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var focus by mutableStateOf(FocusRequest())
    private val opened = mutableListOf<String>()

    private val actions = object : HomeActions {
        override fun play(id: String, title: String) {
            opened += "play $title"
        }

        override fun tuneIn(channelId: String) {
            opened += "tune $channelId"
        }
    }

    private fun show(state: HomeUiState = SampleHomeState.state(homeLayout = HomeLayout.MARQUEE)) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, actions, focus = focus, guide = SampleGuide.ready(), favorites = SampleHomeState.favorites)
            }
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun card(description: String) = compose.onNodeWithContentDescription(description)

    private val severance = "Severance, Season 2"
    private val heat = "Heat, Throwback lens"
    private val live = "Open House, Live on 5 Comedy"

    @Test
    fun focusStartsOnTheStripsFirstCard() {
        show()
        card(severance).assertIsFocused()
        compose.onNodeWithText("Play S2E9").assertIsDisplayed()
    }

    @Test
    fun restingOnACardPutsItsTitleAndButtonsAbove() {
        show()
        press(Key.DirectionRight)
        card(heat).assertIsFocused()
        compose.onNodeWithText("One year ago today you watched").assertIsDisplayed()
        compose.onNodeWithText("Watch again").assertIsDisplayed()
    }

    @Test
    fun upReachesTheButtonsAndDownReturnsToTheCard() {
        show()
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionUp)
        compose.onNodeWithText("Resume").assertIsFocused()
        press(Key.DirectionDown)
        card("Slow Horses, S4E2 · 41m left").assertIsFocused()
    }

    @Test
    fun okOnACardPlaysItOrTunesItsChannel() {
        show()
        card(heat).performSemanticsAction(SemanticsActions.OnClick)
        card(live).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("play Heat", "tune comedy"), opened)
    }

    @Test
    fun downFromTheStripReachesTheRowsBelow() {
        show()
        press(Key.DirectionDown)
        // Where the strip has scrolled to, under the focused row.
        val strip = card(severance).getBoundsInRoot()
        val focused = compose.onNode(isFocused()).getBoundsInRoot()
        assertTrue("focus went below the strip", focused.top > strip.bottom)
    }

    @Test
    fun backFromTheGuideLandsOnTheLiveCard() {
        show()
        focus = FocusRequest(HomeFocus.GUIDE, serial = 1)
        settle()
        card(live).assertIsFocused()
    }

    @Test
    fun homeGoesBackToTheFirstCardAndItsTitle() {
        show()
        press(Key.DirectionRight, times = 3)
        focus = FocusRequest(HomeFocus.PLAY, serial = 1)
        settle()
        card(severance).assertIsFocused()
        compose.onNodeWithText("Play S2E9").assertIsDisplayed()
    }

    @Test
    fun aKidsHomeKeepsItsOwnLayout() {
        show(SampleHomeState.kid().copy(homeLayout = HomeLayout.MARQUEE))
        compose.onNodeWithText("FOR TONIGHT").assertDoesNotExist()
    }

    @Test
    fun beforeTheLibraryItsTheDashboardWithThePlexCode() {
        show(SampleHomeState.state(sample = false, plex = SampleHomeState.plexCode, homeLayout = HomeLayout.MARQUEE))
        compose.onNodeWithText("FOR TONIGHT").assertDoesNotExist()
        compose.onNodeWithText("plex.tv/link", substring = true).assertExists()
    }
}
