package com.phonoodlesramen.actioncenter.ui.apps

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class AllAppsScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeUiState) = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            AllAppsScreen(state.allApps, state.mostUsed, state.hiddenApps, HomeActions.None, onBack = {})
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun allApps() {
        show(SampleHomeState.state())
        capture("all_apps")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun appQuickActions() {
        show(SampleHomeState.state())
        compose.onNodeWithText("Kodi").requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        capture("all_apps_quick_actions")
    }
}
