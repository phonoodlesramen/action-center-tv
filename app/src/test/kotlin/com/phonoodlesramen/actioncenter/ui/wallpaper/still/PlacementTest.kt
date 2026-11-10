package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.phonoodlesramen.actioncenter.ui.wallpaper.styles.YourImages
import com.phonoodlesramen.actioncenter.wallpaper.ImageFit
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementTest {
    private val screen = Size(1920f, 1080f)

    private fun Rect.covers(size: Size) = left <= 0.01f && top <= 0.01f && right >= size.width - 0.01f && bottom >= size.height - 0.01f

    @Test
    fun fillCoversTheScreenAndFitShowsTheWholePicture() {
        val square = placement(5120f, 5120f, screen, ImageFit.FILL)
        assertTrue(square.covers(screen))
        assertEquals(1920f, square.width, 0.01f)
        val whole = placement(5120f, 5120f, screen, ImageFit.FIT)
        assertEquals(Rect(420f, 0f, 1500f, 1080f), whole)
    }

    @Test
    fun theSlowPanNeverUncoversAnEdge() {
        val drifts = (0 until 16).map { StillFrames.driftFor("picture $it.png") }
        for (drift in drifts) {
            for (step in 0..20) {
                val place = placement(1920f, 1080f, screen, ImageFit.FILL, pan = step / 20f, drift = drift)
                assertTrue("$drift at $step: $place", place.covers(screen))
            }
        }
        // At its widest it's zoomed in by PAN_ZOOM.
        assertEquals(1920f * (1 + PAN_ZOOM), placement(1920f, 1080f, screen, ImageFit.FILL, 1f, Offset(1f, 0f)).width, 0.1f)
    }

    @Test
    fun thePanEasesOutAndBack() {
        assertEquals(0f, panAt(0.0), 0.0001f)
        assertEquals(1f, panAt(PAN_PERIOD_SECONDS / 2.0), 0.0001f)
        assertEquals(0f, panAt(PAN_PERIOD_SECONDS.toDouble()), 0.0001f)
    }

    @Test
    fun theSlideshowMovesOnWithTheClock() {
        val nine = LocalDateTime.of(2026, 10, 26, 21, 0)
        val slot = YourImages.slideshowSlot(nine, 5)
        assertEquals(slot, YourImages.slideshowSlot(nine.plusMinutes(4), 5))
        assertEquals(slot + 1, YourImages.slideshowSlot(nine.plusMinutes(5), 5))
        // A setting of 0 minutes can't divide by zero.
        assertTrue(YourImages.slideshowSlot(nine, 0) > 0)
    }

    @Test
    fun aChosenImageWinsWhileItsStillThere() {
        val files = listOf(File("a.png"), File("b.png"), File("c.png"))
        assertEquals(File("b.png"), YourImages.choose(files, "b.png", slot = 0))
        assertEquals(File("b.png"), YourImages.choose(files, null, slot = 4))
        assertEquals(File("a.png"), YourImages.choose(files, "gone.png", slot = 3))
        assertNull(YourImages.choose(emptyList(), null, slot = 3))
    }
}
