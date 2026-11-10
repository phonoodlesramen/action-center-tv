package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot

/**
 * The guide player's timing rules (G5), apart from ExoPlayer and the screen so they're
 * unit-tested: where tuning in joins a program, when the next one is made ready and
 * switched to, and which channel Up and Down land on.
 */
object GuideTuning {
    /** The next slot's file is opened this long before the current slot ends, so the cut is quick. */
    const val PREPARE_AHEAD_MILLIS = 30_000L

    /** Up/Down move the channel at once; the file opens only once the presses stop for this long. */
    const val SURF_DEBOUNCE_MILLIS = 400L

    /** The banner hides after this long without OK or a channel change. */
    const val BANNER_MILLIS = 5_000L

    /** Tuning in this close to a slot's start plays its file from the top. */
    const val FROM_TOP_MILLIS = 2_000L

    /** A file with less than this left is finished: the Up next card shows instead. */
    const val END_MARGIN_MILLIS = 2_000L

    /** A file that ends closer than this to its slot's end keeps its last frame until the switch. */
    const val ENDED_EARLY_MILLIS = 5_000L

    /** The mini guide closes after this long without a key (G12). */
    const val MINI_GUIDE_MILLIS = 6_000L

    /** The options, a track list or the details close after this long without a key: the picture plays on under them (G10). */
    const val OVERLAY_IDLE_MILLIS = 120_000L

    /** How long a program's streams may take to come from Plex before its file plays without them (G13). */
    const val STREAMS_TIMEOUT_MILLIS = 1_500L

    /** A program Plex couldn't give (offline, or its file unavailable) is tried again this long after it failed. */
    const val RETRY_MILLIS = 30_000L

    /** A program can be started over (G11) when it has a file: not filler, and not off air. */
    fun canStartOver(slot: GuideSlot): Boolean = !slot.filler && slot.itemKey != null && slot.offAir == null

    /**
     * Where to open [slot]'s file at [nowMillis]: as far in as the slot has run, so a channel
     * joins its program midway, or from the top within [FROM_TOP_MILLIS] of the start. Null
     * when the file ([durationMs], if known) has nothing left to play, e.g. a trailer shorter
     * than its filler slot.
     */
    fun startPosition(slot: GuideSlot, nowMillis: Long, durationMs: Long?): Long? {
        val offset = slot.offsetAt(nowMillis).let { if (it < FROM_TOP_MILLIS) 0L else it }
        return if (durationMs != null && durationMs > 0 && offset >= durationMs - END_MARGIN_MILLIS) null else offset
    }

    /**
     * Whether a file that ended at [nowMillis] leaves enough of [slot] for the Up next card:
     * a trailer shorter than its filler does; a program whose file runs a second or two
     * shorter than Plex's length for it doesn't, so its last frame holds until the switch.
     */
    fun endedEarly(slot: GuideSlot, nowMillis: Long): Boolean = slot.endMillis - nowMillis > ENDED_EARLY_MILLIS

    /** When to make the slot after [slot] ready, and when to switch to it. */
    data class Handoff(val prepareAtMillis: Long, val switchAtMillis: Long)

    /**
     * The handoff from [slot] to the slot after it: prepared [PREPARE_AHEAD_MILLIS] before the
     * boundary (or now, when that's already passed) and switched to at the boundary itself,
     * however far the file has got, so the channel keeps to its schedule.
     */
    fun handoff(slot: GuideSlot, nowMillis: Long): Handoff =
        Handoff((slot.endMillis - PREPARE_AHEAD_MILLIS).coerceIn(nowMillis.coerceAtMost(slot.endMillis), slot.endMillis), slot.endMillis)

    /** The slot that starts as [slot] ends, or null at a gap or the end of the schedule. */
    fun following(channel: GuideChannel, slot: GuideSlot): GuideSlot? = channel.slots.firstOrNull { it.startMillis == slot.endMillis }

    /** The first program (not filler) that starts at or after [millis]: the banner's and cards' "next". */
    fun nextProgram(channel: GuideChannel, millis: Long): GuideSlot? = channel.slots.firstOrNull { it.startMillis >= millis && !it.filler }

    /** The program on at [millis], or the next one if filler or nothing is: what joining live then shows. */
    fun programAt(channel: GuideChannel, millis: Long): GuideSlot? = channel.slots.firstOrNull { it.endMillis > millis && !it.filler }

    /**
     * The channel [step] places from [currentId] in lineup order, wrapping at both ends
     * (Up is -1, Down +1). From a channel that's no longer in the lineup, the first one.
     */
    fun channelAfter(channels: List<GuideChannel>, currentId: String?, step: Int): GuideChannel? {
        if (channels.isEmpty()) return null
        val index = channels.indexOfFirst { it.id == currentId }
        if (index < 0) return channels.first()
        return channels[(index + step).mod(channels.size)]
    }

    /** The picture's width over its height, allowing for non-square pixels; null until the size is known. */
    fun aspect(width: Int, height: Int, pixelWidthHeightRatio: Float): Float? =
        if (width <= 0 || height <= 0) null else width * (if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f) / height
}
