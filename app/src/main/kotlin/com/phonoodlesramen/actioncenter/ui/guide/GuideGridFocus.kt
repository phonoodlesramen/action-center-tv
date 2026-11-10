package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.runtime.Immutable
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** The D-pad's four ways through the grid. */
internal enum class GuideMove { UP, DOWN, LEFT, RIGHT }

/**
 * Where the full guide is: one 90-minute window shared by every row, the focused program,
 * and the time Up and Down keep. The grid draws only this window and [GuideLineup.rows]
 * channels from [topRow], so focus moves by time rather than by column.
 */
@Immutable
internal data class GuideFocus(
    /** The focused channel; null when nothing in the window can take focus. */
    val channelId: String?,
    /** The focused program's start: a channel's slots never overlap, so it names the slot. */
    val slotStart: Long,
    /** The window's first minute, always on the half hour. */
    val windowStart: Long,
    /**
     * Up and Down land on what's on at this time: where the focused program starts in the
     * window after Left or Right, else the time the guide opened at. It stays put through
     * Up and Down, so a long film on the way doesn't drag the column sideways.
     */
    val anchor: Long,
    /** The first channel drawn. */
    val topRow: Int,
)

/**
 * The lineup as the full guide moves through it at [nowMillis]. Pure, so the paging and
 * focus-by-time rules are tested without Compose.
 *
 * - **Up/Down** keep [GuideFocus.anchor]: the next row's program on at that time, else its
 *   nearest program in the window. A row with nothing in the window is skipped. The rows
 *   scroll one channel at a time past the top or bottom.
 * - **Right** moves to the channel's next program; past the window's end, the window pages
 *   forward so that program starts at its left. Never past the half hour after now + 24 h.
 * - **Left** moves to the previous program; before the window's start, the window pages back
 *   so that program ends at its right. Never before the earliest slot, nor more than 2 hours
 *   before now.
 * - At either end of a channel's schedule, Left or Right still pages to show the rest of the
 *   program that's cut off.
 */
internal class GuideLineup(
    val channels: List<GuideChannel>,
    private val nowMillis: Long,
    private val zone: ZoneId,
    val rows: Int = ROWS,
) {
    /** The earliest window: the earliest slot, but not more than [PAST_MILLIS] back, and never after now's. */
    val firstWindow: Long = run {
        val earliest = channels.mapNotNull { it.slots.firstOrNull()?.startMillis }.minOrNull() ?: nowMillis
        max(halfHourFloor(earliest), halfHourFloor(nowMillis - PAST_MILLIS)).coerceAtMost(halfHourFloor(nowMillis))
    }

    /** The last window ends on the half hour after now + 24 hours. */
    val lastWindow: Long = max(firstWindow, halfHourCeil(nowMillis + DAY_MILLIS) - WINDOW_MILLIS)

    private val maxTop get() = (channels.size - rows).coerceAtLeast(0)

    /** The programs with any minute in the window that starts at [windowStart], in order. */
    fun visible(channel: GuideChannel, windowStart: Long): List<GuideSlot> {
        val end = windowStart + WINDOW_MILLIS
        return channel.slots.filter { it.endMillis > it.startMillis && it.endMillis > windowStart && it.startMillis < end }
    }

    /** The guide opening at [atMillis] (now, normally), on [channelId] or the first channel. */
    fun open(atMillis: Long, channelId: String?): GuideFocus {
        val window = halfHourFloor(atMillis).coerceIn(firstWindow, lastWindow)
        val row = channels.indexOfFirst { it.id == channelId }.coerceAtLeast(0)
        return land(window, atMillis.coerceIn(window, window + WINDOW_MILLIS - 1), row, top = row.coerceAtMost(maxTop))
    }

    /** Where [move] goes from [focus]; [focus] itself at a limit. */
    fun move(focus: GuideFocus, move: GuideMove): GuideFocus {
        val row = rowOf(focus) ?: return focus
        val channel = channels[row]
        val index = channel.slots.indexOfFirst { it.startMillis == focus.slotStart }
        if (index < 0) return focus
        return when (move) {
            GuideMove.UP -> vertical(focus, row, -1)
            GuideMove.DOWN -> vertical(focus, row, 1)
            GuideMove.LEFT -> earlier(focus, channel, index)
            GuideMove.RIGHT -> later(focus, channel, index)
        }
    }

    /** The channel and program [focus] is on, or null. */
    fun focused(focus: GuideFocus): Pair<GuideChannel, GuideSlot>? {
        val channel = rowOf(focus)?.let(channels::get) ?: return null
        val slot = channel.slots.firstOrNull { it.startMillis == focus.slotStart } ?: return null
        return channel to slot
    }

    /**
     * [focus] checked against the lineup, which can change while the guide is open (the
     * schedule grows, a channel goes). A program that's gone hands focus to the same time on
     * the same row, or the nearest row with something on.
     */
    fun resolve(focus: GuideFocus): GuideFocus {
        val row = rowOf(focus)
        val slot = row?.let { visible(channels[it], focus.windowStart).firstOrNull { s -> s.startMillis == focus.slotStart } }
        if (row != null && slot != null) return focus.copy(topRow = scrolled(row, focus.topRow.coerceIn(0, maxTop)))
        return land(focus.windowStart, focus.anchor, row ?: 0, focus.topRow.coerceIn(0, maxTop))
    }

    private fun rowOf(focus: GuideFocus): Int? = channels.indexOfFirst { it.id == focus.channelId }.takeIf { it >= 0 }

    /** Focus on [row] (or the nearest row below, then above, with something in the window) at [anchor]. */
    private fun land(window: Long, anchor: Long, row: Int, top: Int): GuideFocus {
        val candidates = (row until channels.size) + (row - 1 downTo 0)
        for (candidate in candidates) {
            val slot = pick(channels[candidate], window, anchor) ?: continue
            return GuideFocus(channels[candidate].id, slot.startMillis, window, anchor, scrolled(candidate, top))
        }
        return GuideFocus(null, 0L, window, anchor, top)
    }

    private fun vertical(focus: GuideFocus, from: Int, step: Int): GuideFocus {
        var row = from + step
        while (row in channels.indices) {
            val slot = pick(channels[row], focus.windowStart, focus.anchor)
            if (slot != null) {
                return focus.copy(channelId = channels[row].id, slotStart = slot.startMillis, topRow = scrolled(row, focus.topRow))
            }
            row += step
        }
        return focus
    }

    private fun later(focus: GuideFocus, channel: GuideChannel, index: Int): GuideFocus {
        val next = channel.slots.drop(index + 1).firstOrNull { it.endMillis > it.startMillis }
        if (next == null) {
            // The schedule's last program: page on to show its end, as far as the 24 hours go.
            val current = channel.slots[index]
            if (current.endMillis <= focus.windowStart + WINDOW_MILLIS) return focus
            val window = (halfHourCeil(current.endMillis) - WINDOW_MILLIS).coerceIn(focus.windowStart, max(focus.windowStart, lastWindow))
            return focus.copy(windowStart = window, anchor = max(current.startMillis, window))
        }
        val window = if (next.startMillis < focus.windowStart + WINDOW_MILLIS) {
            focus.windowStart
        } else {
            max(focus.windowStart, halfHourFloor(next.startMillis).coerceAtMost(lastWindow))
        }
        // Past the last window: the end of the 24 hours.
        if (next.startMillis >= window + WINDOW_MILLIS) return focus
        return focus.copy(slotStart = next.startMillis, windowStart = window, anchor = max(next.startMillis, window))
    }

    private fun earlier(focus: GuideFocus, channel: GuideChannel, index: Int): GuideFocus {
        val previous = channel.slots.take(index).lastOrNull { it.endMillis > it.startMillis }
        if (previous == null) {
            // The schedule's first program: page back to show its start, as far as the guide goes.
            val current = channel.slots[index]
            if (current.startMillis >= focus.windowStart) return focus
            val window = halfHourFloor(current.startMillis).coerceIn(min(firstWindow, focus.windowStart), focus.windowStart)
            return focus.copy(windowStart = window, anchor = max(current.startMillis, window))
        }
        val window = if (previous.endMillis > focus.windowStart) {
            focus.windowStart
        } else {
            (halfHourCeil(previous.endMillis) - WINDOW_MILLIS).coerceAtLeast(firstWindow).coerceAtMost(focus.windowStart)
        }
        // Before the first window: the earliest the guide goes back.
        if (previous.endMillis <= window) return focus
        return focus.copy(slotStart = previous.startMillis, windowStart = window, anchor = max(previous.startMillis, window))
    }

    /** What's on at [anchor] in the window, else the nearest program in it; null if the row has nothing there. */
    private fun pick(channel: GuideChannel, window: Long, anchor: Long): GuideSlot? {
        val visible = visible(channel, window)
        return visible.firstOrNull { anchor >= it.startMillis && anchor < it.endMillis }
            ?: visible.minByOrNull { if (anchor < it.startMillis) it.startMillis - anchor else anchor - it.endMillis }
    }

    /** [top] moved just enough to show [row]. */
    private fun scrolled(row: Int, top: Int): Int = when {
        row < top -> row
        row >= top + rows -> row - rows + 1
        else -> top
    }.coerceIn(0, maxTop)

    /** The half hour at or before [millis] on the wall clock, so a 45-minute time zone still lines up. */
    private fun halfHourFloor(millis: Long): Long {
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(millis)).totalSeconds * 1_000L
        return Math.floorDiv(millis + offset, STEP_MILLIS) * STEP_MILLIS - offset
    }

    private fun halfHourCeil(millis: Long): Long = halfHourFloor(millis).let { if (it == millis) it else it + STEP_MILLIS }

    companion object {
        /** 90 minutes across at a time (plan §5). */
        const val WINDOW_MILLIS = 90 * 60_000L

        /** Windows start on the half hour, and the ruler marks every one. */
        const val STEP_MILLIS = 30 * 60_000L

        /** Six channels at a time. */
        const val ROWS = 6

        private const val PAST_MILLIS = 2 * 60 * 60_000L
        private const val DAY_MILLIS = 24 * 60 * 60_000L
    }
}
