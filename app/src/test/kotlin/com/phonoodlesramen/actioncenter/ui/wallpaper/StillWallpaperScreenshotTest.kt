package com.phonoodlesramen.actioncenter.ui.wallpaper

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.LocalStillImages
import com.phonoodlesramen.actioncenter.wallpaper.CardStyle
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Package A's wallpapers behind the home screen, with Solid and Frosted glass cards.
 * The pictures are small test files (src/test/resources/wallpaper) and drawn posters;
 * the moving ones show their first frame. Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class StillWallpaperScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        style: WallpaperStyle,
        glass: Boolean,
        state: HomeUiState = SampleHomeState.state(),
        prefs: WallpaperPrefs = WallpaperPrefs(),
    ) = compose.setContent {
        ActionCenterTheme(accent = state.accent, layoutScale = state.layoutScale) {
            CompositionLocalProvider(
                LocalStillImages provides TestWallpapers.images,
                LocalWallpaperSources provides TestWallpapers.sources,
                LocalArtUrls provides TestWallpapers.artUrls,
            ) {
                HomeScreen(
                    state = state,
                    actions = HomeActions.None,
                    wallpaper = prefs.copy(style = style, cardStyle = if (glass) CardStyle.GLASS else CardStyle.SOLID),
                )
            }
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    /** Tonight's Pick from a Plex library, so there's a backdrop. */
    private val withPlexPick = SampleHomeState.state(
        sample = false,
        plex = SampleHomeState.plexLinked,
        plexSnapshot = SampleHomeState.plexLibrary(),
    )

    @Test
    fun coverArt() {
        show(WallpaperStyle.COVER_ART, glass = false, state = withPlexPick)
        capture("wallpaper_cover_art")
    }

    @Test
    fun coverArtWithGlass() {
        show(WallpaperStyle.COVER_ART, glass = true, state = withPlexPick)
        capture("wallpaper_cover_art_glass")
    }

    @Test
    fun yourImages() {
        show(WallpaperStyle.YOUR_IMAGES, glass = false, prefs = WallpaperPrefs(imageBlur = 0.6f))
        capture("wallpaper_your_images")
    }

    @Test
    fun yourImagesWithGlass() {
        show(WallpaperStyle.YOUR_IMAGES, glass = true, prefs = WallpaperPrefs(imageBlur = 0.6f))
        capture("wallpaper_your_images_glass")
    }

    /** Blur off (Your images' default): the picture decoded at screen size, glass from its small blurred copy. */
    @Test
    fun yourImagesSharpWithGlass() {
        show(WallpaperStyle.YOUR_IMAGES, glass = true)
        capture("wallpaper_your_images_sharp_glass")
    }

    @Test
    fun accentGradient() {
        show(WallpaperStyle.ACCENT_GRADIENT, glass = false)
        capture("wallpaper_accent_gradient")
    }

    @Test
    fun accentGradientWithGlass() {
        show(WallpaperStyle.ACCENT_GRADIENT, glass = true)
        capture("wallpaper_accent_gradient_glass")
    }

    @Test
    fun posterWall() {
        show(WallpaperStyle.POSTER_WALL, glass = false)
        capture("wallpaper_poster_wall")
    }

    /** Poster wall moves, so glass is a plain tint over it. */
    @Test
    fun posterWallWithGlass() {
        show(WallpaperStyle.POSTER_WALL, glass = true)
        capture("wallpaper_poster_wall_glass")
    }

    /** Late night dims the art (spec §2) and slows the motion. */
    @Test
    fun coverArtLateNight() {
        show(WallpaperStyle.COVER_ART, glass = true, state = SampleHomeState.state(
            sample = false,
            time = SampleHomeState.lateNight,
            plex = SampleHomeState.plexLinked,
            plexSnapshot = SampleHomeState.plexLibrary(SampleHomeState.lateNight),
        ))
        capture("wallpaper_cover_art_late_night")
    }
}
