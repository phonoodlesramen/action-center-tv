package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import kotlin.math.PI
import kotlin.math.sin

/**
 * Screen care for text that stays put on an OLED TV (Now showing's clock, title and
 * weather): it moves a few dp every minute along a slow loop, so no pixel lights the same
 * way for long. The Shield's own screensaver still takes over later.
 */
object ScreenCare {
    /** How far text may wander from its place, in dp. Its padding leaves this much room inside the safe area. */
    const val MAX_X_DP = 8f
    const val MAX_Y_DP = 6f

    /**
     * The offset (dp) for [minuteOfDay]: a new spot every minute, the two axes at
     * different periods (11 and 7 minutes), so the path takes 77 minutes to repeat.
     */
    fun drift(minuteOfDay: Int): Pair<Float, Float> {
        val x = MAX_X_DP * sin(2 * PI * minuteOfDay / X_PERIOD).toFloat()
        val y = MAX_Y_DP * sin(2 * PI * minuteOfDay / Y_PERIOD).toFloat()
        return x to y
    }

    private const val X_PERIOD = 11.0
    private const val Y_PERIOD = 7.0
}
