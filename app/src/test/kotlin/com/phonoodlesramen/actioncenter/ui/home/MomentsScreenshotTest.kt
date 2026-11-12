package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.nfl.SampleNfl
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import java.time.LocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Moments (spec §4, Home layouts, H3) at the Shield's 1080p: the dashboard's Row A in each
 * moment, at 90, 100 and 115%, the chip's quick actions, and Marquee's strip led by the moment.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class MomentsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun moment(time: LocalDateTime, layoutScale: Float = 1f, layout: HomeLayout = HomeLayout.DASHBOARD) =
        SampleHomeState.state(time = time, layoutScale = layoutScale, homeLayout = layout, moments = true, profiles = SampleHomeState.family)

    /** The sample guide's schedule runs from the state's own time, so its card has something on. */
    private fun show(state: HomeUiState, nfl: NflView = NflView.None) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(
                    state,
                    HomeActions.None,
                    guide = SampleGuide.ready(state.nowMillis),
                    favorites = SampleHomeState.favorites,
                    nfl = nfl,
                    watch = SampleNfl.watch,
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun showGameDay(layoutScale: Float = 1f, layout: HomeLayout = HomeLayout.DASHBOARD) =
        show(moment(SampleNfl.sundayNight, layoutScale, layout), nfl = SampleNfl.view())

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun weeknight() {
        show(moment(SampleHomeState.weeknight))
        capture("home_moments_weeknight")
    }

    @Test
    fun weeknightAtSmallestScale() {
        show(moment(SampleHomeState.weeknight, LayoutScale.MIN))
        capture("home_moments_weeknight_scale_90")
    }

    @Test
    fun weekendMorning() {
        show(moment(SampleHomeState.saturdayMorning))
        capture("home_moments_weekend_morning")
    }

    @Test
    fun weekendMorningAtLargestScale() {
        show(moment(SampleHomeState.saturdayMorning, LayoutScale.MAX))
        capture("home_moments_weekend_morning_scale_115")
    }

    @Test
    fun gameDay() {
        showGameDay()
        capture("home_moments_game_day")
    }

    @Test
    fun gameDayAtLargestScale() {
        showGameDay(LayoutScale.MAX)
        capture("home_moments_game_day_scale_115")
    }

    @Test
    fun lateNight() {
        show(moment(SampleHomeState.lateNight))
        capture("home_moments_late_night")
    }

    @Test
    fun lateNightAtLargestScale() {
        show(moment(SampleHomeState.lateNight, LayoutScale.MAX))
        capture("home_moments_late_night_scale_115")
    }

    @Test
    fun chipOffersTheUsualLayout() {
        show(moment(SampleHomeState.weeknight))
        compose.onNodeWithText("Weeknight evening").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        capture("home_moments_chip")
    }

    @Test
    fun marqueeOnGameDay() {
        showGameDay(layout = HomeLayout.MARQUEE)
        capture("home_marquee_moments_game_day")
    }

    @Test
    fun marqueeOnAWeekendMorning() {
        show(moment(SampleHomeState.saturdayMorning, layout = HomeLayout.MARQUEE))
        capture("home_marquee_moments_weekend_morning")
    }
}
