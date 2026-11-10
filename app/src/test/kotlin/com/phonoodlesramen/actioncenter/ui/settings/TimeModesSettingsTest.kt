package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.context.Hours
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings › Time & modes with the D-pad (M1, M4): − and + move a cutoff or a mode's start
 * and end a quarter of an hour, and a change that would clash is refused with its sentence.
 * Clicks go through semantics: tv-material ignores touch.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class TimeModesSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private val changes = mutableListOf<Pair<Any, Any>>()

    private val actions = object : SettingsActions {
        override fun setQuickWatchCutoff(cutoff: LocalTime) {
            changes += "weeknights" to cutoff
        }

        override fun setWeekendCutoff(cutoff: LocalTime) {
            changes += "weekends" to cutoff
        }

        override fun setModeHours(mode: ContextMode, hours: Hours) {
            changes += mode to hours
        }
    }

    private fun show() {
        val state = SampleHomeState.state()
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                SettingsScreen(state, actions, onBack = {}, initialSection = SettingsSection.TIME)
            }
        }
    }

    private fun button(description: String) = compose.onNodeWithContentDescription(description)

    private fun click(description: String) {
        button(description).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun time(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    @Test
    fun `minus and plus move a cutoff or a mode's start or end a quarter of an hour`() {
        show()

        click("Weeknights cutoff later")
        click("Friday and Saturday cutoff earlier")
        click("Weeknight evening start earlier")
        click("Late night start later")

        assertEquals(
            listOf(
                "weeknights" to time(22, 45),
                "weekends" to time(23, 15),
                ContextMode.WEEKNIGHT_EVENING to Hours(time(17, 45), time(22, 30)),
                ContextMode.LATE_NIGHT to Hours(time(22, 45), time(5)),
            ),
            changes,
        )
    }

    @Test
    fun `a change that would clash is refused with its sentence, until the next change goes through`() {
        show()

        click("Weekend evening end later")

        assertEquals(emptyList<Pair<Any, Any>>(), changes)
        compose.onNodeWithText("Weekend evening would overlap Late night.").assertIsDisplayed()

        click("Weeknight evening start earlier")

        assertEquals(listOf<Pair<Any, Any>>(ContextMode.WEEKNIGHT_EVENING to Hours(time(17, 45), time(22, 30))), changes)
        compose.onNodeWithText("Weekend evening would overlap Late night.").assertDoesNotExist()
    }

    @Test
    fun `the D-pad reaches every button, and Daytime shows below the last`() {
        show()
        button("Weeknights cutoff earlier").requestFocus()
        compose.waitForIdle()

        press(Key.DirectionDown)
        button("Friday and Saturday cutoff earlier").assertIsFocused()
        press(Key.DirectionDown)
        button("Weeknight evening start earlier").assertIsFocused()
        press(Key.DirectionRight, times = 3)
        button("Weeknight evening end later").assertIsFocused()
        press(Key.DirectionDown)
        button("Weekend evening end later").assertIsFocused()
        press(Key.DirectionDown)
        button("Weekend morning end later").assertIsFocused()
        press(Key.DirectionDown)
        button("Late night end later").assertIsFocused()
        compose.onNodeWithText("Any other time").assertIsDisplayed()
        press(Key.DirectionUp)
        button("Weekend morning end later").assertIsFocused()
    }
}
