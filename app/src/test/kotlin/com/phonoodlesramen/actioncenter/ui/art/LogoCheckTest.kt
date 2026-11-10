package com.phonoodlesramen.actioncenter.ui.art

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.ui.home.HERO_LOGO_BACKGROUND
import com.phonoodlesramen.actioncenter.ui.home.lensStyle
import com.phonoodlesramen.actioncenter.ui.home.moreLikeStyle
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The dark-logo check (Phase 12, L2) on made-up wordmarks (a made-up name drawn in one colour
 * on a transparent background, as a Plex clearLogo is), against each card's own background:
 * the hero's scrim, and every lens's lightest stop.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoCheckTest {
    private val hero = LogoCheck.luminance(HERO_LOGO_BACKGROUND)

    /** Each lens's lightest stop, and More like this's (the default accent's surface). */
    private val lenses = Lens.entries.associate { it.name to LogoCheck.luminance(lensStyle(it).lightest) } +
        ("MORE_LIKE" to LogoCheck.luminance(moreLikeStyle(Palette.DefaultAccent).lightest))

    private val family = LogoCheck.luminance(lensStyle(Lens.FAMILY).lightest)

    private fun readable(color: Long, background: Float) = LogoCheck.readable(LogoCheck.luminance(wordmark(color.toInt())), background)

    @Test
    fun `relative luminance is WCAG's, over the visible pixels only`() {
        assertEquals(1f, LogoCheck.luminance(intArrayOf(0xFFFFFFFF.toInt()))!!, 0.0001f)
        assertEquals(0.2159f, LogoCheck.luminance(intArrayOf(0xFF808080.toInt()))!!, 0.0001f)
        assertEquals(0f, LogoCheck.luminance(intArrayOf(0xFF000000.toInt()))!!, 0.0001f)
        assertEquals("pure red is its red coefficient", 0.2126f, LogoCheck.luminance(intArrayOf(0xFFFF0000.toInt()))!!, 0.0001f)
        assertEquals(
            "a faint white (alpha 127) doesn't count; a half-opaque one does",
            0.5f,
            LogoCheck.luminance(intArrayOf(0x7FFFFFFF, 0x80FFFFFF.toInt(), 0xFF000000.toInt()))!!,
            0.0001f,
        )
        assertNull("nothing visible", LogoCheck.luminance(intArrayOf(0x00FFFFFF, 0x10FFFFFF)))
        assertNull(LogoCheck.luminance(IntArray(0)))
    }

    @Test
    fun `the backgrounds' luminance`() {
        assertEquals("the hero's scrim, #080B0F", 0.0033f, hero, 0.0001f)
        assertEquals("For the family's light stop, #1E3F3A, the lightest lens", 0.0414f, family, 0.0001f)
        assertEquals(family, lenses.values.max())
    }

    @Test
    fun `a logo reads at 3 to 1 against its background, and an empty one never`() {
        assertTrue(LogoCheck.readable(0.1f, background = 0f))
        assertFalse(LogoCheck.readable(0.0999f, background = 0f))
        assertTrue(LogoCheck.readable(0.25f, background = 0.0333f))
        assertFalse(LogoCheck.readable(null, background = 0f))
    }

    @Test
    fun `a red logo shows on the hero, not on the lightest lens tint`() {
        assertTrue(readable(0xFFE50914, hero))
        assertFalse(readable(0xFFE50914, family))
    }

    @Test
    fun `white and amber logos show on every card`() {
        for ((card, background) in lenses + ("HERO" to hero)) {
            assertTrue("white on $card", readable(0xFFFFFFFF, background))
            assertTrue("amber on $card", readable(0xFFE5A00D, background))
        }
    }

    @Test
    fun `black, navy and deep blue logos show on no card`() {
        for ((card, background) in lenses + ("HERO" to hero)) {
            for ((name, color) in listOf("black" to 0xFF000000, "navy" to 0xFF14213D, "blue" to 0xFF1F3A93)) {
                assertFalse("$name on $card", readable(color, background))
            }
        }
    }

    @Test
    fun `a soft dark shadow doesn't sink a white logo, and a blank one keeps the title`() {
        val shadowed = wordmark(0xFFFFFFFF.toInt(), shadow = 0x60000000)
        assertEquals("only where the edges blend over it", 1f, LogoCheck.luminance(shadowed)!!, 0.05f)
        assertTrue(LogoCheck.readable(LogoCheck.luminance(shadowed), family))
        assertFalse("nothing to see", LogoCheck.readable(LogoCheck.luminance(Bitmap.createBitmap(200, 50, Bitmap.Config.ARGB_8888)), hero))
    }

    @Test
    fun `a mostly navy logo with a thin white outline is too dark for the lightest lens tint`() {
        val bitmap = wordmark(0xFF14213D.toInt(), outline = 0xFFFFFFFF.toInt())
        assertFalse("luminance ${LogoCheck.luminance(bitmap)}", LogoCheck.readable(LogoCheck.luminance(bitmap), family))
    }

    /** A made-up wordmark in [color] on transparency, optionally with a soft [shadow] behind or a thin [outline] around it. */
    private fun wordmark(color: Int, shadow: Int? = null, outline: Int? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 72f
            typeface = Typeface.DEFAULT_BOLD
        }
        if (shadow != null) canvas.drawText("MADE UP", 14f, 84f, Paint(paint).apply { this.color = shadow })
        if (outline != null) {
            canvas.drawText("MADE UP", 10f, 80f, Paint(paint).apply { this.color = outline; style = Paint.Style.STROKE; strokeWidth = 2f })
        }
        canvas.drawText("MADE UP", 10f, 80f, paint.apply { this.color = color })
        return bitmap
    }
}
