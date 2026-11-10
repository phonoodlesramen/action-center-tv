package com.phonoodlesramen.actioncenter.ui.apps

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** All apps for a kid (K9): My apps, only what a grown-up picked, with no quick actions. Yours is unchanged. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class AllAppsKidTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Maya's home: every app installed, five of them on her list. */
    private val state = SampleHomeState.kid()
    private val launched = mutableListOf<String>()

    private val actions = object : HomeActions {
        override fun launch(app: LaunchableApp) {
            launched += app.label
        }
    }

    private fun show(allowlist: Set<String>?) = compose.setContent {
        ActionCenterTheme(accent = state.accent) {
            AllAppsScreen(state.allApps, state.mostUsed, state.hiddenApps, actions, onBack = {}, allowlist = allowlist)
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The focused banner's name (after its initial, when it has no art). */
    private fun focusedText() = compose.onNode(isFocused()).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty().substringAfterLast(", ")

    @Test
    fun `a kid's All apps is My apps, listing only the allowlist A to Z`() {
        show(state.profile.apps)
        compose.onNodeWithText("My apps").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onNodeWithText("MOST USED").assertDoesNotExist()
        compose.onNodeWithText(KID_NOTE).assertExists()
        for (other in listOf("Netflix", "YouTube", "Kodi", "Plex", "Prime Video", "Spotify", "Twitch")) {
            compose.onNodeWithText(other).assertDoesNotExist()
        }
        val seen = mutableListOf<String>()
        repeat(5) {
            seen += focusedText()
            press(Key.DirectionRight)
        }
        assertEquals(listOf("Disney+", "Minecraft", "PBS KIDS Video", "Plezy", "YouTube Kids"), seen)
    }

    @Test
    fun `a kid's apps open with OK and have no quick actions`() {
        show(state.profile.apps)
        compose.onNodeWithText("Plezy").requestFocus()
        press(Key.Menu)
        compose.onNodeWithText("Hide from launchpad").assertDoesNotExist()
        compose.onNodeWithText("Plezy").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("Plezy"), launched)
    }

    @Test
    fun `yours lists every app, with Most used and quick actions`() {
        show(allowlist = null)
        compose.onNodeWithText("All apps").assertIsDisplayed()
        compose.onNodeWithText("MOST USED").assertIsDisplayed()
        compose.onNodeWithText("${state.allApps.size}").assertIsDisplayed()
        compose.onNodeWithText(KID_NOTE).assertDoesNotExist()
        compose.onNodeWithText("Twitch").requestFocus()
        press(Key.Menu)
        compose.onNodeWithText("Hide from launchpad").assertExists()
    }
}
