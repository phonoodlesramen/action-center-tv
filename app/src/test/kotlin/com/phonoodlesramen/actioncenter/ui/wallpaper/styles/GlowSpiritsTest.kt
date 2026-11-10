package com.phonoodlesramen.actioncenter.ui.wallpaper.styles

import androidx.compose.ui.geometry.Size
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On the Shield (10/27/2026) the orbs looked still: at 4-8 px/s a soft glow the size of
 * half the screen doesn't read as moving. They must drift visibly but calmly.
 */
class GlowSpiritsTest {
    private val screen = Size(1920f, 1080f)

    /** Mean speed over a minute at the default Speed setting (rate 1), in px/s on a 1080p screen. */
    private fun GlowSpirits.Orb.meanSpeed(): Float {
        val step = 0.5f
        var travelled = 0f
        var previous = center(GlowSpirits.STILL_SECONDS, screen)
        var t = GlowSpirits.STILL_SECONDS + step
        while (t <= GlowSpirits.STILL_SECONDS + 60f) {
            val now = center(t, screen)
            travelled += (now - previous).getDistance()
            previous = now
            t += step
        }
        return travelled / 60f
    }

    @Test
    fun `every orb drifts visibly but calmly`() {
        GlowSpirits.orbs.forEachIndexed { i, orb ->
            val speed = orb.meanSpeed()
            assertTrue("orb $i drifts at $speed px/s", speed in 20f..80f)
        }
    }

    @Test
    fun `the drift loops seamlessly when wallpaper time wraps after an hour`() {
        GlowSpirits.orbs.forEachIndexed { i, orb ->
            val start = orb.center(0f, screen)
            val wrapped = orb.center(Motion.PERIOD_SECONDS, screen)
            assertEquals("orb $i x", start.x, wrapped.x, 0.5f)
            assertEquals("orb $i y", start.y, wrapped.y, 0.5f)
        }
    }
}
