package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import kotlin.math.pow

/**
 * Spec §6: text keeps 4.5:1 against the brightest point a wallpaper leaves under it.
 * Every still wallpaper is held at or under [Limit], where primary text
 * ([Palette.Text]) meets 4.5:1. That's the operator's choice (10/27/2026): brighter
 * wallpapers, with a soft halo under the dimmer labels that sit on them
 * (`onWallpaper()`), rather than walls dark enough for [Palette.TextDim].
 */
object Legibility {
    const val MIN_CONTRAST = 4.5f

    /** The brightest relative luminance a wallpaper may leave anywhere. */
    val Limit: Float = limitFor(Palette.Text)

    /** Where dim text meets 4.5:1: the most a frosted glass card may let through, since dim text sits inside them. */
    val DimLimit: Float = limitFor(Palette.TextDim)

    /**
     * The worst a scrimmed still wallpaper can leave: the brightest grey under [Limit].
     * Blended toward a dark tint, a grey stays brighter than any colour of the same
     * luminance, so a tint that copes with it copes with them all.
     */
    val BrightestStill: Int by lazy {
        (255 downTo 0).map { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }.first { luminance(it) <= Limit }
    }

    /** The brightest background [text] keeps [MIN_CONTRAST] against (WCAG 2 contrast). */
    fun limitFor(text: Color): Float = (text.luminance() + 0.05f) / MIN_CONTRAST - 0.05f

    fun contrast(a: Float, b: Float): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

    /** WCAG relative luminance of an sRGB colour. */
    fun luminance(argb: Int): Float =
        0.2126f * Linear[(argb shr 16) and 0xFF] + 0.7152f * Linear[(argb shr 8) and 0xFF] + 0.0722f * Linear[argb and 0xFF]

    /**
     * The scrim a picture needs: the least alpha of [Palette.Background] (blended in sRGB,
     * as Android does) that brings its brightest sampled pixel under [Limit]. [pixels] is
     * ARGB, row by row; about 60 samples across the short side are plenty for a picture
     * that's blurred or 480 px wide.
     *
     * @param least the scrim never goes below this, so a dark picture still looks dimmed.
     */
    fun scrimFor(pixels: IntArray, width: Int, height: Int, least: Float = 0f): Scrim {
        if (width == 0 || height == 0) return Scrim(least)
        val step = maxOf(1, minOf(width, height) / SAMPLES)
        val samples = ArrayList<Int>()
        for (y in 0 until height step step) {
            for (x in 0 until width step step) samples += pixels[y * width + x]
        }
        return Scrim(maxOf(least, alphaFor(samples, Limit)))
    }

    /**
     * How strongly a picture may be drawn over [Palette.Background] (its alpha, at most
     * [most]) with its brightest pixel still under [Limit]: Poster wall's posters.
     */
    fun strongestOverBackground(pixels: IntArray, most: Float): Float {
        val step = maxOf(1, pixels.size / (SAMPLES * SAMPLES))
        val samples = (pixels.indices step step).map { pixels[it] }
        return minOf(most, 1f - alphaFor(samples, Limit))
    }

    /** The least alpha of [scrim] over [pixels] that keeps them all at or under [limit]. */
    fun alphaFor(pixels: Collection<Int>, limit: Float, scrim: Color = Palette.Background): Float {
        val over = scrim.toArgb()
        fun fits(alpha: Float) = pixels.all { luminance(blend(it, over, alpha)) <= limit }
        if (fits(0f)) return 0f
        if (!fits(1f)) return 1f
        var low = 0f
        var high = 1f
        repeat(14) {
            val mid = (low + high) / 2
            if (fits(mid)) high = mid else low = mid
        }
        return high
    }

    /** [over] at [alpha] over [under], per channel in sRGB. */
    fun blend(under: Int, over: Int, alpha: Float): Int {
        fun channel(shift: Int): Int {
            val u = (under shr shift) and 0xFF
            val o = (over shr shift) and 0xFF
            return (u + (o - u) * alpha + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private const val SAMPLES = 60

    private val Linear = FloatArray(256) { value ->
        val c = value / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
}

/** The background colour at [alpha] over a whole picture: what keeps text over it readable. */
@JvmInline
value class Scrim(val alpha: Float) {
    /** Over a wallpaper of [canvas] size: glass cards draw it shifted, in a canvas of their own size. */
    fun DrawScope.drawScrim(canvas: Size, fade: Float = 1f, color: Color = Palette.Background) {
        if (alpha > 0f && fade > 0f) drawRect(color.copy(alpha = alpha * fade), Offset.Zero, canvas)
    }

    companion object {
        val None = Scrim(0f)
    }
}
