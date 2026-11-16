package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.nfl.NflOpen
import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The side rail (spec §4, Home layouts, H4): beside the dashboard and Marquee, closed and open,
 * at 90, 100 and 115%, and its D-pad: Left opens it on Home, Right and Back return, OK opens.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class SideRailTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var focus by mutableStateOf(FocusRequest())
    private val opened = mutableListOf<String>()

    private val actions = object : HomeActions {
        override fun openSettings() {
            opened += "settings"
        }

        override fun openGuide(channelId: String?) {
            opened += "guide"
        }
    }

    private fun railed(layoutScale: Float = 1f, layout: HomeLayout = HomeLayout.DASHBOARD) =
        SampleHomeState.state(layoutScale = layoutScale, homeLayout = layout, sideRail = true)

    private fun show(state: HomeUiState = railed(), nfl: NflView = NflView.None) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, actions, focus = focus, guide = SampleGuide.ready(state.nowMillis), favorites = SampleHomeState.favorites, nfl = nfl)
            }
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun besideTheDashboard() {
        show()
        capture("home_side_rail")
    }

    @Test
    fun besideTheDashboardAtSmallestScale() {
        show(railed(LayoutScale.MIN))
        capture("home_side_rail_scale_90")
    }

    @Test
    fun besideTheDashboardAtLargestScale() {
        show(railed(LayoutScale.MAX))
        capture("home_side_rail_scale_115")
    }

    @Test
    fun leftFromPlayOpensItOnHome() {
        show()
        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Home").assertIsFocused()
        capture("home_side_rail_open")
    }

    @Test
    fun openAtLargestScale() {
        show(railed(LayoutScale.MAX))
        press(Key.DirectionLeft)
        capture("home_side_rail_open_scale_115")
    }

    @Test
    fun besideMarquee() {
        show(railed(layout = HomeLayout.MARQUEE))
        capture("home_side_rail_marquee")
    }

    @Test
    fun rightReturnsToTheCardYouLeft() {
        show()
        val slowHorses = compose.onNodeWithContentDescription("Slow Horses, S4E2, 41m left")
        press(Key.DirectionDown)
        slowHorses.assertIsFocused()
        press(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Home").assertIsFocused()
        press(Key.DirectionRight)
        slowHorses.assertIsFocused()
    }

    @Test
    fun backReturnsToTheCardYouLeft() {
        show()
        press(Key.DirectionLeft)
        compose.activity.onBackPressedDispatcher.onBackPressed()
        settle()
        compose.onNodeWithText("Play S2E9").assertIsFocused()
    }

    @Test
    fun okOpensItsScreenAndBackLandsOnIt() {
        show()
        compose.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Guide").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("settings", "guide"), opened)

        focus = FocusRequest(HomeFocus.SETTINGS, serial = 1)
        settle()
        compose.onNodeWithContentDescription("Settings").assertIsFocused()
    }

    @Test
    fun theTopBarsRoundButtonsMoveIntoIt() {
        show()
        assertEquals(1, compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().size)
    }

    @Test
    fun backFromTheNflScreenItOpenedLandsOnItsNfl() {
        show(nfl = SampleNfl.view())
        focus = FocusRequest(HomeFocus.NFL, serial = 1, nflKey = NflOpen.RAIL)
        settle()
        compose.onNodeWithContentDescription("NFL").assertIsFocused()
    }

    @Test
    fun aKidsHomeHasNone() {
        show(SampleHomeState.kid().copy(sideRail = true))
        compose.onNodeWithContentDescription("Home").assertDoesNotExist()
    }
}
