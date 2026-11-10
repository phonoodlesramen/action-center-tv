package com.phonoodlesramen.actioncenter.ui.wallpaper.styles

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.graphics.withTranslation
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperScene
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.Motion
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.SkyClock
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.SkyColors
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.SunTimes
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.glowPaint
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.rememberWallpaperTime
import com.phonoodlesramen.actioncenter.weather.Sky
import com.phonoodlesramen.actioncenter.weather.display
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Frontier (L1): A sky that follows the real sunrise and sunset and the current weather.
 *
 * - **Time of day:** night, dawn, day, golden hour and dusk from today's sunrise and sunset
 *   (Open-Meteo), else 6:30 and 19:00 ([SkyClock], [SunTimes]). The colours move on with
 *   the home's minute tick; the top stays darkest, behind the top bar.
 * - **Weather:** overcast skies are greyer; rain, drizzle and storms fall as streaks and
 *   snow as drifting flakes, while the reading is fresh enough for the top bar to show it.
 * - **Stars** at night, twinkling slowly.
 * - **Frames:** 30 a second while something falls, 10 for twinkling stars, none on a
 *   clear day; with animations off, a still frame. Late night dims it a little.
 */
@Composable
fun LivingSkyWallpaper(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val sun = SunTimes.from(scene.weather)
    val sky = remember(scene.now, sun) { SkyClock.colors(scene.now.toLocalTime(), sun) }
    val nowMillis = scene.now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val weather = scene.weather.display(nowMillis)?.sky
    val fall = LivingSky.precipitation(weather)
    val overcast = LivingSky.overcast(weather)
    // Cloud hides the stars: gone at half overcast, so snow is never mistaken for them.
    val stars = sky.stars * (1f - overcast * 2f).coerceAtLeast(0f)
    val fps = when {
        fall != null -> FALL_FPS
        stars > VISIBLE -> TWINKLE_FPS
        else -> 0
    }
    val time = rememberWallpaperTime(Motion.rate(scene.prefs.speed), fps = fps.coerceAtLeast(1), start = LivingSky.STILL_SECONDS, running = fps > 0)
    val dim = if (scene.lateNight) LATE_NIGHT_DIM else 0f
    Spacer(
        modifier.drawWithCache {
            val colors = LivingSky.overcast(sky, overcast)
            val gradient = Paint(Paint.DITHER_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, size.height,
                    intArrayOf(colors.top, colors.middle, colors.horizon),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            val glowRadius = size.maxDimension * 0.55f
            val glow = glowPaint(Color(colors.glow), colors.glowAlpha * (1f - overcast * 0.6f), glowRadius)
            val starField = LivingSky.stars(size.width, size.height, density)
            val particles = fall?.let { LivingSky.Particles(it, size.width, size.height, density) }
            onDrawBehind {
                val t = time.floatValue
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.drawRect(0f, 0f, size.width, size.height, gradient)
                    if (colors.glowAlpha > 0f) {
                        native.withTranslation(size.width * 0.68f, size.height * 1.05f) { drawCircle(0f, 0f, glowRadius, glow) }
                    }
                    if (stars > VISIBLE) starField.draw(native, t, stars)
                    particles?.draw(native, t)
                }
                if (dim > 0f) drawRect(Color.Black, alpha = dim)
            }
        },
    )
}

/** Living sky's weather and particles: plain maths, kept apart from drawing. */
internal object LivingSky {
    /** Where time starts (and stops, with animations off). */
    const val STILL_SECONDS = 20f

    enum class Fall(val count: Int) { DRIZZLE(70), RAIN(150), STORM(210), SNOW(120) }

    fun precipitation(sky: Sky?): Fall? = when (sky) {
        Sky.DRIZZLE -> Fall.DRIZZLE
        Sky.RAIN -> Fall.RAIN
        Sky.THUNDER -> Fall.STORM
        Sky.SNOW -> Fall.SNOW
        else -> null
    }

    /** How far cloud greys the sky (0 clear, 1 grey), and hides the stars. */
    fun overcast(sky: Sky?): Float = when (sky) {
        null, Sky.CLEAR -> 0f
        Sky.PARTLY_CLOUDY -> 0.25f
        Sky.CLOUDY, Sky.DRIZZLE, Sky.SNOW -> 0.5f
        Sky.FOG -> 0.6f
        Sky.RAIN, Sky.THUNDER -> 0.6f
    }

    /** [sky] mixed toward a grey of its own brightness. */
    fun overcast(sky: SkyColors, amount: Float): SkyColors {
        if (amount <= 0f) return sky
        fun grey(argb: Int): Int {
            val luma = (0.3f * (argb shr 16 and 0xFF) + 0.59f * (argb shr 8 and 0xFF) + 0.11f * (argb and 0xFF)).toInt()
            return SkyClock.lerpArgb(argb, (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma, amount)
        }
        return sky.copy(top = grey(sky.top), middle = grey(sky.middle), horizon = grey(sky.horizon))
    }

    /** About 140 stars in the upper sky, always in the same places, in four twinkle groups. */
    fun stars(width: Float, height: Float, density: Float): StarField {
        val random = Random(STAR_SEED)
        val groups = List(TWINKLE_GROUPS) { FloatArray(STARS_PER_GROUP * 2) }
        groups.forEach { points ->
            for (i in 0 until STARS_PER_GROUP) {
                points[i * 2] = random.nextFloat() * width
                // Thinner toward the horizon.
                points[i * 2 + 1] = random.nextFloat().let { it * it } * height * 0.7f
            }
        }
        return StarField(groups, density)
    }

    class StarField(private val groups: List<FloatArray>, density: Float) {
        private val paints = groups.indices.map { i ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = density * (0.9f + 0.45f * (i % 3))
            }
        }

        /** Each group brightens and fades on its own slow cycle. */
        fun draw(canvas: android.graphics.Canvas, time: Float, brightness: Float) {
            groups.forEachIndexed { i, points ->
                val twinkle = 0.7f + 0.3f * sin(2 * PI.toFloat() * Motion.cycles(time, TWINKLES_PER_HOUR + i * 7) + i)
                paints[i].alpha = (255 * brightness * twinkle * (0.55f + 0.12f * i)).toInt().coerceIn(0, 255)
                canvas.drawPoints(points, paints[i])
            }
        }
    }

    /**
     * Rain streaks or snowflakes, each falling on a loop that repeats a whole number of
     * times an hour. Positions are computed into reused arrays each frame: no allocation.
     */
    class Particles(private val fall: Fall, private val width: Float, private val height: Float, density: Float) {
        private val random = Random(PARTICLE_SEED + fall.ordinal)
        private val x0 = FloatArray(fall.count) { random.nextFloat() }
        private val y0 = FloatArray(fall.count) { random.nextFloat() }
        private val trips = IntArray(fall.count) {
            val (low, high) = if (fall == Fall.SNOW) SNOW_TRIPS else RAIN_TRIPS
            low + random.nextInt(high - low)
        }
        private val sway = FloatArray(fall.count) { random.nextFloat() * 2 * PI.toFloat() }
        private val length = height * if (fall == Fall.STORM) 0.03f else 0.022f

        /** Snow falls in [SNOW_SIZES] layers, nearer flakes bigger and brighter; rain is one layer of streaks. */
        private val layers = if (fall == Fall.SNOW) SNOW_SIZES else 1
        private val points = List(layers) { layer ->
            val inLayer = (0 until fall.count).count { it % layers == layer }
            FloatArray(inLayer * if (fall == Fall.SNOW) 2 else 4)
        }
        private val paints = List(layers) { layer ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeCap = Paint.Cap.ROUND
                if (fall == Fall.SNOW) {
                    color = android.graphics.Color.argb(110 + 50 * layer, 235, 240, 248)
                    strokeWidth = density * (2.5f + 1.5f * layer)
                } else {
                    color = android.graphics.Color.argb(if (fall == Fall.DRIZZLE) 70 else 95, 170, 196, 224)
                    strokeWidth = density * 0.8f
                }
            }
        }

        fun draw(canvas: android.graphics.Canvas, time: Float) {
            for (i in 0 until fall.count) {
                val out = points[i % layers]
                val at = i / layers
                val progress = (y0[i] + Motion.cycles(time, trips[i])) % 1f
                val y = progress * (height + length) - length
                if (fall == Fall.SNOW) {
                    val drift = 0.012f * width * sin(2 * PI.toFloat() * Motion.cycles(time, SNOW_SWAYS) + sway[i])
                    out[at * 2] = x0[i] * width + drift
                    out[at * 2 + 1] = y
                } else {
                    // Falling at a slight slant, along the streak's own angle, wrapping at the edges.
                    val x = (x0[i] * width - progress * (height + length) * RAIN_TILT).mod(width)
                    out[at * 4] = x
                    out[at * 4 + 1] = y
                    out[at * 4 + 2] = x - length * RAIN_TILT
                    out[at * 4 + 3] = y + length
                }
            }
            points.forEachIndexed { layer, out ->
                if (fall == Fall.SNOW) canvas.drawPoints(out, paints[layer]) else canvas.drawLines(out, paints[layer])
            }
        }
    }

    private const val STAR_SEED = 7
    private const val PARTICLE_SEED = 11
    private const val TWINKLE_GROUPS = 4
    private const val STARS_PER_GROUP = 36
    private const val TWINKLES_PER_HOUR = 211

    /** Screen crossings an hour: rain about 1.5 s a fall, snow about 12 s. */
    private val RAIN_TRIPS = 2_000 to 2_900
    private val SNOW_TRIPS = 240 to 380
    private const val SNOW_SWAYS = 400
    private const val RAIN_TILT = 0.12f
    private const val SNOW_SIZES = 3
}

private const val FALL_FPS = 30
private const val TWINKLE_FPS = 10
private const val VISIBLE = 0.05f
private const val LATE_NIGHT_DIM = 0.2f
