package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import android.animation.ValueAnimator
import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * How the moving wallpapers move (spec §7: honour the system animation scale). Everything
 * here keeps the work in the wallpaper's own draw phase: no recomposition per frame, and
 * no frames at all while the home is hidden.
 */
object Motion {
    /**
     * Wallpaper time wraps after an hour. Every motion repeats a whole number of times an
     * hour ([cycles]), so the wrap is invisible and a Float stays precise for days.
     */
    const val PERIOD_SECONDS = 3_600f

    /**
     * Android's switch for animations: false when the animator duration scale is 0
     * (Developer options, or Battery saver). `ValueAnimator.areAnimatorsEnabled()`, API 26.
     */
    fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /** Turns of a full circle at [time] for something that goes round [perHour] times an hour. */
    fun cycles(time: Float, perHour: Int): Float = time * perHour / PERIOD_SECONDS

    /** Settings › Wallpaper speed (0-1) as a time multiplier: half speed to one and a half. */
    fun rate(speed: Float): Float = 0.5f + speed.coerceIn(0f, 1f)
}

/**
 * Seconds of wallpaper time, read in the draw phase so only the wallpaper redraws.
 *
 * Runs only while the home is started, at most [fps] frames a second, at [rate] × real
 * time. With animations off (or [running] false) it stays put: a still frame. It starts
 * at [start], so the still frame and the first animated frame are the same picture, and
 * screenshot tests (where Compose cancels infinite animations) see exactly that frame.
 */
@Composable
fun rememberWallpaperTime(rate: Float, fps: Int, start: Float, running: Boolean = true): FloatState {
    val time = remember { mutableFloatStateOf(start) }
    val currentRate by rememberUpdatedState(rate)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, fps, running) {
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Checked each time the home comes back: the scale may have changed meanwhile.
            if (!Motion.animationsEnabled()) return@repeatOnLifecycle
            val frameNanos = 1_000_000_000L / fps
            var last = withInfiniteAnimationFrameNanos { it }
            while (true) {
                val now = withInfiniteAnimationFrameNanos { it }
                // Skip display frames until a wallpaper frame is due (half a frame early is fine).
                if (now - last < frameNanos - frameNanos / 4) continue
                time.floatValue = (time.floatValue + (now - last) / 1e9f * currentRate) % Motion.PERIOD_SECONDS
                last = now
            }
        }
    }
    return time
}

/**
 * A soft round glow of [color], brightest in the middle and gone at [radius], centred on
 * (0, 0): draw it with `canvas.translate` so the shader is made once, not every frame.
 * Dithered, because a dark gradient bands on a TV otherwise (spec §2).
 */
fun glowPaint(color: Color, alpha: Float, radius: Float, blend: BlendMode? = null): Paint {
    val a = alpha.coerceIn(0f, 1f)
    val colors = GLOW_ALPHAS.map { color.copy(alpha = a * it).toArgb() }.toIntArray()
    return Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        shader = RadialGradient(0f, 0f, radius.coerceAtLeast(1f), colors, GLOW_STOPS, Shader.TileMode.CLAMP)
        if (blend != null) blendMode = blend
    }
}

/** A falloff close to a Gaussian, so no edge ever shows. */
private val GLOW_STOPS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
private val GLOW_ALPHAS = listOf(1f, 0.78f, 0.42f, 0.13f, 0f)
