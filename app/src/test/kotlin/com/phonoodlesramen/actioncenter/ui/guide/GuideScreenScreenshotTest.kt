package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The full guide at the Shield's 1080p TV configuration, from the sample lineup (UTC):
 * now on the weeknight, later today, tomorrow morning and late night, at 90/100/115%, a
 * program's quick actions, the three waits before there are channels (G20 B), screen
 * care, and favorites first (Phase 13). Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class GuideScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: GuideCardState = SampleGuide.ready(),
        now: Long = SampleGuide.WEEKNIGHT,
        layoutScale: Float = 1f,
        lateNight: Boolean = false,
        openAt: Long? = null,
        care: GuideScreenCare = GuideScreenCare(),
        favorites: List<String> = emptyList(),
    ) = compose.setContent {
        val accent = if (lateNight) Palette.lateNight(Palette.DefaultAccent) else Palette.DefaultAccent
        ActionCenterTheme(accent = accent, layoutScale = layoutScale) {
            GuideScreen(
                state = state,
                nowMillis = now,
                actions = HomeActions.None,
                onBack = {},
                zone = ZoneOffset.UTC,
                openAtMillis = openAt,
                lateNight = lateNight,
                care = care,
                favorites = favorites,
            )
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private fun LocalDateTime.millis() = toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun now() {
        show()
        capture("guide_now")
    }

    /** Phase 13: Comedy and TV Mix first, starred, a line under TV Mix, then the rest by number; Down to Movies. */
    @Test
    fun favorites() {
        show(favorites = SampleHomeState.favorites)
        press(Key.DirectionDown, times = 2)
        capture("guide_favorites")
    }

    @Test
    fun nowScale90() {
        show(layoutScale = 0.9f)
        capture("guide_now_scale_90")
    }

    @Test
    fun nowScale115() {
        show(layoutScale = 1.15f)
        capture("guide_now_scale_115")
    }

    /** Right to the 11:00 film: the info panel says how long until it starts, and that OK has its details. */
    @Test
    fun laterToday() {
        show()
        press(Key.DirectionRight, times = 2)
        capture("guide_later_today")
    }

    @Test
    fun laterTodayScale115() {
        show(layoutScale = 1.15f)
        press(Key.DirectionRight, times = 2)
        capture("guide_later_today_scale_115")
    }

    /** Opened on tomorrow morning, TV Mix focused. */
    @Test
    fun tomorrowMorning() {
        show(openAt = SampleHomeState.weeknight.plusDays(1).withHour(7).withMinute(0).millis())
        press(Key.DirectionDown, times = 2)
        capture("guide_tomorrow_morning")
    }

    /** 11:48 PM on the evening's schedule: the window crosses midnight, Classics' 12:30 film is off air. */
    @Test
    fun lateNight() {
        show(now = SampleHomeState.lateNight.millis(), lateNight = true)
        press(Key.DirectionDown, times = 3)
        press(Key.DirectionRight, times = 2)
        capture("guide_late_night")
    }

    /** Menu on the film on now: Watch, Start over, Details and Open in Plezy. */
    @Test
    fun quickActions() {
        show()
        press(Key.Menu)
        capture("guide_quick_actions")
    }

    @Test
    fun quickActionsScale115() {
        show(layoutScale = 1.15f)
        press(Key.Menu)
        capture("guide_quick_actions_scale_115")
    }

    /** Menu on Ridge Line Marathon at 115%: "Add Ridge Line Marathon to favorites" takes two lines, and the row grows. */
    @Test
    fun quickActionsLongFavorite() {
        show(layoutScale = 1.15f)
        press(Key.DirectionDown, times = 5)
        press(Key.Menu)
        capture("guide_quick_actions_long_favorite")
    }

    @Test
    fun notSetUp() {
        show(state = GuideCardState.NotSetUp)
        capture("guide_not_set_up")
    }

    @Test
    fun building() {
        show(state = GuideCardState.Building)
        capture("guide_building")
    }

    @Test
    fun nothingToFill() {
        show(state = GuideCardState.NothingToFill)
        capture("guide_nothing_to_fill")
    }

    /** Screen care (G10): dimmed and drifted for the minute. */
    @Test
    fun screenCare() {
        val care = GuideScreenCare()
        show(care = care)
        compose.runOnUiThread { care.show() }
        compose.waitForIdle()
        capture("guide_screen_care")
    }
}
