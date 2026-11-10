package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.setup.HomeRoute
import com.phonoodlesramen.actioncenter.setup.LocalSetup
import com.phonoodlesramen.actioncenter.setup.SetupStatus
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

/** Setup without a computer: Settings › Setup on a new TV, a SHIELD that's done, and HomeWatch turned off; and the top bar's chip. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class SetupScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val newTv = SetupStatus(home = HomeRoute.OFF, tvListings = false, pictures = false, usageAccess = false)

    private fun settings(setup: SetupStatus, state: HomeUiState = SampleHomeState.state(sample = false, plex = SampleHomeState.plexCode)) =
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(state, SettingsActions.None, onBack = {}, initialSection = SettingsSection.SETUP, setup = setup)
            }
        }

    private fun home(setup: SetupStatus, state: HomeUiState = SampleHomeState.state()) = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            CompositionLocalProvider(LocalSetup provides setup) { HomeScreen(state, HomeActions.None) }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun newGoogleTv() {
        settings(newTv)
        capture("settings_setup")
    }

    @Test
    fun shieldWithoutAnAccessibilityLink() {
        settings(newTv.copy(accessibilityLink = false, tvListings = true))
        capture("settings_setup_no_link")
    }

    @Test
    fun allDone() {
        settings(SetupStatus(), SampleHomeState.state(sample = false, plex = SampleHomeState.plexLinked))
        capture("settings_setup_done")
    }

    @Test
    fun homeWatchTurnedOff() {
        settings(SetupStatus(home = HomeRoute.WENT_OFF), SampleHomeState.state(sample = false, plex = SampleHomeState.plexLinked))
        capture("settings_setup_went_off")
    }

    @Test
    fun finishSetupChip() {
        home(newTv.copy(home = HomeRoute.HOMEWATCH))
        capture("home_setup_chip")
    }

    @Test
    fun homeButtonOffChip() {
        home(SetupStatus(home = HomeRoute.WENT_OFF))
        capture("home_setup_home_button_off")
    }
}
