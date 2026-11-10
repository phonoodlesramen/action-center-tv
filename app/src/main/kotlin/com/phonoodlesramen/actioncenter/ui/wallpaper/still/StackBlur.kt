package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * A stack blur: a triangle-weighted moving average, run along the rows and then the
 * columns, in time that doesn't grow with the radius. `Modifier.blur` and `RenderEffect`
 * need Android 12, so the still wallpapers blur a small copy once, in software, and draw
 * it scaled up.
 *
 * The weights of radius r are `r + 1 - |k|` for k in -r..r, so each pass divides by
 * `(r + 1)²`. Edge pixels repeat past the edges. Meant for opaque images: the result's
 * alpha is always opaque.
 */
object StackBlur {
    /** The strongest radius the wallpapers ask for, on a 480 px wide copy. */
    const val MAX_RADIUS = 24

    /** A blurred copy of [source] (ARGB_8888). [source] is left as it is. */
    fun blur(source: Bitmap, radius: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        blur(pixels, width, height, radius)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Blurs [pixels] (ARGB, row by row) in place. A radius below 1 leaves them sharp. */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        require(pixels.size >= width * height) { "${pixels.size} pixels for $width×$height" }
        if (radius < 1 || width == 0 || height == 0) return
        val line = IntArray(maxOf(width, height))
        val out = IntArray(line.size)
        for (y in 0 until height) {
            for (x in 0 until width) line[x] = pixels[y * width + x]
            blurLine(line, width, radius, out)
            for (x in 0 until width) pixels[y * width + x] = out[x]
        }
        for (x in 0 until width) {
            for (y in 0 until height) line[y] = pixels[y * width + x]
            blurLine(line, height, radius, out)
            for (y in 0 until height) pixels[y * width + x] = out[y]
        }
    }

    /**
     * One pass over [count] pixels of [line] into [out]. For each channel it keeps the
     * weighted sum, the sum of the left half (centre included, weights falling as the
     * window moves on) and the right half (weights rising).
     */
    private fun blurLine(line: IntArray, count: Int, r: Int, out: IntArray) {
        val divisor = (r + 1) * (r + 1)
        val half = divisor / 2
        fun at(i: Int) = line[i.coerceIn(0, count - 1)]

        var sumR = 0; var sumG = 0; var sumB = 0
        var outR = 0; var outG = 0; var outB = 0
        var inR = 0; var inG = 0; var inB = 0
        for (k in -r..r) {
            val p = at(k)
            val weight = r + 1 - abs(k)
            sumR += red(p) * weight; sumG += green(p) * weight; sumB += blue(p) * weight
            if (k <= 0) {
                outR += red(p); outG += green(p); outB += blue(p)
            } else {
                inR += red(p); inG += green(p); inB += blue(p)
            }
        }
        for (x in 0 until count) {
            out[x] = (0xFF shl 24) or
                (((sumR + half) / divisor) shl 16) or
                (((sumG + half) / divisor) shl 8) or
                ((sumB + half) / divisor)
            // Slide the window one pixel on: x - r leaves, x + r + 1 arrives, x + 1 crosses the centre.
            val leaving = at(x - r)
            val arriving = at(x + r + 1)
            val crossing = at(x + 1)
            sumR += inR + red(arriving) - outR
            sumG += inG + green(arriving) - outG
            sumB += inB + blue(arriving) - outB
            outR += red(crossing) - red(leaving)
            outG += green(crossing) - green(leaving)
            outB += blue(crossing) - blue(leaving)
            inR += red(arriving) - red(crossing)
            inG += green(arriving) - green(crossing)
            inB += blue(arriving) - blue(crossing)
        }
    }

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun green(argb: Int) = (argb shr 8) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF
}
