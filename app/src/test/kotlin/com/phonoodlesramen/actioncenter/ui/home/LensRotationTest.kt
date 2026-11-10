package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.phonoodlesramen.actioncenter.content.LensCursor
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.content.LensSchedule
import com.phonoodlesramen.actioncenter.home.lensDeck
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 14 (L1, L5): the lens card moves on by itself while you're elsewhere, waits while
 * you're on it or a sheet is open, and counts a title shown for 3 seconds. On the test clock.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class LensRotationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var prefs = LensPrefs()
    private var cursor = LensCursor()
    private var state by mutableStateOf(SampleHomeState.state())
    private val moved = mutableListOf<String>()
    private val shown = mutableListOf<String>()

    /** Next moves the card as the view model would; what's shown is recorded. */
    private val actions = object : HomeActions {
        override fun nextLensPick() {
            cursor = cursor.next(state.lensDeck(), LensSchedule.lead(state.now, state.schedule))
            state = SampleHomeState.state(cursor = cursor, lensPrefs = prefs)
            moved += state.lens!!.pick.title
        }

        override fun lensShown(pick: LensPick) {
            shown += pick.title
        }
    }

    private fun show(lensPrefs: LensPrefs = LensPrefs()) {
        prefs = lensPrefs
        state = SampleHomeState.state(lensPrefs = lensPrefs)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, actions)
            }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    private fun pass(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        pass(100)
    }

    @Test
    fun `it moves on every 30 seconds while you're elsewhere`() {
        show()
        pass(29_000)
        assertEquals(emptyList<String>(), moved)

        pass(1_500)
        assertEquals(1, moved.size)
        pass(30_000)
        assertEquals(2, moved.size)
    }

    @Test
    fun `it waits while you're on the card, and starts the wait over when you leave`() {
        show()
        compose.onNodeWithText("Watch again").requestFocus()
        pass(90_000)
        assertEquals(emptyList<String>(), moved)

        press(Key.DirectionLeft)
        pass(29_000)
        assertEquals(emptyList<String>(), moved)
        pass(1_500)
        assertEquals(1, moved.size)
    }

    @Test
    fun `it waits while a sheet is open`() {
        show()
        press(Key.Menu)
        pass(90_000)

        assertEquals(emptyList<String>(), moved)
    }

    @Test
    fun `the fastest notch moves it every 10 seconds`() {
        show(LensPrefs(speed = 5))
        pass(10_500)
        assertEquals(1, moved.size)
    }

    @Test
    fun `off, it stays put`() {
        show(LensPrefs(rotate = false))
        pass(120_000)
        assertEquals(emptyList<String>(), moved)
    }

    @Test
    fun `a title on screen for 3 seconds counts as shown`() {
        show(LensPrefs(rotate = false))
        pass(2_000)
        assertEquals(emptyList<String>(), shown)

        pass(1_500)
        assertEquals(listOf("Heat"), shown)
    }
}
