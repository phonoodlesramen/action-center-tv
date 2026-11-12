package com.phonoodlesramen.actioncenter.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveClock
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * On Now (spec §4, Home layouts, H5) at the Shield's 1080p, at 90, 100 and 115%: the last
 * channel's tile, the mini guide and its now line, the D-pad, and the dashboard without a guide.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class OnNowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var focus by mutableStateOf(FocusRequest())
    private val opened = mutableListOf<String>()

    private val actions = object : HomeActions {
        override fun tuneIn(channelId: String) {
            opened += "tune $channelId"
        }

        override fun resume(spot: SavedSpot) {
            opened += "resume ${spot.channelId}"
        }

        override fun openDetails(channelId: String, startMillis: Long) {
            opened += "details $channelId"
        }
    }

    private fun onNow(layoutScale: Float = 1f) = SampleHomeState.state(layoutScale = layoutScale, homeLayout = HomeLayout.ON_NOW)

    private fun show(state: HomeUiState = onNow(), guide: GuideCardState = SampleGuide.ready(state.nowMillis), spot: SavedSpot? = null) {
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                HomeScreen(state, actions, focus = focus, guide = guide, favorites = SampleHomeState.favorites, spot = spot)
            }
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    /** Sci-Fi's latest program (not its filler), paused nine minutes ago ten minutes in (P3). */
    private fun pausedOnSciFi(): SavedSpot {
        val now = SampleGuide.WEEKNIGHT
        val slot = SampleGuide.ready(now).channels.first { it.id == "scifi" }.slots.last { !it.filler && it.offAir == null && it.startMillis <= now }
        return SavedSpot("scifi", slot.startMillis, slot.itemKey!!, positionMs = 10 * 60_000L, savedAtMillis = now - 9 * 60_000L)
    }

    @Test
    fun firstScreen() {
        show()
        capture("home_on_now")
    }

    @Test
    fun firstScreenAtSmallestScale() {
        show(onNow(LayoutScale.MIN))
        capture("home_on_now_scale_90")
    }

    @Test
    fun firstScreenAtLargestScale() {
        show(onNow(LayoutScale.MAX))
        capture("home_on_now_scale_115")
    }

    @Test
    fun backToThePausedChannel() {
        show(spot = pausedOnSciFi())
        compose.onNodeWithContentDescription("Back to 2 Sci-Fi", substring = true).assertIsFocused()
        capture("home_on_now_paused")
    }

    @Test
    fun rightEntersTheMiniGuide() {
        show()
        press(Key.DirectionRight)
        capture("home_on_now_mini_guide")
    }

    @Test
    fun miniGuideAtLargestScale() {
        show(onNow(LayoutScale.MAX))
        press(Key.DirectionRight)
        capture("home_on_now_mini_guide_scale_115")
    }

    @Test
    fun playIsTheTileAndOkTunesIn() {
        show()
        val tile = compose.onNodeWithContentDescription("On now · 5 Comedy", substring = true)
        tile.assertIsFocused()
        tile.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("tune comedy"), opened)
    }

    @Test
    fun okOnThePausedTileResumes() {
        show(spot = pausedOnSciFi())
        compose.onNodeWithContentDescription("Back to 2 Sci-Fi", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("resume scifi"), opened)
    }

    @Test
    fun theMiniGuideTunesInNowAndOpensLaterDetails() {
        show()
        val comedy = SampleGuide.ready(SampleGuide.WEEKNIGHT).channels.first { it.id == "comedy" }
        val now = comedy.slotAt(SampleGuide.WEEKNIGHT)!!
        val later = comedy.slots.first { it.startMillis > SampleGuide.WEEKNIGHT && !it.filler }
        val clock = LiveClock(SampleHomeState.weeknight, SampleGuide.WEEKNIGHT)
        compose.onNodeWithContentDescription("${now.title}, 5 Comedy, ${clock.timeOf(now.startMillis)}").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("${later.title}, 5 Comedy, ${clock.timeOf(later.startMillis)}").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("tune comedy", "details comedy"), opened)
    }

    @Test
    fun backFromTheGuideLandsOnTheTile() {
        show()
        press(Key.DirectionRight)
        focus = FocusRequest(HomeFocus.GUIDE, serial = 1)
        settle()
        compose.onNodeWithContentDescription("On now · 5 Comedy", substring = true).assertIsFocused()
    }

    @Test
    fun withoutAGuideItsTheDashboard() {
        show(guide = GuideCardState.NotSetUp)
        compose.onNodeWithText("Play S2E9").assertIsFocused()
        compose.onNodeWithText("ON NOW").assertDoesNotExist()
    }

    @Test
    fun theWindowStartsOnTheHalfHourAndTheNowLineMovesWithTheClock() {
        val at841 = LocalDateTime.of(2026, 10, 26, 20, 41)
        val clock = LiveClock(at841, SampleGuide.WEEKNIGHT)
        val window = MiniGuideWindow.at(clock)

        assertEquals("8:30", clock.timeOf(window.startMillis))
        assertEquals(11f / 90f, window.fraction(SampleGuide.WEEKNIGHT), 0.0001f)
        assertEquals(12f / 90f, window.fraction(SampleGuide.WEEKNIGHT + 60_000L), 0.0001f)
        // On the hour, it rolls over.
        val at900 = MiniGuideWindow.at(LiveClock(at841.plusMinutes(19), SampleGuide.WEEKNIGHT + 19 * 60_000L))
        assertEquals("9:00", clock.timeOf(at900.startMillis))
    }

    @Test
    fun theMiniGuideHasYourFavoritesFirst() {
        val channels = miniGuideChannels(SampleGuide.ready(), SampleHomeState.favorites)
        assertEquals(listOf("comedy", "tv-mix", "movies", "scifi"), channels.map { it.id })
    }
}
