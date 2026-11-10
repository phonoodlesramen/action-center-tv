package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.content.LensMoveOn
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Lenses (Phase 14, L1-L5) with the D-pad: the speed's notches take focus and focus
 * chooses them, Rotate and Shuffle toggle, and Move on picks one. Clicks go through
 * semantics: tv-material ignores touch.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class LensSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private var prefs by mutableStateOf(LensPrefs())
    private val changes = mutableListOf<String>()

    /** Each change lands in [prefs], as the settings flow would bring it back. */
    private val actions = object : SettingsActions {
        override fun setLensRotate(on: Boolean) {
            changes += "rotate $on"
            prefs = prefs.copy(rotate = on)
        }

        override fun setLensSpeed(notch: Int) {
            changes += "speed $notch"
            prefs = prefs.copy(speed = notch)
        }

        override fun setLensShuffle(on: Boolean) {
            changes += "shuffle $on"
            prefs = prefs.copy(shuffle = on)
        }

        override fun setLensMoveOn(moveOn: LensMoveOn) {
            changes += "move on ${moveOn.name}"
            prefs = prefs.copy(moveOn = moveOn)
        }
    }

    private fun show() {
        compose.setContent {
            val state = SampleHomeState.state(lensPrefs = prefs)
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(state, actions, onBack = {}, initialSection = SettingsSection.LENSES)
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun notch(n: Int) = compose.onNodeWithContentDescription("Speed $n of 5", substring = true)

    @Test
    fun `coming into the speed lands on the chosen notch, and Left and Right change it`() {
        show()
        compose.onNodeWithText("Rotate on its own").requestFocus()
        press(Key.DirectionDown)
        notch(3).assertIsFocused()
        assertEquals(emptyList<String>(), changes)

        press(Key.DirectionRight, times = 2)
        notch(5).assertIsFocused()
        compose.onNodeWithText("Every 10 s").assertExists()
        press(Key.DirectionLeft, times = 4)
        notch(1).assertIsFocused()
        compose.onNodeWithText("Every 1 min").assertExists()
        assertEquals(listOf("speed 4", "speed 5", "speed 4", "speed 3", "speed 2", "speed 1"), changes)
    }

    @Test
    fun `Rotate and Shuffle toggle, the speed goes with Rotate, and Move on picks one`() {
        show()
        click("Rotate on its own")
        compose.onNodeWithContentDescription("Speed 3 of 5", substring = true).assertDoesNotExist()
        click("Shuffle")
        click("After a week")

        assertEquals(listOf("rotate false", "shuffle false", "move on WEEK"), changes)
        compose.onNodeWithText("A title on the card on 7 days", substring = true).assertExists()
        click("Once it's watched")
        compose.onNodeWithText("A title stays on its lens until it's watched", substring = true).assertExists()
    }
}
