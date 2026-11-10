package com.phonoodlesramen.actioncenter.ui.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.HomeScreen
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.ArtColors
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.ArtPaletteSource
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.LocalArtPaletteSource
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperStyle
import com.phonoodlesramen.actioncenter.weather.Place
import com.phonoodlesramen.actioncenter.weather.TemperatureUnit
import com.phonoodlesramen.actioncenter.weather.WeatherReading
import com.phonoodlesramen.actioncenter.weather.WeatherState
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Package B's wallpapers as still frames (Compose stops infinite animations in tests, so
 * each style shows its start frame), and Now showing. Art comes from
 * `src/test/resources/wallpaper/backdrop.png`, since Robolectric can't reach Plex.
 * Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class MovingWallpapersScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    /** New York's sun on 26 October 2026 (the Open-Meteo fixture): up at 7:19, down at 17:59. */
    private fun weather(at: LocalDateTime, code: Int) = WeatherState(
        place = Place("New York", "New York", "United States", 40.71, -74.01),
        unit = TemperatureUnit.FAHRENHEIT,
        reading = WeatherReading(
            celsius = 12.2,
            code = code,
            isDay = at.hour in 8..17,
            // On the device's clock, as the wallpaper reads it: 12 minutes old on any machine.
            fetchedAtMillis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - 12 * 60_000,
            sunrise = at.toLocalDate().atTime(7, 19),
            sunset = at.toLocalDate().atTime(17, 59),
        ),
    )

    private fun scene(
        style: WallpaperStyle,
        at: LocalDateTime = SampleHomeState.weeknight,
        code: Int = 0,
        lateNight: Boolean = false,
        pick: Boolean = true,
        nowShowing: Boolean = false,
    ): WallpaperScene {
        val sample = SampleHomeState.state(time = at).pick
        return WallpaperScene(
            prefs = WallpaperPrefs(style = style, nowShowing = nowShowing),
            accent = if (lateNight) Palette.lateNight(Palette.DefaultAccent) else Palette.DefaultAccent,
            lateNight = lateNight,
            pickId = sample?.id?.takeIf { pick },
            pickTitle = sample?.title?.takeIf { pick },
            pickArt = BACKDROP.takeIf { pick },
            weather = weather(at, code),
            now = at,
        )
    }

    /** The test backdrop's colours, worked out the way the app does from a 16×9 thumbnail. */
    private val backdropColors = ArtPaletteSource { _ ->
        val image = javaClass.getResourceAsStream("/wallpaper/backdrop.png")!!.use(BitmapFactory::decodeStream)
        val small = Bitmap.createScaledBitmap(image, ArtColors.WIDTH, ArtColors.HEIGHT, true)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        ArtColors.extract(pixels, small.width, small.height).map { Color(it) }
    }

    private fun show(focusedArt: String? = null, content: @Composable () -> Unit) = compose.setContent {
        ActionCenterTheme {
            CompositionLocalProvider(
                LocalArtPaletteSource provides backdropColors,
                LocalFocusedArt provides FocusedArt().apply { image = focusedArt },
                content = content,
            )
        }
    }

    private fun wallpaper(scene: WallpaperScene, focusedArt: String? = null) = show(focusedArt) {
        WallpaperLayer(scene, Modifier.fillMaxSize())
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun glowSpiritsInTheAccent() {
        wallpaper(scene(WallpaperStyle.GLOW_SPIRITS, pick = false))
        capture("wallpaper_glow_spirits")
    }

    @Test
    fun glowSpiritsWithThePicksColours() {
        wallpaper(scene(WallpaperStyle.GLOW_SPIRITS))
        capture("wallpaper_glow_spirits_art")
    }

    @Test
    fun glowSpiritsLateNight() {
        wallpaper(scene(WallpaperStyle.GLOW_SPIRITS, at = SampleHomeState.lateNight, lateNight = true))
        capture("wallpaper_glow_spirits_late_night")
    }

    @Test
    fun ambientLightFromTheFocusedArt() {
        wallpaper(scene(WallpaperStyle.AMBIENT_LIGHT), focusedArt = BACKDROP)
        capture("wallpaper_ambient_light")
    }

    @Test
    fun ambientLightBeforeAnyArtHasFocus() {
        wallpaper(scene(WallpaperStyle.AMBIENT_LIGHT))
        capture("wallpaper_ambient_light_accent")
    }

    @Test
    fun livingSkyThroughTheDay() {
        val day = SampleHomeState.weeknight.toLocalDate()
        var current by mutableStateOf(scene(WallpaperStyle.LIVING_SKY))
        show { WallpaperLayer(current, Modifier.fillMaxSize()) }
        listOf(
            "night" to day.atTime(5, 30),
            "dawn" to day.atTime(7, 9),
            "day" to day.atTime(12, 0),
            "golden_hour" to day.atTime(17, 40),
            "dusk" to day.atTime(18, 19),
        ).forEach { (name, at) ->
            current = scene(WallpaperStyle.LIVING_SKY, at = at)
            compose.waitForIdle()
            capture("wallpaper_living_sky_$name")
        }
    }

    @Test
    fun livingSkyRain() {
        wallpaper(scene(WallpaperStyle.LIVING_SKY, at = SampleHomeState.weeknight.toLocalDate().atTime(14, 0), code = 63))
        capture("wallpaper_living_sky_rain")
    }

    @Test
    fun livingSkySnowAtNight() {
        wallpaper(scene(WallpaperStyle.LIVING_SKY, at = SampleHomeState.weeknight, code = 73))
        capture("wallpaper_living_sky_snow")
    }

    @Test
    fun nowShowingThePick() {
        show { NowShowingScreen(scene(WallpaperStyle.MIDNIGHT, code = 3), Modifier.fillMaxSize()) }
        capture("now_showing")
    }

    @Test
    fun nowShowingWithoutAPick() {
        show { NowShowingScreen(scene(WallpaperStyle.GLOW_SPIRITS, code = 3, pick = false), Modifier.fillMaxSize()) }
        capture("now_showing_no_pick")
    }

    @Test
    fun homeOverGlowSpirits() {
        val state = SampleHomeState.state()
        show {
            HomeScreen(state, HomeActions.None, wallpaper = WallpaperPrefs(style = WallpaperStyle.GLOW_SPIRITS))
        }
        capture("home_wallpaper_glow_spirits")
    }

    private companion object {
        /** Any Plex-style path: the test palette source reads the one backdrop for all. */
        const val BACKDROP = "/library/metadata/1/art/1"
    }
}
