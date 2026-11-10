package com.phonoodlesramen.actioncenter.guide

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The Plex Guide's lineup as the home sees it: the Live row, the Row B card and the full
 * guide all read this. The guide lives in the launcher (G0), so there's no pairing and no
 * server to be offline: the lineup is either still being made or ready.
 */
sealed interface GuideCardState {
    /** No channels yet: the library hasn't synced (Plex may not be linked; the home knows). */
    data object NotSetUp : GuideCardState

    /** The library has synced and the lineup is being made: the first time, or after a remake (2.7). */
    data object Building : GuideCardState

    /** The library has synced, but nothing in it fills a day of any channel (2.7). */
    data object NothingToFill : GuideCardState

    /** Every channel is turned off, or was deleted, in Settings › Plex Guide or on the phone (2.7). */
    data object NoneOn : GuideCardState

    /**
     * A kid's view of a lineup with no channel inside their ceiling (K8): there are channels,
     * just none for them ([KidGuide]).
     */
    data object NoneForKids : GuideCardState

    /** The lineup in order, each channel with its slots from now to 24 hours ahead. "Now" is picked on the minute tick. */
    data class Ready(
        val channels: List<GuideChannel>,
        /** When the schedule was last extended (millis). */
        val updatedAtMillis: Long?,
    ) : GuideCardState
}

/**
 * A channel and its schedule: [slots] are sorted by start and never overlap (filler is a slot
 * of its own). [contentRatings] and [unrated] are what it can play (R2): a kid sees the
 * channel only when it can play something, every rating is inside their ceiling and nothing
 * is unrated ([KidGuide]).
 */
data class GuideChannel(
    val id: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val slots: List<GuideSlot>,
    /**
     * The ratings of every title its rule can play (an episode without one has its show's),
     * and of each program still to finish in its schedule. Empty when it can play nothing.
     */
    val contentRatings: Set<String> = emptySet(),
    /** Whether something it can play has no rating (K4: kids never see it). */
    val unrated: Boolean = false,
) {
    /** What's on at [millis], or null in a gap or past the end of the schedule. */
    fun slotAt(millis: Long): GuideSlot? = slots.firstOrNull { millis >= it.startMillis && millis < it.endMillis }

    /** The next [count] slots that start after [millis]. */
    fun upNext(millis: Long, count: Int): List<GuideSlot> = slots.asSequence().filter { it.startMillis > millis }.take(count).toList()
}

/**
 * One scheduled block. Plex keys only, never a token or URL. Filler (G3) is a trailer when
 * [filler] has an [itemKey] (the extra's ratingKey), else an "Up next" card.
 */
data class GuideSlot(
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val episode: String?,
    val itemKey: String?,
    val showKey: String?,
    val art: String?,
    val contentRating: String?,
    val filler: Boolean,
    /** Why it can't play, e.g. off air. */
    val offAir: String?,
    /** Plex's description, for the full guide's info panel. */
    val summary: String? = null,
) {
    /** How far through the slot [millis] is, from 0 to 1. */
    fun progressAt(millis: Long): Float =
        if (endMillis <= startMillis) 1f else ((millis - startMillis).toFloat() / (endMillis - startMillis)).coerceIn(0f, 1f)

    /** Whole minutes left at [millis], rounded up, so a slot on its last seconds shows "1 min". */
    fun minutesLeftAt(millis: Long): Int = ((endMillis - millis + 59_999) / 60_000).toInt().coerceAtLeast(0)

    /** Where tuning in at [millis] joins the slot's item, from its start. */
    fun offsetAt(millis: Long): Long = (millis - startMillis).coerceIn(0, endMillis - startMillis)
}

/** The seam the Plex Guide plugs into (package E builds it in Phase 2.6). Read from the cache only. */
interface GuideSource {
    val card: Flow<GuideCardState>

    companion object None : GuideSource {
        override val card: Flow<GuideCardState> = flowOf(GuideCardState.NotSetUp)
    }
}
