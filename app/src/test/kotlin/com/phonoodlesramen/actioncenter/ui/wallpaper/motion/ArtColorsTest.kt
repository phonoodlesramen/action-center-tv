package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtColorsTest {
    private val width = ArtColors.WIDTH
    private val height = ArtColors.HEIGHT

    /** Left third [left], middle [middle], right [right]. */
    private fun thirds(left: Int, middle: Int, right: Int) = IntArray(width * height) { i ->
        val x = i % width
        when {
            x < width / 3 -> left
            x < 2 * width / 3 -> middle
            else -> right
        }
    }

    @Test
    fun `three colours, left to right, keeping each third's hue`() {
        val colors = ArtColors.extract(thirds(RED, GREEN, BLUE), width, height)

        assertEquals(3, colors.size)
        assertEquals(0f, ArtColors.hsv(colors[0])[0], 1f)
        assertEquals(120f, ArtColors.hsv(colors[1])[0], 1f)
        assertEquals(240f, ArtColors.hsv(colors[2])[0], 1f)
    }

    @Test
    fun `colours are toned for a dark background, never bright`() {
        val colors = ArtColors.extract(thirds(WHITE, RED, BLACK), width, height)

        colors.forEach { color ->
            val value = ArtColors.hsv(color)[2]
            assertTrue("Value $value", value in ArtColors.MIN_VALUE - 0.01f..ArtColors.MAX_VALUE + 0.01f)
        }
    }

    @Test
    fun `vivid pixels outweigh grey ones in a third`() {
        // Mostly grey with a quarter of strong orange: the colour leans orange, not grey.
        val pixels = IntArray(width * height) { i -> if (i % 4 == 0) ORANGE else GREY }

        val hsv = ArtColors.hsv(ArtColors.extract(pixels, width, height)[0])

        assertEquals(30f, hsv[0], 6f)
        assertTrue("Saturation ${hsv[1]}", hsv[1] > 0.4f)
    }

    @Test
    fun `transparent pixels are ignored, and an image of nothing gives the background`() {
        val clear = IntArray(width * height)

        assertEquals(List(3) { 0xFF0E1318.toInt() }, ArtColors.extract(clear, width, height))
    }

    @Test
    fun `any thumbnail size works`() {
        val colors = ArtColors.extract(intArrayOf(RED, GREEN), 2, 1)

        assertEquals(3, colors.size)
    }

    @Test
    fun `hsv round trips`() {
        listOf(RED, GREEN, BLUE, ORANGE, 0xFF4FC3F7.toInt()).forEach { color ->
            val (h, s, v) = ArtColors.hsv(color)
            val back = ArtColors.fromHsv(h, s, v)
            listOf(16, 8, 0).forEach { shift ->
                val difference = abs((color shr shift and 0xFF) - (back shr shift and 0xFF))
                assertTrue("${Integer.toHexString(color)} channel $shift is off by $difference", difference <= 1)
            }
        }
    }

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val GREEN = 0xFF00FF00.toInt()
        val BLUE = 0xFF0000FF.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()
        val GREY = 0xFF808080.toInt()
        val ORANGE = 0xFFFF8000.toInt()
    }
}
