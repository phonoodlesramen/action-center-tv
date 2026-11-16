package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideSlot

/**
 * Keeping your place in the guide (P5). What decides whether a program you leave is kept is
 * how much you were watching it, not which button you left by: surfing past leaves nothing,
 * and a program you were watching is kept whether you left by Home, Back, a channel change or
 * the mini guide. Pure, so it's unit-tested; [GuideTuner] keeps the time and the places.
 *
 * - **Watching:** its frames have played [WATCHING_MILLIS] this sitting, or you chose it: you
 *   started it over, paused it, skipped in it, or picked it up.
 * - **Coming back:** on its channel again within [QUICK_RETURN_MILLIS] in the same sitting (a
 *   slip of the thumb on Channel up), it carries on where it was, no question asked; later,
 *   or after the player has closed, the pick-up card offers it while the channel tunes in live.
 * - **Plex:** leaving a program you've seen from (about) its start tells Plex where you left
 *   it ([leftOff]), so Up Next, Plezy and every Plex app can carry on once the guide has moved on.
 */
object PlaceRules {
    /** A program's frames have played this long this sitting: you were watching it, not surfing past. */
    const val WATCHING_MILLIS = 3 * 60_000L

    /** Back on its channel this soon after leaving, in the same sitting: straight back to where it was. */
    const val QUICK_RETURN_MILLIS = 2 * 60_000L

    /** The pick-up card goes by itself after this long. */
    const val CARD_MILLIS = 15_000L

    /** Seen from its start, give or take this much: worth a resume point in Plex. */
    const val FROM_THE_START_MILLIS = 5 * 60_000L

    /** Less behind live than this, there's nothing to pick up: live is where you left it. */
    const val MISSED_MILLIS = 30_000L

    /** You were watching: [watchedMs] of its frames played this sitting, or you [chose] it. */
    fun watching(watchedMs: Long, chose: Boolean): Boolean = chose || watchedMs >= WATCHING_MILLIS

    /** A place at [positionMs] has something to pick up: [liveMs], where live (or the program's end) is now, is far enough on. */
    fun missed(positionMs: Long, liveMs: Long): Boolean = liveMs - positionMs >= MISSED_MILLIS

    /** Back on the channel within [QUICK_RETURN_MILLIS] of leaving it. A clock that went back counts as now. */
    fun quickReturn(leftAtMillis: Long, nowMillis: Long): Boolean = nowMillis - leftAtMillis <= QUICK_RETURN_MILLIS

    /**
     * Where leaving at [positionMs] puts Plex's resume point for the program, or null for
     * nothing: only when [watchedMs] of it has played, so you've seen it from about its start,
     * past the first minute (Plex ignores less) and short of [watchedAtMs], its watched point
     * (past that it counts as watched, or not at all). Joined midway, nothing: a resume point
     * there would start a film you never saw the beginning of.
     */
    fun leftOff(positionMs: Long, watchedMs: Long, watchedAtMs: Long): Long? = positionMs.takeIf {
        it >= GuideViewing.MIN_RESUME_MILLIS && it < watchedAtMs && watchedMs >= it - FROM_THE_START_MILLIS
    }
}

/**
 * A program you were watching and left (P5): where the picture was in its file, on
 * [channelId], and when you left. [room] names another TV it was left on (Phase 8, N6).
 */
data class Place(val channelId: String, val slot: GuideSlot, val positionMs: Long, val leftAtMillis: Long, val room: String? = null)

/** The pick-up card (P5): you left [place] on the channel on screen, and OK carries on from there. */
data class PickUpCard(val place: Place)
