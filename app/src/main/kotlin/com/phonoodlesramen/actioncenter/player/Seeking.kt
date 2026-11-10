package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideSlot

/**
 * Where ⏪ and ⏩ land in the program on screen (P1), apart from the player so they're
 * unit-tested. Positions are in the program's file, from its start.
 *
 * - **A tap** goes back [BACK_MILLIS] or forward [FORWARD_MILLIS].
 * - **Holding** moves the banner's marker, faster the longer the key is held: ×2 for the
 *   first [STEP_UP_MILLIS], ×4 for the next, then ×8 ([speedAt]). ×1 is [BASE_RATE_MILLIS]
 *   of the program for each second held, so ×2 is 30 s a second, ×4 a minute and ×8 two
 *   minutes. Nothing seeks until the key is let go.
 * - **Either way** it stays inside the program: back no further than its start, forward
 *   no further than live ([liveAt]), where it's Back to live.
 */
object Seeking {
    /** A tap of ⏪. */
    const val BACK_MILLIS = 10_000L

    /** A tap of ⏩. */
    const val FORWARD_MILLIS = 30_000L

    /** Held this long, the scrub steps up a speed. */
    const val STEP_UP_MILLIS = 2_000L

    /** How far ×1 would move the marker for each second held. */
    const val BASE_RATE_MILLIS = 15_000L

    /** The speeds a hold steps through, slowest first. */
    val SPEEDS = listOf(2, 4, 8)

    /** The live point in [slot] at [nowMillis]: as far as the schedule has got, its end once it's over. */
    fun liveAt(slot: GuideSlot, nowMillis: Long): Long = slot.offsetAt(nowMillis)

    /** Where a tap from [fromMs] lands: back 10 s or forward 30 s, between the program's start and [liveMs]. */
    fun skip(fromMs: Long, forward: Boolean, liveMs: Long): Long =
        (if (forward) fromMs + FORWARD_MILLIS else fromMs - BACK_MILLIS).coerceIn(0, liveMs.coerceAtLeast(0))

    /** The scrub's speed once the key has been held [heldMillis]: ×2, ×4, then ×8. */
    fun speedAt(heldMillis: Long): Int = SPEEDS[(heldMillis.coerceAtLeast(0) / STEP_UP_MILLIS).toInt().coerceAtMost(SPEEDS.lastIndex)]

    /** How far the marker has moved after [heldMillis], each stretch of the hold at its own speed. */
    fun distance(heldMillis: Long): Long {
        var left = heldMillis.coerceAtLeast(0)
        var moved = 0L
        SPEEDS.forEachIndexed { index, speed ->
            val stretch = if (index == SPEEDS.lastIndex) left else minOf(left, STEP_UP_MILLIS)
            moved += stretch * speed * BASE_RATE_MILLIS / 1_000
            left -= stretch
        }
        return moved
    }

    /** Where the marker is after holding for [heldMillis] from [fromMs], between the program's start and [liveMs]. */
    fun scrubTo(fromMs: Long, forward: Boolean, heldMillis: Long, liveMs: Long): Long {
        val moved = distance(heldMillis)
        return (if (forward) fromMs + moved else fromMs - moved).coerceIn(0, liveMs.coerceAtLeast(0))
    }
}
