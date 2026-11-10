package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeRowsState
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Live and Continue in your apps (L8, L9) on the sample home, at the Shield's 1080p TV
 * configuration, and at 90 and 115%. Banners and art don't load here, so the cards show
 * their names and generated art. Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class OtherAppsRowsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeUiState = SampleHomeState.state(), rows: HomeRowsState = SampleHomeRows.rows) = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            CompositionLocalProvider(LocalAppBadges provides SampleHomeRows.badges) {
                HomeScreen(state, HomeActions.None, rows = rows)
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private fun card(description: String) = compose.onNodeWithContentDescription(description, substring = true)

    @Test
    fun rowsBelowTheDashboard() {
        show()
        capture("home_rows_peek")
    }

    @Test
    fun channelFocused() {
        show()
        card("FOX Sports 1").requestFocus()
        capture("home_rows_channel_focused")
    }

    @Test
    fun continueInAppsFocused() {
        show()
        card("The Boys").requestFocus()
        capture("home_rows_continue_focused")
    }

    /** The rows line up with Up next above them, cards the apps row's size (G20 C). */
    @Test
    fun continueInAppsFocusedScale90() {
        show(SampleHomeState.state(layoutScale = 0.9f))
        card("The Boys").requestFocus()
        capture("home_rows_scale_90")
    }

    @Test
    fun continueInAppsFocusedScale115() {
        show(SampleHomeState.state(layoutScale = 1.15f))
        card("The Boys").requestFocus()
        capture("home_rows_scale_115")
    }

    @Test
    fun continueInAppsAtLateNight() {
        show(SampleHomeState.state(time = SampleHomeState.lateNight))
        card("Bluey").requestFocus()
        capture("home_rows_continue_late_night")
    }

    @Test
    fun continueInAppsWithoutChannels() {
        show(rows = SampleHomeRows.rows.copy(channels = emptyList()))
        card("Bluey").requestFocus()
        capture("home_rows_continue_only")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun quickActionsOnAContinueCard() {
        show()
        card("Moana 2").requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        capture("home_rows_continue_quick_actions")
    }
}
