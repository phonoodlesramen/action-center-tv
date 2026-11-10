package com.phonoodlesramen.actioncenter.ui.diagnostics

import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeState
import com.phonoodlesramen.actioncenter.safety.CrashRecord
import com.phonoodlesramen.actioncenter.safety.StartupMode
import com.phonoodlesramen.actioncenter.system.PackageState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots at the Shield's 1080p TV configuration. Record with
 * `.\gradlew.bat recordRoborazziDebug`, check with `verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class DiagnosticsScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HomeState) =
        compose.setContent { DiagnosticsScreen(state, SampleDiagnosticsState.now, DiagnosticsActions.None) }

    private fun capture(name: String) =
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun normal() {
        show(SampleDiagnosticsState.state)
        capture("diagnostics_normal")
    }

    @Test
    fun safeMode() {
        show(
            SampleDiagnosticsState.state.copy(
                mode = StartupMode.SAFE,
                lastCrash = CrashRecord(
                    timeMillis = SampleDiagnosticsState.now.minusMinutes(3).toInstant().toEpochMilli(),
                    summary = "IllegalStateException: Test crash from the device panel",
                ),
            ),
        )
        capture("diagnostics_safe_mode")
    }

    @Test
    fun stockLauncherBack() {
        show(SampleDiagnosticsState.state.copy(stockLauncher = PackageState.ENABLED))
        capture("diagnostics_stock_launcher_back")
    }

    @Test
    fun appTileFocused() {
        show(SampleDiagnosticsState.state)
        compose.onNodeWithText("Plezy").requestFocus()
        capture("diagnostics_app_tile_focused")
    }

    @Test
    fun deviceCheck() {
        show(SampleDiagnosticsState.state)
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("device-title")
        compose.onNodeWithText("This Shield").requestFocus()
        capture("diagnostics_device_check")
    }
}
