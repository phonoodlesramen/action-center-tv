package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.ai.AiBlurbsController
import com.phonoodlesramen.actioncenter.ai.AiBlurbsStatus
import com.phonoodlesramen.actioncenter.ai.ClaudeBlurbs
import com.phonoodlesramen.actioncenter.guide.editor.EditorServer.State
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › AI blurbs (M7-M11) and Add a key on your phone (M10) at the Shield's 1080p TV
 * configuration: off, on with a key and a last run, a failed run, and the key screen
 * waiting and saved. Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class AiBlurbsSettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun controller(status: AiBlurbsStatus) = object : AiBlurbsController {
        override val status: Flow<AiBlurbsStatus> = flowOf(status)
    }

    private fun show(state: HomeUiState, controller: AiBlurbsController) = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            SettingsScreen(state, SettingsActions.None, onBack = {}, initialSection = SettingsSection.AI, aiBlurbs = controller)
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun off() {
        show(SampleHomeState.state(), AiBlurbsController.None)
        capture("settings_ai_off")
    }

    @Test
    fun onWithAKeyAndALastRun() {
        show(
            SampleHomeState.state().copy(aiBlurbs = true),
            controller(AiBlurbsStatus(keyEnding = "x7Qa", lastRun = "Last night at 4:02 AM: 28 blurbs, 7,530 tokens, about $0.002")),
        )
        capture("settings_ai_on")
    }

    @Test
    fun aFailedRun() {
        show(
            SampleHomeState.state().copy(aiBlurbs = true),
            controller(AiBlurbsStatus(keyEnding = "x7Qa", lastRun = "Last night at 4:02 AM: ${ClaudeBlurbs.KEY_REFUSED}", failed = true)),
        )
        capture("settings_ai_failed")
    }

    private val waiting = State.Running(
        address = "http://192.168.1.20:8137",
        pin = "123456",
        link = "http://192.168.1.20:8137/?k=sCreEnShOt-k3y_0123456",
        phoneConnected = false,
    )

    @Test
    fun keyScreenWaiting() {
        compose.setContent {
            ActionCenterTheme { AiKeyContent(waiting, savedEnding = null, onStartAgain = {}, onBack = {}) }
        }
        capture("ai_key_waiting")
    }

    @Test
    fun keyScreenSaved() {
        compose.setContent {
            ActionCenterTheme { AiKeyContent(waiting.copy(phoneConnected = true), savedEnding = "x7Qa", onStartAgain = {}, onBack = {}) }
        }
        capture("ai_key_saved")
    }
}
