package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Plex Guide on the sample weeknight (UTC) with a made-up lineup of 18 channels,
 * each with its Ratings (R2): at 90/100/115%, a channel's Ratings focused, a channel being
 * moved, the remake's confirm step, a change turned down, and no channels yet. Record with
 * `.\gradlew.bat recordRoborazziDebug`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class PlexGuideSettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val zone = TimeZone.getDefault()

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restore() = TimeZone.setDefault(zone)

    private fun show(editor: GuideEditor? = FakeGuideEditor(), layoutScale: Float = 1f) {
        val home = SampleHomeState.state()
        compose.setContent {
            ActionCenterTheme(accent = home.accent, layoutScale = layoutScale) {
                SettingsScreen(home, SettingsActions.None, onBack = {}, initialSection = SettingsSection.PLEX_GUIDE, guideEditor = editor)
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun lineup() {
        show()
        capture("settings_plex_guide")
    }

    @Test
    fun lineupScale90() {
        show(layoutScale = 0.9f)
        capture("settings_plex_guide_scale_90")
    }

    @Test
    fun lineupScale115() {
        show(layoutScale = 1.15f)
        capture("settings_plex_guide_scale_115")
    }

    @Test
    fun movingAChannel() {
        show()
        compose.onNodeWithText("Comedy").requestFocus()
        press(Key.Menu)
        press(Key.DirectionDown, times = 2)
        capture("settings_plex_guide_moving")
    }

    @Test
    fun channelRatings() {
        show()
        // Drama's ratings were set on the phone and are no bracket's: Custom.
        compose.onNodeWithText("Drama").requestFocus()
        press(Key.DirectionRight)
        capture("settings_plex_guide_ratings")
    }

    @Test
    fun remakeConfirm() {
        show()
        compose.onNodeWithText("Remake the starter lineup").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        capture("settings_plex_guide_remake")
    }

    @Test
    fun changeTurnedDown() {
        val editor = FakeGuideEditor().apply { reject = "Channel 12 is taken by Classics." }
        show(editor)
        compose.onNodeWithText("Comedy").requestFocus()
        press(Key.DirectionCenter)
        capture("settings_plex_guide_turned_down")
    }

    @Test
    fun noChannelsYet() {
        show(FakeGuideEditor(emptyList()))
        capture("settings_plex_guide_empty")
    }
}
