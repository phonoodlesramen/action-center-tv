package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Time & modes beyond its resting state (`settings_time`): a change refused, and at 115%. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class TimeModesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeUiState = SampleHomeState.state()) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(state, SettingsActions.None, onBack = {}, initialSection = SettingsSection.TIME, version = "0.1.0")
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun refused() {
        show()
        // Weekend evening to 10:45 PM would run into late night, which starts at 10:30.
        compose.onNodeWithContentDescription("Weekend evening end later").performSemanticsAction(SemanticsActions.OnClick)
        capture("settings_time_refused")
    }

    @Test
    fun largest() {
        show(SampleHomeState.state(layoutScale = 1.15f))
        capture("settings_time_scale_115")
    }
}
