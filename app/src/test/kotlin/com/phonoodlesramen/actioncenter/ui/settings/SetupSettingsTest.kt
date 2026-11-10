package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.setup.HomeRoute
import com.phonoodlesramen.actioncenter.setup.SetupPermission
import com.phonoodlesramen.actioncenter.setup.SetupStatus
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Setup's steps and buttons (setup without a computer). Clicks go through semantics: tv-material ignores touch. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class SetupSettingsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private val actions = object : SettingsActions {
        override fun allowPermission(permission: SetupPermission) {
            calls += "allow $permission"
        }

        override fun openHomeWatchSettings() {
            calls += "homewatch"
        }

        override fun openUsageAccess() {
            calls += "usage"
        }

        override fun setSetupChipShown(shown: Boolean) {
            calls += "chip $shown"
        }
    }

    private val newTv = SetupStatus(home = HomeRoute.OFF, tvListings = false, pictures = false, usageAccess = false)

    private fun show(setup: SetupStatus, state: HomeUiState = SampleHomeState.state(sample = false, plex = SampleHomeState.plexCode)) =
        compose.setContent {
            ActionCenterTheme {
                SettingsScreen(state, actions, onBack = {}, initialSection = SettingsSection.SETUP, setup = setup)
            }
        }

    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun `on a new TV every step has its button, and each goes where it should`() {
        show(newTv)
        click("Turn on HomeWatch")
        compose.onAllNodesWithText("Allow")[0].performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onAllNodesWithText("Allow")[1].performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        click("Turn on")
        assertEquals(listOf("homewatch", "allow TV_LISTINGS", "allow PICTURES", "usage"), calls)
        compose.onNodeWithText("Open Plex settings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `where TV Settings can't link to Accessibility, Setup says the way`() {
        show(newTv.copy(accessibilityLink = false))
        compose.onNodeWithText("Device Preferences (or System) › Accessibility", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a set-up TV shows no buttons, only what's done`() {
        show(SetupStatus(), SampleHomeState.state(sample = false, plex = SampleHomeState.plexLinked))
        assertEquals(0, count("Allow"))
        assertEquals(0, count("Turn on HomeWatch"))
        assertEquals(0, count("Open Plex settings"))
        compose.onNodeWithText("Home opens Action Center", substring = true).assertIsDisplayed()
    }

    @Test
    fun `HomeWatch turned off by Android says so, and offers it again`() {
        show(newTv.copy(home = HomeRoute.WENT_OFF))
        compose.onNodeWithText("Turned off by Android").assertIsDisplayed()
        click("Turn on HomeWatch")
        assertEquals(listOf("homewatch"), calls)
    }

    @Test
    fun `when a step gets done, focus moves from its button to the next step's`() {
        var setup by mutableStateOf(newTv)
        val state = SampleHomeState.state(sample = false, plex = SampleHomeState.plexCode)
        compose.setContent {
            ActionCenterTheme {
                SettingsScreen(state, actions, onBack = {}, initialSection = SettingsSection.SETUP, setup = setup)
            }
        }
        // Continue in your apps' Allow opens Android's question, and the answer is yes.
        compose.onAllNodesWithText("Allow")[0].performScrollTo().requestFocus()
        setup = setup.copy(tvListings = true)
        compose.waitForIdle()
        // Your pictures' Allow is next (it's the only Allow left).
        compose.onNodeWithText("Allow").assertIsFocused()

        // Turn on HomeWatch, done in TV Settings: the next step still to do, after wrapping round.
        compose.onNodeWithText("Turn on HomeWatch").performScrollTo().requestFocus()
        setup = setup.copy(home = HomeRoute.HOMEWATCH)
        compose.waitForIdle()
        compose.onNodeWithText("Allow").assertIsFocused()
    }

    @Test
    fun `when the last step gets done, focus goes to Show in the top bar`() {
        var setup by mutableStateOf(SetupStatus(usageAccess = false))
        val state = SampleHomeState.state(sample = false, plex = SampleHomeState.plexLinked)
        compose.setContent {
            ActionCenterTheme {
                SettingsScreen(state, actions, onBack = {}, initialSection = SettingsSection.SETUP, setup = setup)
            }
        }
        compose.onNodeWithText("Turn on").performScrollTo().requestFocus()
        setup = setup.copy(usageAccess = true)
        compose.waitForIdle()
        compose.onNodeWithText("Show in the top bar").assertIsFocused()
    }

    @Test
    fun `the next step wraps round, and none is left once all are done`() {
        val allDone = SetupStep.entries.associateWith { true }
        assertEquals(null, SetupFocus.next(SetupStep.USAGE, allDone))
        assertEquals(SetupStep.PICTURES, SetupFocus.next(SetupStep.TV_LISTINGS, allDone + (SetupStep.PICTURES to false)))
        assertEquals(SetupStep.HOME, SetupFocus.next(SetupStep.PLEX, allDone + (SetupStep.HOME to false)))
    }

    @Test
    fun `the top-bar chip can be hidden`() {
        show(newTv)
        click("Show in the top bar")
        assertEquals(listOf("chip false"), calls)
    }

    @Test
    fun `and shown again`() {
        show(newTv.copy(chipHidden = true))
        click("Show in the top bar")
        assertEquals(listOf("chip true"), calls)
    }
}
