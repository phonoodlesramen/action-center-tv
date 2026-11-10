package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.FrostedGlass
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.PosterWall
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Spec §6: primary text keeps 4.5:1 over every still wallpaper, and dim text through frosted glass. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegibilityTest {
    private val white = Color.White.toArgb()
    private val background = Palette.Background.toArgb()
    private val text = Palette.Text.luminance()
    private val dimText = Palette.TextDim.luminance()

    private fun textContrast(argb: Int) = Legibility.contrast(text, Legibility.luminance(argb))

    private fun dimContrast(argb: Int) = Legibility.contrast(dimText, Legibility.luminance(argb))

    @Test
    fun theLimitIsWherePrimaryTextMeetsFourAndAHalfToOne() {
        assertEquals(4.5f, Legibility.contrast(text, Legibility.Limit), 0.001f)
        assertEquals(4.5f, Legibility.contrast(dimText, Legibility.DimLimit), 0.001f)
        assertTrue(Legibility.luminance(Legibility.BrightestStill) <= Legibility.Limit)
        // Our luminance agrees with Compose's.
        assertEquals(Palette.Surface.luminance(), Legibility.luminance(Palette.Surface.toArgb()), 0.0005f)
        // Midnight itself is well inside it.
        assertTrue(dimContrast(background) > 7f)
    }

    @Test
    fun aWhitePictureGetsAScrimThatKeepsPrimaryTextReadable() {
        val scrim = Legibility.scrimFor(IntArray(48 * 27) { white }, 48, 27)
        assertTrue(textContrast(Legibility.blend(white, background, scrim.alpha)) >= 4.5f)
        // And no more than it needs.
        assertTrue(textContrast(Legibility.blend(white, background, scrim.alpha - 0.01f)) < 4.5f)
    }

    @Test
    fun aDarkPictureNeedsNoScrimButKeepsItsLeast() {
        val pixels = IntArray(48 * 27) { background }
        assertEquals(Scrim(0f), Legibility.scrimFor(pixels, 48, 27))
        assertEquals(Scrim(0.25f), Legibility.scrimFor(pixels, 48, 27, least = 0.25f))
    }

    @Test
    fun oneBrightCornerSetsTheScrim() {
        val pixels = IntArray(120 * 68) { if (it % 120 > 100 && it / 120 > 50) white else background }
        val scrim = Legibility.scrimFor(pixels, 120, 68)
        assertTrue(textContrast(Legibility.blend(white, background, scrim.alpha)) >= 4.5f)
    }

    @Test
    fun theAccentGradientKeepsPrimaryTextReadableInEveryAccent() {
        for (accent in Palette.Accents + Palette.Accents.map(Palette::lateNight)) {
            for (lateNight in listOf(false, true)) {
                val pixels = StillBitmaps.pixels(AccentGradient.render(accent, lateNight, width = 96, height = 54))
                val brightest = pixels.maxOf { Legibility.luminance(it) }
                assertTrue("$accent: $brightest", brightest <= Legibility.Limit)
                assertTrue("$accent shows some accent", brightest > Legibility.luminance(background) * 1.5f)
            }
        }
    }

    @Test
    fun theAccentGradientIsTheSameEveryTime() {
        val first = StillBitmaps.pixels(AccentGradient.render(Palette.DefaultAccent, false, width = 64, height = 36))
        val second = StillBitmaps.pixels(AccentGradient.render(Palette.DefaultAccent, false, width = 64, height = 36))
        assertArrayEquals(first, second)
    }

    @Test
    fun glassOverTheBrightestStillWallpaperKeepsDimTextReadable() {
        // The colours cards without art use: Quick Watch and the Plex card, the top bar's chip.
        for (card in listOf(Palette.Surface, Palette.Surface2)) {
            val glass = Legibility.blend(Legibility.BrightestStill, card.toArgb(), FrostedGlass.GLASS_TINT)
            assertTrue("$card: ${dimContrast(glass)}", dimContrast(glass) >= 4.5f)
        }
    }

    @Test
    fun aWhitePosterIsDrawnFaintAndADarkOneStronger() {
        val whiteAlpha = Legibility.strongestOverBackground(IntArray(120 * 180) { white }, PosterWall.MOST_ALPHA)
        assertTrue(textContrast(Legibility.blend(background, white, whiteAlpha)) >= 4.5f)
        assertTrue(whiteAlpha < PosterWall.MOST_ALPHA)
        val navy = 0xFF1A2440.toInt()
        assertEquals(PosterWall.MOST_ALPHA, Legibility.strongestOverBackground(IntArray(120 * 180) { navy }, PosterWall.MOST_ALPHA), 0.0001f)
    }
}
