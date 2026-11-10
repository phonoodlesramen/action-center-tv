package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colours for a wallpaper from a title's art (Ambient light, Glow spirits). Works on ARGB
 * ints, so it runs and tests without Android; the art is decoded at [WIDTH]×[HEIGHT]
 * first, so there's never much to read.
 */
object ArtColors {
    /** A 16:9 thumbnail this small still holds an image's main colours. */
    const val WIDTH = 16
    const val HEIGHT = 9

    /** The Midnight background, for an image with nothing opaque in it. */
    private const val FALLBACK = 0xFF0E1318.toInt()

    /**
     * Three colours, left to right: each third of the image averaged, vivid pixels
     * counting most so the result isn't grey mush, then [tamed] so white text stays
     * readable over it.
     */
    fun extract(pixels: IntArray, width: Int, height: Int): List<Int> {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Need $width×$height pixels" }
        return (0 until BANDS).map { band ->
            val from = band * width / BANDS
            val to = max(from + 1, (band + 1) * width / BANDS)
            var red = 0.0
            var green = 0.0
            var blue = 0.0
            var weights = 0.0
            for (y in 0 until height) {
                for (x in from until min(to, width)) {
                    val pixel = pixels[y * width + x]
                    if (pixel ushr 24 < OPAQUE) continue
                    val hsv = hsv(pixel)
                    val weight = hsv[1] * hsv[2] + BASE_WEIGHT
                    red += (pixel shr 16 and 0xFF) * weight
                    green += (pixel shr 8 and 0xFF) * weight
                    blue += (pixel and 0xFF) * weight
                    weights += weight
                }
            }
            if (weights == 0.0) FALLBACK else tamed(rgb(red / weights, green / weights, blue / weights))
        }
    }

    /**
     * A colour fit to light a dark TV background: saturation capped, brightness between
     * [MIN_VALUE] and [MAX_VALUE] (no large bright areas, spec §2).
     */
    fun tamed(argb: Int): Int {
        val (hue, saturation, value) = hsv(argb)
        return fromHsv(hue, min(saturation * SATURATION_BOOST, MAX_SATURATION), value.coerceIn(MIN_VALUE, MAX_VALUE))
    }

    /** Hue (0-360), saturation and value (0-1). */
    fun hsv(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val chroma = high - low
        val hue = when {
            chroma == 0f -> 0f
            high == r -> 60f * (((g - b) / chroma).mod(6f))
            high == g -> 60f * ((b - r) / chroma + 2f)
            else -> 60f * ((r - g) / chroma + 4f)
        }
        return floatArrayOf(hue, if (high == 0f) 0f else chroma / high, high)
    }

    /** Opaque ARGB from hue (any angle), saturation and value. */
    fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
        val h = hue.mod(360f)
        val chroma = value * saturation
        val x = chroma * (1 - abs((h / 60f).mod(2f) - 1))
        val (r, g, b) = when {
            h < 60 -> Triple(chroma, x, 0f)
            h < 120 -> Triple(x, chroma, 0f)
            h < 180 -> Triple(0f, chroma, x)
            h < 240 -> Triple(0f, x, chroma)
            h < 300 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = value - chroma
        return rgb(((r + m) * 255).toDouble(), ((g + m) * 255).toDouble(), ((b + m) * 255).toDouble())
    }

    private fun rgb(red: Double, green: Double, blue: Double): Int {
        fun channel(value: Double) = value.roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    private const val BANDS = 3
    private const val OPAQUE = 128
    private const val BASE_WEIGHT = 0.05
    private const val SATURATION_BOOST = 1.2f
    private const val MAX_SATURATION = 0.85f
    const val MIN_VALUE = 0.22f
    const val MAX_VALUE = 0.52f
}
