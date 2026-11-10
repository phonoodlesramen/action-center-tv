package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
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
 * The Back-to card when the spot was paused on another TV paired with the same guide station
 * (Phase 8, N6): "Paused in the living room · 9 min ago", at 90, 100 and 115%. This TV's own
 * spot is GuideCardScreenshotTest's `home_guide_back_to`. Record with
 * `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class BackToRoomScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** The Ninth Moon on Sci-Fi, left paused in the living room 9 minutes before 8:41. */
    private val fromTheLivingRoom by lazy {
        val slot = SampleGuide.ready().channels.single { it.id == "scifi" }.slotAt(SampleGuide.WEEKNIGHT - 60 * 60_000L)!!
        SavedSpot("scifi", slot.startMillis, slot.itemKey!!, 72 * 60_000L + 30_000, SampleGuide.WEEKNIGHT - 9 * 60_000L, room = "Living room")
    }

    private fun backTo(layoutScale: Float, name: String) {
        val state = SampleHomeState.state(layoutScale = layoutScale)
        compose.setContent {
            ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
                CompositionLocalProvider(LocalAppBadges provides SampleHomeRows.badges) {
                    HomeScreen(state, HomeActions.None, rows = SampleHomeRows.rows, guide = SampleGuide.ready(), spot = fromTheLivingRoom)
                }
            }
        }
        compose.onNodeWithText("BACK TO SCI-FI").requestFocus()
        compose.onNodeWithText("Paused in the living room · 9 min ago").assertExists()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun backToFromAnotherRoom() = backTo(1f, "home_guide_back_to_room")

    @Test
    fun backToFromAnotherRoomScale90() = backTo(0.9f, "home_guide_back_to_room_scale_90")

    @Test
    fun backToFromAnotherRoomScale115() = backTo(1.15f, "home_guide_back_to_room_scale_115")
}
