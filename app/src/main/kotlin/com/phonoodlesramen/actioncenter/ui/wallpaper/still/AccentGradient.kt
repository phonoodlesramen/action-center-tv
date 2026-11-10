package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import kotlin.math.sqrt

/**
 * Accent gradient (L1): the accent glowing deep from the top left, fading into the
 * background, with a fainter glow at the bottom right.
 *
 * TVs band smooth gradients (spec §2), so it's computed per pixel in software and
 * dithered: each channel gets triangular noise of up to one level before rounding, which
 * turns the steps into a fine grain the eye averages away. It's rendered at half the
 * screen (960×540, 2 MB) and drawn at twice the size on whole pixels, so the dither isn't
 * smoothed back into bands.
 */
object AccentGradient {
    const val WIDTH = 960
    const val HEIGHT = 540

    /** Never more than this much accent, however dark the accent. */
    const val MAX_MIX = 0.42f

    /** Late night: artwork at 75% brightness (spec §2), and the glow with it. */
    const val LATE_NIGHT = 0.75f

    /** The bottom-right glow's strength against the top-left one. */
    private const val SECOND_GLOW = 0.4f

    /**
     * The most accent a pixel takes: as much as keeps dim text over it at 4.5:1
     * ([Legibility.Limit]), with a level to spare for the dither.
     */
    fun strongestMix(accent: Color): Float {
        val background = Palette.Background
        fun fits(mix: Float): Boolean {
            val r = channel(background.red, accent.red, mix) + 1f
            val g = channel(background.green, accent.green, mix) + 1f
            val b = channel(background.blue, accent.blue, mix) + 1f
            return Legibility.luminance(argb(r, g, b)) <= Legibility.Limit
        }
        if (fits(MAX_MIX)) return MAX_MIX
        var low = 0f
        var high = MAX_MIX
        repeat(16) {
            val mid = (low + high) / 2
            if (fits(mid)) low = mid else high = mid
        }
        return low
    }

    /** How much of the glow reaches a point, 0 to 1. [x] and [y] are in screen widths. */
    fun glow(x: Float, y: Float): Float {
        val first = bump(x, y, 0.12f, -0.20f, 1.15f)
        val second = bump(x, y, 1.04f, 0.66f, 0.70f)
        return 1f - (1f - first) * (1f - SECOND_GLOW * second)
    }

    /** The gradient for [accent], dithered with a fixed seed so it's the same every time. */
    fun render(accent: Color, lateNight: Boolean, width: Int = WIDTH, height: Int = HEIGHT, seed: Int = 0x5EED): Bitmap {
        val mix = strongestMix(accent) * if (lateNight) LATE_NIGHT else 1f
        val background = Palette.Background
        val noise = Noise(seed)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val t = mix * glow(x.toFloat() / width, y.toFloat() / width)
                pixels[y * width + x] = argb(
                    channel(background.red, accent.red, t) + noise.next(),
                    channel(background.green, accent.green, t) + noise.next(),
                    channel(background.blue, accent.blue, t) + noise.next(),
                )
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** A smooth bump: 1 at ([cx], [cy]), easing to 0 at [radius] with no edge. */
    private fun bump(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Float {
        val dx = x - cx
        val dy = y - cy
        val d = sqrt(dx * dx + dy * dy) / radius
        if (d >= 1f) return 0f
        val falloff = 1f - d * d
        return falloff * falloff
    }

    /** One channel (0 to 255, unrounded) [mix] of the way from [from] to [to]. */
    private fun channel(from: Float, to: Float, mix: Float) = (from + (to - from) * mix) * 255f

    private fun argb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (round(r) shl 16) or (round(g) shl 8) or round(b)

    private fun round(value: Float) = (value + 0.5f).toInt().coerceIn(0, 255)

    /** Triangular noise in -1..1 from a xorshift generator: cheap, and the same for a seed. */
    private class Noise(seed: Int) {
        private var state = if (seed == 0) 1 else seed

        private fun uniform(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return (state ushr 8) / 16_777_216f
        }

        fun next(): Float = uniform() + uniform() - 1f
    }
}
