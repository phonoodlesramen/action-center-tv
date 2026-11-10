package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackBlurTest {
    private val black = 0xFF000000.toInt()

    private fun grey(value: Int) = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    private fun red(argb: Int) = (argb shr 16) and 0xFF

    @Test
    fun aRadiusOfZeroLeavesThePixels() {
        val pixels = IntArray(25) { grey(it * 10) }
        val before = pixels.copyOf()
        StackBlur.blur(pixels, 5, 5, 0)
        assertArrayEquals(before, pixels)
    }

    @Test
    fun anEvenColourStaysExactlyTheSame() {
        val pixels = IntArray(40 * 30) { 0xFF336699.toInt() }
        StackBlur.blur(pixels, 40, 30, 7)
        assertTrue(pixels.all { it == 0xFF336699.toInt() })
    }

    /**
     * One white pixel spreads into the stack blur's weights: 1, 2, 3, 2, 1 for radius 2,
     * divided by 9 along the row and again down the column, rounded each time.
     */
    @Test
    fun oneBrightPixelSpreadsAsATriangleInBothDirections() {
        val size = 11
        val pixels = IntArray(size * size) { black }
        pixels[5 * size + 5] = grey(255)
        StackBlur.blur(pixels, size, size, 2)
        fun at(x: Int, y: Int) = red(pixels[y * size + x])
        assertEquals(28, at(5, 5))
        assertEquals(19, at(6, 5))
        assertEquals(19, at(5, 6))
        assertEquals(13, at(6, 6))
        assertEquals(9, at(7, 5))
        assertEquals(0, at(8, 5))
        assertEquals(0, at(5, 8))
        // Symmetric about the pixel.
        for (d in 1..3) {
            assertEquals(at(5 + d, 5), at(5 - d, 5))
            assertEquals(at(5, 5 + d), at(5, 5 - d))
        }
    }

    @Test
    fun aHardEdgeBecomesARampAsWideAsTheBlur() {
        val width = 40
        val radius = 4
        val pixels = IntArray(width * 3) { if (it % width < 20) black else grey(255) }
        StackBlur.blur(pixels, width, 3, radius)
        val row = (0 until width).map { red(pixels[width + it]) }
        assertEquals(0, row[20 - radius - 1])
        assertEquals(255, row[20 + radius])
        assertTrue("the ramp only rises", row.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(row[19] in 1..254 && row[20] in 1..254)
    }

    @Test
    fun aBitmapIsBlurredIntoANewOpaqueCopy() {
        val source = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        source.eraseColor(black)
        source.setPixel(16, 9, grey(255))
        val blurred = StackBlur.blur(source, 3)
        assertEquals(Bitmap.Config.ARGB_8888, blurred.config)
        assertEquals(32, blurred.width)
        assertEquals(18, blurred.height)
        assertEquals(grey(255), source.getPixel(16, 9))
        assertTrue(red(blurred.getPixel(16, 9)) in 1..254)
        assertTrue(red(blurred.getPixel(17, 9)) > 0)
        assertEquals(0xFF, blurred.getPixel(0, 0) ushr 24)
    }
}
