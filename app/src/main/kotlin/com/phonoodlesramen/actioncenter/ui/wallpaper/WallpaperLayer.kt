package com.phonoodlesramen.actioncenter.ui.wallpaper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.AccentGradientWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.AmbientLightWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.CoverArtWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.GlowSpiritsWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.LivingSkyWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.MidnightWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.PosterWallWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.VideoLoopWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.YourImagesWallpaper
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperSources
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperStyle
import com.phonoodlesramen.actioncenter.weather.WeatherState
import java.time.LocalDateTime

/**
 * What a wallpaper style draws from, besides its settings: [LocalWallpaperSources] (the
 * operator's folders, the Plex cache) and [LocalFocusedArt] (Ambient light).
 */
@Immutable
data class WallpaperScene(
    val prefs: WallpaperPrefs,
    val accent: Color,
    /** Late night dims the accent and art (spec §2); animated styles calm down too. */
    val lateNight: Boolean,
    /** Tonight's Pick, for Cover art and Now showing: its Plex id, title and backdrop art path. */
    val pickId: String?,
    val pickTitle: String?,
    val pickArt: String?,
    /** For Living sky and Now showing. */
    val weather: WeatherState,
    val now: LocalDateTime,
)

val LocalWallpaperSources = staticCompositionLocalOf<WallpaperSources> { WallpaperSources.None }

/**
 * The home screen's background (L1): the chosen style over the Midnight colour, under a
 * dim scrim. Each style is its own file in `styles/`, so the styles can be built apart.
 */
@Composable
fun WallpaperLayer(scene: WallpaperScene, modifier: Modifier = Modifier) {
    Box(modifier.background(Palette.Background)) {
        val fill = Modifier.matchParentSize()
        when (scene.prefs.style) {
            WallpaperStyle.MIDNIGHT -> MidnightWallpaper(scene, fill)
            WallpaperStyle.COVER_ART -> CoverArtWallpaper(scene, fill)
            WallpaperStyle.YOUR_IMAGES -> YourImagesWallpaper(scene, fill)
            WallpaperStyle.ACCENT_GRADIENT -> AccentGradientWallpaper(scene, fill)
            WallpaperStyle.GLOW_SPIRITS -> GlowSpiritsWallpaper(scene, fill)
            WallpaperStyle.VIDEO_LOOP -> VideoLoopWallpaper(scene, fill)
            WallpaperStyle.AMBIENT_LIGHT -> AmbientLightWallpaper(scene, fill)
            WallpaperStyle.POSTER_WALL -> PosterWallWallpaper(scene, fill)
            WallpaperStyle.LIVING_SKY -> LivingSkyWallpaper(scene, fill)
        }
        val dim = scene.prefs.dim.coerceIn(0f, WallpaperPrefs.MAX_DIM)
        if (dim > 0f) Box(fill.background(Color.Black.copy(alpha = dim)))
    }
}
