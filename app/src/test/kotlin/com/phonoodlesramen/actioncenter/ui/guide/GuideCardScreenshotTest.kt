package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.lastapp.AppVisit
import com.phonoodlesramen.actioncenter.lastapp.TunedChannel
import com.phonoodlesramen.actioncenter.lastapp.TvState
import com.phonoodlesramen.actioncenter.lastapp.YouTubeTv
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.home.rows.LocalAppBadges
import com.phonoodlesramen.actioncenter.ui.home.rows.SampleHomeRows
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The dashboard with the Plex Guide card's lineup (plan Q3), focused, at 90, 100 and
 * 115% and late at night, with the Live row peeking below; then the card's waits with
 * Plex linked (G20 B): the library's sync, the lineup being made, and a library too small
 * to fill a channel. The card before Plex is connected is HomeScreenScreenshotTest's
 * `home_guide_focused`. Then Back to {channel} (P4) at 90, 100 and 115%, and Back to
 * YouTube TV at 100 and 115%, without a channel, and with its quick actions open.
 * Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class GuideCardScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: HomeUiState,
        guide: GuideCardState = SampleGuide.ready(),
        spot: SavedSpot? = null,
        favorites: List<String> = emptyList(),
        tv: TvState = TvState(),
    ) = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            CompositionLocalProvider(LocalAppBadges provides SampleHomeRows.badges) {
                HomeScreen(state, HomeActions.None, rows = SampleHomeRows.rows, guide = guide, spot = spot, favorites = favorites, tv = tv)
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private fun focusCard() = compose.onNodeWithText("Coming up at", substring = true).requestFocus()

    /** Plex linked and the card focused, before there are channels. */
    private fun waiting(guide: GuideCardState, name: String) {
        show(SampleHomeState.state(plex = SampleHomeState.plexLinked), guide)
        compose.onNodeWithText("Your library as TV channels").requestFocus()
        capture(name)
    }

    @Test
    fun notSynced() = waiting(GuideCardState.NotSetUp, "home_guide_not_synced")

    @Test
    fun building() = waiting(GuideCardState.Building, "home_guide_building")

    @Test
    fun nothingToFill() = waiting(GuideCardState.NothingToFill, "home_guide_nothing_to_fill")

    @Test
    fun noneOn() = waiting(GuideCardState.NoneOn, "home_guide_none_on")

    /** Phase 13: Comedy, a favorite, leads Coming up at 9:00 with a star, then Sci-Fi; the Live row has the favorites first. */
    @Test
    fun favorites() {
        show(SampleHomeState.state(), favorites = SampleHomeState.favorites)
        focusCard()
        capture("home_guide_favorites")
    }

    @Test
    fun ready() {
        show(SampleHomeState.state())
        focusCard()
        capture("home_guide_ready")
    }

    @Test
    fun readyScale90() {
        show(SampleHomeState.state(layoutScale = 0.9f))
        focusCard()
        capture("home_guide_ready_scale_90")
    }

    @Test
    fun readyScale115() {
        show(SampleHomeState.state(layoutScale = 1.15f))
        focusCard()
        capture("home_guide_ready_scale_115")
    }

    @Test
    fun readyLateNight() {
        show(SampleHomeState.state(time = SampleHomeState.lateNight))
        focusCard()
        capture("home_guide_ready_late_night")
    }

    // Phase 7 (P4): Back to {channel} while the player's spot holds.

    /** The Ninth Moon on Sci-Fi (6:30-8:31 PM), left paused 1:12:30 in, 9 minutes before 8:41. */
    private val ninthMoon by lazy {
        val slot = SampleGuide.ready().channels.single { it.id == "scifi" }.slotAt(SampleGuide.WEEKNIGHT - 60 * 60_000L)!!
        SavedSpot("scifi", slot.startMillis, slot.itemKey!!, 72 * 60_000L + 30_000, SampleGuide.WEEKNIGHT - 9 * 60_000L)
    }

    private fun backTo(layoutScale: Float, name: String) {
        show(SampleHomeState.state(layoutScale = layoutScale), spot = ninthMoon)
        compose.onNodeWithText("BACK TO SCI-FI").requestFocus()
        capture(name)
    }

    @Test
    fun backTo() = backTo(1f, "home_guide_back_to")

    @Test
    fun backToScale90() = backTo(0.9f, "home_guide_back_to_scale_90")

    @Test
    fun backToScale115() = backTo(1.15f, "home_guide_back_to_scale_115")

    // Back to YouTube TV, opened on NBC from the Live row 40 minutes ago and left 9 minutes ago.

    private val youTubeTv = LaunchableApp(YouTubeTv.PACKAGE, "com.google.android.apps.youtube.tvunplugged.activity.MainActivity", "YouTube TV")

    /** The card focused; [onNbc] false: opened from its icon, so no channel is known. With [sheet], its quick actions open. */
    private fun backToTv(layoutScale: Float, name: String, onNbc: Boolean = true, sheet: Boolean = false) {
        val state = SampleHomeState.state(layoutScale = layoutScale).let { it.copy(allApps = it.allApps + youTubeTv) }
        val now = state.nowMillis
        val visit = AppVisit(YouTubeTv.PACKAGE, frontAt = now - 40 * 60_000L, leftAt = now - 9 * 60_000L)
        val nbc = TunedChannel("NBC", "https://tv.youtube.com/watch/sample3", visit.frontAt - 3_000)
        val espn = TunedChannel("ESPN", "https://tv.youtube.com/watch/sample0", now - 2 * 60 * 60_000L)
        show(state, tv = TvState(visit, if (onNbc) listOf(nbc, espn) else listOf(espn)))
        compose.onNodeWithText("BACK TO YOUTUBE TV").requestFocus()
        if (sheet) {
            compose.onRoot().performKeyInput { pressKey(Key.Menu) }
            compose.waitForIdle()
        }
        capture(name)
    }

    @Test
    fun backToYouTubeTv() = backToTv(1f, "home_guide_back_to_youtube_tv")

    @Test
    fun backToYouTubeTvScale115() = backToTv(1.15f, "home_guide_back_to_youtube_tv_scale_115")

    @Test
    fun backToYouTubeTvNoChannel() = backToTv(1f, "home_guide_back_to_youtube_tv_live_tv", onNbc = false)

    @Test
    fun backToYouTubeTvSheet() = backToTv(1f, "home_guide_back_to_youtube_tv_sheet", sheet = true)
}
