package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensCursor
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.apps.AllAppsScreen
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.rows.SampleHomeRows
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The kids' home (K7, U13) for Maya, 9, in coral, at the Shield's 1080p TV configuration:
 * a weeknight evening ("Remember when") and a Saturday morning (For the family), each at 90,
 * 100 and 115%; her lens card (R1) on Classic, Halloween and Comfort rewatch (90, 100 and
 * 115%), and its quick actions; then her kid-safe Live row (K8), and with a Back-to card
 * first (P4, at 100 and 115%), My apps' All apps focused, and All apps as My apps (K9).
 * Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class KidHomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** YouTube TV's networks and Continue in your apps are on offer: a kid's home leaves them out. */
    private fun show(state: HomeUiState) = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            HomeScreen(state, HomeActions.None, rows = SampleHomeRows.rows, guide = SampleGuide.withKidChannels(state.nowMillis))
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun weeknight() {
        show(SampleHomeState.kid())
        capture("home_kid_weeknight")
    }

    @Test
    fun weeknightScale90() {
        show(SampleHomeState.kid(layoutScale = 0.9f))
        capture("home_kid_weeknight_scale_90")
    }

    @Test
    fun weeknightScale115() {
        show(SampleHomeState.kid(layoutScale = 1.15f))
        capture("home_kid_weeknight_scale_115")
    }

    @Test
    fun weekendMorning() {
        show(SampleHomeState.kid(SampleHomeState.saturdayMorning))
        capture("home_kid_weekend")
    }

    @Test
    fun weekendMorningScale90() {
        show(SampleHomeState.kid(SampleHomeState.saturdayMorning, layoutScale = 0.9f))
        capture("home_kid_weekend_scale_90")
    }

    @Test
    fun weekendMorningScale115() {
        show(SampleHomeState.kid(SampleHomeState.saturdayMorning, layoutScale = 1.15f))
        capture("home_kid_weekend_scale_115")
    }

    /** Her lens card (R1) on another lens, as Another lens leaves it on a weeknight. */
    private fun onLens(lens: Lens, id: String, layoutScale: Float = 1f) =
        SampleHomeState.kid(layoutScale = layoutScale, cursor = LensCursor(lead = Lens.THROWBACK, lens = lens, id = id))

    @Test
    fun lensClassic() {
        show(onLens(Lens.CLASSIC, "irongiant"))
        capture("home_kid_lens_classic")
    }

    @Test
    fun lensSeasonal() {
        show(onLens(Lens.SEASONAL, "hoteltransylvania"))
        capture("home_kid_lens_halloween")
    }

    @Test
    fun lensComfort() {
        show(onLens(Lens.COMFORT, "moana"))
        capture("home_kid_lens_comfort")
    }

    @Test
    fun lensComfortScale90() {
        show(onLens(Lens.COMFORT, "moana", layoutScale = 0.9f))
        capture("home_kid_lens_comfort_scale_90")
    }

    @Test
    fun lensComfortScale115() {
        show(onLens(Lens.COMFORT, "moana", layoutScale = 1.15f))
        capture("home_kid_lens_comfort_scale_115")
    }

    /** Menu on "Remember when"'s Watch again: your lens card's quick actions. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun lensQuickActions() {
        show(SampleHomeState.kid())
        compose.onNodeWithText("Watch again").requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
        capture("home_kid_lens_quick_actions")
    }

    /** Down twice from Play: Up Next, then the Live row with Family and Cartoon Club alone. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun liveRow() {
        show(SampleHomeState.kid())
        repeat(2) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        capture("home_kid_live_row")
    }

    /**
     * Down twice from Play with a spot saved on Cartoon Club (P4): its Back-to card first in
     * the Live row, focused, then Family and Cartoon Club.
     */
    @Test
    fun liveRowBackTo() = liveRowBackTo(1f, "home_kid_live_row_back_to")

    @Test
    fun liveRowBackToScale115() = liveRowBackTo(1.15f, "home_kid_live_row_back_to_scale_115")

    @OptIn(ExperimentalTestApi::class)
    private fun liveRowBackTo(layoutScale: Float, name: String) {
        val maya = SampleHomeState.kid(layoutScale = layoutScale)
        val guide = SampleGuide.withKidChannels(maya.nowMillis)
        val tidePool = guide.channels.single { it.id == "cartoons" }.slotAt(maya.nowMillis)!!
        val spot = SavedSpot("cartoons", tidePool.startMillis, tidePool.itemKey!!, 6 * 60_000L + 30_000, maya.nowMillis - 4 * 60_000L)
        compose.setContent {
            ActionCenterTheme(accent = maya.accent, layoutScale = maya.layoutScale) {
                HomeScreen(maya, HomeActions.None, rows = SampleHomeRows.rows, guide = guide, spot = spot)
            }
        }
        repeat(2) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }
        capture(name)
    }

    @Test
    fun myAppsAllAppsFocused() {
        show(SampleHomeState.kid())
        compose.onNodeWithText("All apps").requestFocus()
        capture("home_kid_my_apps")
    }

    /** All apps from there (K9): My apps, her five, A–Z. */
    @Test
    fun allAppsAsMyApps() {
        val maya = SampleHomeState.kid()
        compose.setContent {
            ActionCenterTheme(accent = maya.accent) {
                AllAppsScreen(maya.allApps, maya.mostUsed, maya.hiddenApps, HomeActions.None, onBack = {}, allowlist = maya.profile.apps)
            }
        }
        capture("all_apps_kid")
    }
}
