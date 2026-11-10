package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensCursor
import com.phonoodlesramen.actioncenter.content.MoreLike
import com.phonoodlesramen.actioncenter.content.SampleContentSource
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.home.PickState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The home screen in every adult state of spec §5, at the Shield's 1080p TV
 * configuration. Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class HomeScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeUiState) = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            HomeScreen(state, HomeActions.None)
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun firstRun() {
        show(SampleHomeState.state(sample = false, plex = SampleHomeState.plexCode))
        capture("home_first_run")
    }

    @Test
    fun plexLibraryOffline() {
        val offline = SampleHomeState.plexLinked.copy(offline = true, lastSyncMillis = SampleHomeState.weeknight.toEpochSecond(java.time.ZoneOffset.UTC) * 1_000 - 2 * 3_600_000)
        show(SampleHomeState.state(sample = false, plex = offline, plexSnapshot = SampleHomeState.plexLibrary()))
        capture("home_plex_library_offline")
    }

    @Test
    fun engineRan() {
        show(SampleHomeState.state(sample = false, plex = SampleHomeState.plexLinked, plexSnapshot = SampleHomeState.engineLibrary()))
        capture("home_engine")
    }

    @Test
    fun moreLikeThis() {
        show(
            SampleHomeState.state(
                sample = false,
                plex = SampleHomeState.plexLinked,
                plexSnapshot = SampleHomeState.engineLibrary(),
                pickState = PickState(shownId = "arrival"),
                cursor = LensCursor(lead = Lens.THROWBACK, moreLike = MoreLike("arrival", "Arrival")),
            ),
        )
        capture("home_more_like_this")
    }

    @Test
    fun plexLinkedBeforeTheLibrary() {
        show(SampleHomeState.state(sample = false, plex = SampleHomeState.plexLinked))
        capture("home_plex_linked")
    }

    @Test
    fun sampleContentWithPlayFocused() {
        show(SampleHomeState.state())
        capture("home_sample")
    }

    @Test
    fun lateNight() {
        show(SampleHomeState.state(time = SampleHomeState.lateNight))
        capture("home_late_night")
    }

    @Test
    fun familyLensOnWeekendMornings() {
        show(SampleHomeState.state(time = SampleHomeState.saturdayMorning))
        capture("home_lens_family")
    }

    @Test
    fun seasonalLensOnFridayEvenings() {
        show(SampleHomeState.state(time = SampleHomeState.fridayEvening))
        compose.onNodeWithText("Watch again").requestFocus()
        capture("home_lens_seasonal")
    }

    /** Phase 14 (L3): Up from Watch, Back's circle fills with the lens's colour. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun lensBackFocused() {
        show(SampleHomeState.state())
        // As from the remote: coming into the card lands on Watch, and Up goes to Back.
        compose.onNodeWithText("Watch again").requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        capture("home_lens_back_focused")
    }

    /** At 115% the line gives way; Back still fits in the eyebrow's row. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun lensBackAtLargestScale() {
        show(SampleHomeState.state(layoutScale = LayoutScale.MAX))
        compose.onNodeWithText("Watch again").requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        capture("home_lens_back_scale_115")
    }

    @Test
    fun stockLauncherBack() {
        show(SampleHomeState.state(stockLauncherBack = true))
        capture("home_stock_launcher_back")
    }

    @Test
    fun notTonightOffersUndo() {
        val picks = SampleContentSource.content().picks
        show(SampleHomeState.state(pickState = PickState().notTonight(picks, nowMillis = 0)))
        compose.onNodeWithText("Undo").requestFocus()
        capture("home_undo")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun quickActions() {
        show(SampleHomeState.state())
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        capture("home_quick_actions")
    }

    @Test
    fun upNextFocused() {
        show(SampleHomeState.state())
        compose.onNodeWithContentDescription("Shōgun", substring = true).requestFocus()
        capture("home_up_next_focused")
    }

    /** Down from Up Next: the home scrolls so the apps row is whole, Row A at the top edge. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun appsRowFocused() {
        show(SampleHomeState.state())
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        capture("home_apps_row_focused")
    }

    @Test
    fun appsRowFocusedScale90() {
        show(SampleHomeState.state(layoutScale = 0.9f))
        compose.onNodeWithText("YouTube").requestFocus()
        capture("home_apps_row_scale_90")
    }

    @Test
    fun appsRowFocusedScale115() {
        show(SampleHomeState.state(layoutScale = 1.15f))
        compose.onNodeWithText("YouTube").requestFocus()
        capture("home_apps_row_scale_115")
    }

    /** Right along the row to its end: the row slides one card at a time, All apps last. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun allAppsCardFocused() {
        show(SampleHomeState.state())
        compose.onNodeWithText("YouTube").requestFocus()
        compose.onRoot().performKeyInput { repeat(8) { pressKey(Key.DirectionRight) } }
        compose.waitForIdle()
        capture("home_all_apps_card_focused")
    }

    @Test
    fun guideFocused() {
        show(SampleHomeState.state())
        compose.onNodeWithText("Your library as TV channels").requestFocus()
        capture("home_guide_focused")
    }

    @Test
    fun scale90() {
        show(SampleHomeState.state(layoutScale = 0.9f))
        capture("home_scale_90")
    }

    @Test
    fun scale115() {
        show(SampleHomeState.state(layoutScale = 1.15f))
        capture("home_scale_115")
    }

    @Test
    fun plexUpdating() {
        show(SampleHomeState.state(plex = SampleHomeState.plexLinked.copy(updating = true)))
        compose.onNodeWithText("Updating").assertExists()
        capture("home_plex_updating")
    }

    @Test
    fun plexNotUpdating() {
        show(SampleHomeState.state(plex = SampleHomeState.plexLinked))
        compose.onNodeWithText("Updating").assertDoesNotExist()
    }

    /** The fullest top bar: a weekend morning's kids' avatars, offline, at 115%; the greeting gives way. */
    @Test
    fun plexUpdatingWithTheKidsScale115() {
        val plex = SampleHomeState.plexLinked.copy(updating = true, offline = true)
        show(
            SampleHomeState.state(time = SampleHomeState.saturdayMorning, layoutScale = 1.15f, plex = plex)
                .copy(profiles = SampleHomeState.kid().profiles),
        )
        compose.onNodeWithText("Updating").assertExists()
        capture("home_plex_updating_kids_scale_115")
    }

    @Test
    fun firstRunScale115() {
        show(SampleHomeState.state(sample = false, layoutScale = 1.15f, plex = SampleHomeState.plexCode))
        capture("home_first_run_scale_115")
    }
}
