package com.phonoodlesramen.actioncenter.ui.wallpaper.styles

import android.graphics.BlendMode
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withTranslation
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperScene
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.ArtColors
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.Motion
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.glowPaint
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.rememberArtPalette
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.rememberWallpaperTime
import kotlin.math.PI
import kotlin.math.sin

/**
 * Creative (L1): Soft orbs of light drifting slowly, coloured by the accent or the pick's art.
 *
 * - Six radial glows on one canvas, screen-blended, each on its own slow loop.
 * - Colours: the accent and two neighbours of its hue, plus Tonight's Pick's art colours
 *   once they load (a 16×9 decode, the same as Ambient light).
 * - Speed from Settings; late night is slower and dimmer.
 * - With animations off it's a still frame. Only this layer redraws, at most 30 times a
 *   second, and only while the home is showing.
 */
@Composable
fun GlowSpiritsWallpaper(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val art by rememberArtPalette(scene.pickArt)
    val colors = remember(scene.accent, art) { GlowSpirits.colors(scene.accent, art) }
    val rate = Motion.rate(scene.prefs.speed) * if (scene.lateNight) LATE_NIGHT_RATE else 1f
    val intensity = if (scene.lateNight) LATE_NIGHT_INTENSITY else 1f
    val time = rememberWallpaperTime(rate, fps = FPS, start = GlowSpirits.STILL_SECONDS)
    Spacer(
        modifier.drawWithCache {
            val unit = size.minDimension
            val paints = GlowSpirits.orbs.mapIndexed { i, orb ->
                glowPaint(colors[i % colors.size], orb.alpha * intensity, orb.radius * unit, BlendMode.SCREEN)
            }
            onDrawBehind {
                val t = time.floatValue
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    GlowSpirits.orbs.forEachIndexed { i, orb ->
                        val center = orb.center(t, size)
                        native.withTranslation(center.x, center.y) { drawCircle(0f, 0f, orb.radius * unit, paints[i]) }
                    }
                }
            }
        },
    )
}

/** Glow spirits' orbs and colours: plain maths, kept apart from drawing. */
internal object GlowSpirits {
    /** Where time starts (and stops, with animations off): the orbs well spread. */
    const val STILL_SECONDS = 40f

    private const val ART_VALUE = 0.8f

    /**
     * An orb drifting round its home ([x], [y], fractions of the screen) on an ellipse of
     * [dx] × [dy], going round [perHourX] and [perHourY] times an hour (whole numbers, so
     * the motion repeats seamlessly when wallpaper time wraps). [radius] is a fraction of
     * the screen's short side.
     */
    data class Orb(
        val x: Float,
        val y: Float,
        val dx: Float,
        val dy: Float,
        val perHourX: Int,
        val perHourY: Int,
        val phase: Float,
        val radius: Float,
        val alpha: Float,
    ) {
        fun center(time: Float, size: Size): Offset {
            val ax = 2 * PI.toFloat() * Motion.cycles(time, perHourX) + phase
            val ay = 2 * PI.toFloat() * Motion.cycles(time, perHourY) + phase * 1.7f
            return Offset((x + dx * sin(ax)) * size.width, (y + dy * sin(ay)) * size.height)
        }
    }

    /**
     * About 30-40 px/s at the default speed on a 1080p screen, each going round in 25-65 s.
     * Five times slower (6 px/s) read as frozen on the Shield (10/27/2026).
     */
    val orbs = listOf(
        Orb(0.18f, 0.30f, 0.10f, 0.12f, perHourX = 115, perHourY = 85, phase = 0.0f, radius = 0.62f, alpha = 0.40f),
        Orb(0.78f, 0.22f, 0.12f, 0.10f, perHourX = 95, perHourY = 145, phase = 1.3f, radius = 0.54f, alpha = 0.34f),
        Orb(0.55f, 0.70f, 0.16f, 0.12f, perHourX = 65, perHourY = 105, phase = 2.6f, radius = 0.70f, alpha = 0.30f),
        Orb(0.30f, 0.82f, 0.12f, 0.08f, perHourX = 155, perHourY = 55, phase = 3.9f, radius = 0.46f, alpha = 0.30f),
        Orb(0.92f, 0.68f, 0.08f, 0.14f, perHourX = 85, perHourY = 125, phase = 5.1f, radius = 0.50f, alpha = 0.28f),
        Orb(0.45f, 0.18f, 0.18f, 0.08f, perHourX = 55, perHourY = 95, phase = 0.7f, radius = 0.42f, alpha = 0.24f),
    )

    /**
     * One colour per orb: the accent and its two hue neighbours (a little darker, so
     * nothing is bright), with the art's colours taking every other orb when there are any.
     * Art colours come toned down for backgrounds; a glow lifts them to about the accent's
     * brightness so they count as much.
     */
    fun colors(accent: Color, art: List<Color>?): List<Color> {
        val (hue, saturation, value) = ArtColors.hsv(accent.toArgb())
        fun shade(shift: Float, valueScale: Float) =
            Color(ArtColors.fromHsv(hue + shift, saturation.coerceAtMost(0.8f), (value * valueScale).coerceIn(0.25f, 0.85f)))
        fun lifted(color: Color): Color {
            val (h, s, v) = ArtColors.hsv(color.toArgb())
            return Color(ArtColors.fromHsv(h, s, v.coerceAtLeast(ART_VALUE)))
        }
        val accents = listOf(accent, shade(-32f, 0.8f), shade(28f, 0.75f))
        return orbs.indices.map { i ->
            val fromArt = art?.takeIf { it.isNotEmpty() && i % 2 == 1 }?.let { lifted(it[(i / 2) % it.size]) }
            fromArt ?: accents[i % accents.size]
        }
    }
}

private const val FPS = 30
private const val LATE_NIGHT_RATE = 0.6f
private const val LATE_NIGHT_INTENSITY = 0.7f
