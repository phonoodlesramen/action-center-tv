package com.phonoodlesramen.actioncenter.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Who's watching?" with the D-pad (K1): focus starts on whoever is on, OK picks, Back closes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV_1080P)
class WhosWatchingScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val everyone = SampleProfiles.everyone()
    private val picked = mutableListOf<ActiveProfile>()
    private var closed = 0

    private fun show(current: String) {
        compose.setContent {
            ActionCenterTheme {
                WhosWatchingScreen(everyone, current, Palette.DefaultAccent, onPick = { picked += it }, onBack = { closed++ })
            }
        }
        compose.waitForIdle()
    }

    private fun person(name: String) = compose.onNode(hasText(name) and hasClickAction())

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `focus starts on whoever is on, and OK picks who's focused`() {
        show(current = "kid-maya")
        person("Maya").assertIsFocused()

        press(Key.DirectionRight)
        person("Leo").assertIsFocused()
        press(Key.DirectionCenter)

        assertEquals(listOf("kid-leo"), picked.map { it.id })
    }

    @Test
    fun `you're focused when you're on, and Back closes it with nobody picked`() {
        show(current = "owner")
        person("Alex").assertIsFocused()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertEquals(1, closed)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun `after the screen was off it opens only when there's a kid`() {
        assertTrue(opensAfterScreenOff(everyone))
        assertFalse(opensAfterScreenOff(listOf(ActiveProfile.owner("Alex"))))
    }
}
