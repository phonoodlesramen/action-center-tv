package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalWallpaperSources
import com.phonoodlesramen.actioncenter.ui.wallpaper.TestWallpapers
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.LocalStillImages
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Live row (G7, plan §6) with the sample lineup, at the Shield's 1080p TV
 * configuration: on now at 8:41 PM, late at night, off air after midnight, and at 90 and
 * 115%; with favorites, and a favorite being moved (Phase 13). Plex art doesn't load here, so programs show their generated art. Record with
 * `.\gradlew.bat recordRoborazziDebug`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class LiveRowScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: HomeUiState = SampleHomeState.state(),
        wallpaper: WallpaperPrefs = WallpaperPrefs(),
        favorites: List<String> = emptyList(),
    ) = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            CompositionLocalProvider(
                LocalAppBadges provides SampleHomeRows.badges,
                LocalStillImages provides TestWallpapers.images,
                LocalWallpaperSources provides TestWallpapers.sources,
            ) {
                HomeScreen(state, HomeActions.None, rows = SampleHomeRows.rows, wallpaper = wallpaper, guide = SampleGuide.ready(), favorites = favorites)
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    private fun card(description: String) = compose.onNodeWithContentDescription(description, substring = true)

    /** Movies on now, Sci-Fi between programs (Up next at 9:00), then YouTube TV's networks. */
    @Test
    fun onNowWeeknight() {
        show()
        card("Movies").requestFocus()
        capture("live_row_weeknight")
    }

    @Test
    fun lateNight() {
        show(SampleHomeState.state(time = SampleHomeState.lateNight))
        card("Movies").requestFocus()
        capture("live_row_late_night")
    }

    /** 12:45 AM: Classics' program has left the library; Movies is between programs. */
    @Test
    fun offAir() {
        show(SampleHomeState.state(time = SampleHomeState.weeknight.plusHours(4).plusMinutes(4)))
        card("Classics").requestFocus()
        capture("live_row_off_air")
    }

    @Test
    fun scale90() {
        show(SampleHomeState.state(layoutScale = 0.9f))
        card("Movies").requestFocus()
        capture("live_row_scale_90")
    }

    @Test
    fun scale115() {
        show(SampleHomeState.state(layoutScale = 1.15f))
        card("Movies").requestFocus()
        capture("live_row_scale_115")
    }

    /** Phase 13: Comedy and TV Mix are favorites, first with a star, then the rest by number. */
    @Test
    fun favorites() {
        show(favorites = SampleHomeState.favorites)
        card("Comedy").requestFocus()
        capture("live_row_favorites")
    }

    /** Hold OK › Move on Comedy, then Right: carried past TV Mix, lifted, "Left or right, then OK" in the accent. */
    @Test
    fun moving() {
        show(favorites = SampleHomeState.favorites)
        card("Comedy").requestFocus()
        press(Key.Menu)
        press(Key.DirectionDown, times = 3)
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        capture("live_row_move")
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** Captions under the cards get the wallpaper's halo on a bright picture. */
    @Test
    fun overYourImages() {
        show(wallpaper = WallpaperPrefs(style = WallpaperStyle.YOUR_IMAGES))
        card("TV Mix").requestFocus()
        capture("live_row_your_images")
    }
}
