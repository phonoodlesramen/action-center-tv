package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Marquee layout (spec §4, Marquee) at the Shield's 1080p, at 90, 100 and 115%: focus on
 * the strip with the top bar quiet, resting on other cards, the title's buttons, and late night.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class MarqueeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun marquee(time: java.time.LocalDateTime = SampleHomeState.weeknight, layoutScale: Float = 1f) =
        SampleHomeState.state(time = time, layoutScale = layoutScale, homeLayout = HomeLayout.MARQUEE)

    private fun show(state: HomeUiState = marquee()) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, HomeActions.None, guide = SampleGuide.ready(), favorites = SampleHomeState.favorites)
            }
        }
        settle()
    }

    /** Past the rest before a backdrop comes in, its crossfade and the top bar's fade. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun startsOnTheStrip() {
        show()
        capture("home_marquee")
    }

    @Test
    fun atSmallestScale() {
        show(marquee(layoutScale = LayoutScale.MIN))
        capture("home_marquee_scale_90")
    }

    @Test
    fun atLargestScale() {
        show(marquee(layoutScale = LayoutScale.MAX))
        capture("home_marquee_scale_115")
    }

    @Test
    fun restsOnTheLens() {
        show()
        press(Key.DirectionRight)
        capture("home_marquee_lens")
    }

    @Test
    fun restsOnLive() {
        show()
        press(Key.DirectionRight, times = 4)
        capture("home_marquee_live")
    }

    @Test
    fun liveAtLargestScale() {
        show(marquee(layoutScale = LayoutScale.MAX))
        press(Key.DirectionRight, times = 4)
        capture("home_marquee_live_scale_115")
    }

    @Test
    fun upReachesTheButtons() {
        show()
        press(Key.DirectionUp)
        capture("home_marquee_buttons")
    }

    @Test
    fun buttonsAtLargestScale() {
        show(marquee(layoutScale = LayoutScale.MAX))
        press(Key.DirectionUp)
        capture("home_marquee_buttons_scale_115")
    }

    @Test
    fun buttonsAtSmallestScale() {
        show(marquee(layoutScale = LayoutScale.MIN))
        press(Key.DirectionUp)
        capture("home_marquee_buttons_scale_90")
    }

    @Test
    fun lateNight() {
        show(marquee(time = SampleHomeState.lateNight))
        capture("home_marquee_late_night")
    }

    @Test
    fun downReachesTheRows() {
        show()
        press(Key.DirectionDown)
        capture("home_marquee_rows")
    }
}
