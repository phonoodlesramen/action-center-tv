package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.setup.HomeRoute
import com.phonoodlesramen.actioncenter.setup.LocalSetup
import com.phonoodlesramen.actioncenter.setup.SetupStatus
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.update.LocalUpdates
import com.phonoodlesramen.actioncenter.update.UpdateInfo
import com.phonoodlesramen.actioncenter.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The top bar's update chip (A6): in the context chip's place while an update waits, above
 * Finish setup, and OK goes to [HomeActions.openUpdate]. Clicks go through semantics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class UpdateChipTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val info = UpdateInfo(1, 200, "1.1.0", "v1.1.0", "app-release.apk", 53_572_870, "c".repeat(64), "a".repeat(64))
    private var opened = 0
    private val actions = object : HomeActions {
        override fun openUpdate() {
            opened++
        }
    }

    private fun home(updates: UpdateStatus, setup: SetupStatus = SetupStatus(), kid: Boolean = false) {
        val sample = SampleHomeState.state()
        val state = if (kid) SampleHomeState.kid() else sample
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                CompositionLocalProvider(LocalSetup provides setup, LocalUpdates provides updates) {
                    HomeScreen(state, actions)
                }
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun `a downloaded update says Update ready, above Finish setup, and OK goes to it`() {
        home(UpdateStatus(available = info, ready = true), setup = SetupStatus(home = HomeRoute.OFF))
        compose.onNodeWithText("Update ready · 1.1.0").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Finish setup", substring = true).fetchSemanticsNodes().size)
        capture("home_update_ready_chip")
        compose.onNodeWithText("Update ready · 1.1.0").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test
    fun `one not downloaded yet says Update available`() {
        home(UpdateStatus(available = info))
        compose.onNodeWithText("Update available · 1.1.0").assertIsDisplayed()
    }

    @Test
    fun `a kid, or a build that doesn't update, sees no chip`() {
        home(UpdateStatus(available = info), kid = true)
        assertEquals(0, compose.onAllNodesWithText("Update available", substring = true).fetchSemanticsNodes().size)
    }
}
