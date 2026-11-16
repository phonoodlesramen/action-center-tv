package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideSlot

/**
 * Watching a channel's program behind live (G11): started over from its start, or paused.
 * The picture's place in the program is kept as a position at a wall-clock time, so it
 * moves on with the clock while playing and stands still while paused; how far behind
 * live it is follows from that.
 *
 * When the program ends at this pace ([endsAtMillis]), or its file does, the channel
 * rejoins live: whatever's on now (G11). Pure, so it's unit-tested.
 */
data class BehindLive(
    /** The program being watched. */
    val slot: GuideSlot,
    /** Where the picture was in the program's file at [atMillis]. */
    val positionMs: Long,
    val atMillis: Long,
    val paused: Boolean,
    /** Started over from the program's start, rather than paused from live: the banner says which. */
    val startedOver: Boolean,
    /** Picked up where you left it (P5): the banner says so. */
    val pickedUp: Boolean = false,
) {
    private val lengthMs: Long get() = (slot.endMillis - slot.startMillis).coerceAtLeast(0)

    /** Where the picture is in the program at [nowMillis]. */
    fun positionAt(nowMillis: Long): Long = if (paused) positionMs else positionMs + (nowMillis - atMillis).coerceAtLeast(0)

    /** How far behind live the picture is at [nowMillis]: the schedule's place in the program, less the picture's. */
    fun behindAt(nowMillis: Long): Long = (nowMillis - slot.startMillis - positionAt(nowMillis)).coerceAtLeast(0)

    /** When the program ends at this pace and the channel rejoins live; null while paused, as it never does. */
    val endsAtMillis: Long? get() = if (paused) null else atMillis + (lengthMs - positionMs).coerceAtLeast(0)

    /** When the channel rejoins live: [endsAtMillis], or while paused, if it played on from [nowMillis]. */
    fun rejoinsLiveAt(nowMillis: Long): Long = endsAtMillis ?: (nowMillis + (lengthMs - positionMs).coerceAtLeast(0))

    /** How far through the program the picture is, from 0 to 1, for the banner's bar. */
    fun progressAt(nowMillis: Long): Float = if (lengthMs == 0L) 1f else (positionAt(nowMillis).toFloat() / lengthMs).coerceIn(0f, 1f)

    /** Whole minutes of the program left from the picture, rounded up, as the banner's "41m left". */
    fun minutesLeftAt(nowMillis: Long): Int = ((lengthMs - positionAt(nowMillis) + MINUTE_MILLIS - 1) / MINUTE_MILLIS).toInt().coerceAtLeast(0)

    /** Paused at [nowMillis]; [positionMs] is the player's own position when it knows it. */
    fun pausedAt(nowMillis: Long, positionMs: Long? = null): BehindLive =
        copy(positionMs = positionMs ?: positionAt(nowMillis), atMillis = nowMillis, paused = true)

    fun resumedAt(nowMillis: Long): BehindLive = if (!paused) this else copy(atMillis = nowMillis, paused = false)

    companion object {
        private const val MINUTE_MILLIS = 60_000L

        /** [slot] from its start, playing from [nowMillis]. */
        fun startedOver(slot: GuideSlot, nowMillis: Long) = BehindLive(slot, 0, nowMillis, paused = false, startedOver = true)

        /** Paused at [nowMillis] while live: the picture is where the schedule is, or where the player says. */
        fun pausedLive(slot: GuideSlot, nowMillis: Long, positionMs: Long? = null) =
            BehindLive(slot, positionMs ?: slot.offsetAt(nowMillis), nowMillis, paused = true, startedOver = false)
    }
}
