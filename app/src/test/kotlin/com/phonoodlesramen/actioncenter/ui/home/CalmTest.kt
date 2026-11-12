package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Calm (spec §4, Home layouts, H6) at the Shield's 1080p, at 90, 100 and 115%: the clock, the
 * one line and the dock over the wallpaper, the dashboard as a sheet, and their D-pad.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class CalmTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var focus by mutableStateOf(FocusRequest())
    private val opened = mutableListOf<String>()

    private val actions = object : HomeActions {
        override fun play(id: String, title: String) {
            opened += "play $title"
        }

        override fun openGuide(channelId: String?) {
            opened += "guide"
        }
    }

    private fun calm(layoutScale: Float = 1f, time: java.time.LocalDateTime = SampleHomeState.weeknight, moments: Boolean = false) =
        SampleHomeState.state(layoutScale = layoutScale, time = time, homeLayout = HomeLayout.CALM, moments = moments)

    private fun show(state: HomeUiState = calm(), wallpaper: WallpaperPrefs = WallpaperPrefs()) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, actions, focus = focus, guide = SampleGuide.ready(state.nowMillis), favorites = SampleHomeState.favorites, wallpaper = wallpaper)
            }
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun back() {
        compose.activity.onBackPressedDispatcher.onBackPressed()
        settle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun firstScreen() {
        show()
        capture("home_calm")
    }

    @Test
    fun firstScreenAtSmallestScale() {
        show(calm(LayoutScale.MIN))
        capture("home_calm_scale_90")
    }

    @Test
    fun firstScreenAtLargestScale() {
        show(calm(LayoutScale.MAX))
        capture("home_calm_scale_115")
    }

    @Test
    fun overTheLivingSky() {
        show(wallpaper = WallpaperPrefs(style = WallpaperStyle.LIVING_SKY))
        capture("home_calm_living_sky")
    }

    @Test
    fun lateNightsComfortRewatch() {
        show(calm(time = SampleHomeState.lateNight, moments = true))
        compose.onNodeWithText("The Grand Budapest Hotel").assertExists()
        capture("home_calm_late_night")
    }

    @Test
    fun focusStartsOnPlayAndOkPlays() {
        show()
        compose.onNodeWithText("Play S2E9").assertIsFocused()
        compose.onNodeWithText("Play S2E9").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("play Severance"), opened)
    }

    @Test
    fun downTwiceBringsTheDashboardUpAndBackDropsIt() {
        show()
        press(Key.DirectionDown)
        // The dock, under Play.
        compose.onNodeWithText("Play S2E9").assertIsNotFocused()
        compose.onNodeWithText("TONIGHT'S PICK").assertDoesNotExist()
        press(Key.DirectionDown)
        // The sheet: the dashboard, Play on Tonight's Pick focused.
        compose.onNodeWithText("TONIGHT'S PICK").assertExists()
        compose.onNodeWithText("Play S2E9").assertIsFocused()
        capture("home_calm_sheet")
        back()
        compose.onNodeWithText("TONIGHT'S PICK").assertDoesNotExist()
        compose.onNodeWithText("Play S2E9").assertIsFocused()
    }

    @Test
    fun theDocksGuideOpensItAndBackLandsThere() {
        show()
        compose.onNodeWithContentDescription("Guide").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("guide"), opened)
        focus = FocusRequest(HomeFocus.GUIDE, serial = 1)
        settle()
        compose.onNodeWithContentDescription("Guide").assertIsFocused()
    }

    @Test
    fun backFromSettingsLandsOnTheSheetsGear() {
        show()
        focus = FocusRequest(HomeFocus.SETTINGS, serial = 1)
        settle()
        compose.onNodeWithContentDescription("Settings").assertIsFocused()
        compose.onNodeWithText("TONIGHT'S PICK").assertExists()
    }

    @Test
    fun homeDropsTheSheetAndLandsOnPlay() {
        show()
        focus = FocusRequest(HomeFocus.SETTINGS, serial = 1)
        settle()
        focus = FocusRequest(HomeFocus.PLAY, serial = 2)
        settle()
        compose.onNodeWithText("TONIGHT'S PICK").assertDoesNotExist()
        compose.onNodeWithText("Play S2E9").assertIsFocused()
    }

    @Test
    fun theSideRailsHomeDropsTheSheet() {
        show(SampleHomeState.state(homeLayout = HomeLayout.CALM, sideRail = true))
        focus = FocusRequest(HomeFocus.SETTINGS, serial = 1)
        settle()
        compose.onNodeWithText("TONIGHT'S PICK").assertExists()
        compose.onNodeWithContentDescription("Home").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        compose.onNodeWithText("TONIGHT'S PICK").assertDoesNotExist()
        compose.onNodeWithText("Play S2E9").assertIsFocused()
    }

    @Test
    fun beforeTheLibraryItsTheDashboard() {
        show(SampleHomeState.state(sample = false, plex = SampleHomeState.plexCode, homeLayout = HomeLayout.CALM))
        compose.onNodeWithText("plex.tv/link", substring = true).assertExists()
    }
}
