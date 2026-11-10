package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.content.Pick

/**
 * Which pick the hero shows, and the Undo window after "Not tonight" [U12]: the next
 * pick appears at once and the button turns into Undo for 8 seconds. It remembers the
 * pick by id, so when the engine re-ranks after the feedback (S1) the hero stays put.
 */
data class PickState(val shownId: String? = null, val undo: Undo? = null) {
    data class Undo(val skippedTitle: String, val untilMillis: Long, val previousId: String)

    /** The shown pick, or the first when it's gone from the list. */
    fun current(picks: List<Pick>): Pick? = picks.firstOrNull { it.id == shownId } ?: picks.firstOrNull()

    fun notTonight(picks: List<Pick>, nowMillis: Long): PickState {
        val skipped = current(picks) ?: return this
        val next = picks[(picks.indexOf(skipped) + 1) % picks.size]
        return PickState(shownId = next.id, undo = Undo(skipped.title, nowMillis + UNDO_WINDOW_MILLIS, previousId = skipped.id))
    }

    fun undo(nowMillis: Long): PickState {
        val window = undo ?: return this
        return if (nowMillis < window.untilMillis) PickState(shownId = window.previousId) else copy(undo = null)
    }

    fun expire(nowMillis: Long): PickState =
        if (undo != null && nowMillis >= undo.untilMillis) copy(undo = null) else this

    companion object {
        const val UNDO_WINDOW_MILLIS = 8_000L
    }
}
