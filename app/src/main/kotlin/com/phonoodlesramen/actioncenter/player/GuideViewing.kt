package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.plex.cache.Finished
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets

/**
 * Guide viewing in Plex (Phase 11, amending G6): when a program counts as watched (K1, K2),
 * where a branch into Plezy or Plex starts, and when the credits card shows (K5). Pure, so
 * it's unit-tested; [GuideTuner] keeps the time and the positions.
 *
 * - **Watched point:** the program's credits (Plex's first credits marker), or 90% of its
 *   length without one, as Plex's own default puts it.
 * - **Counts (K2):** playback has reached the watched point, and the program's frames have
 *   played for at least ¾ of its length this sitting. Paused or skipped-over time adds
 *   nothing; behind live counts. Catching only the end counts for nothing.
 */
object GuideViewing {
    /** Plex's default "Video played threshold". */
    const val PLAYED_FRACTION = Finished.PLAYED_FRACTION

    /** K2: the share of a program's length its frames must have played. */
    const val WATCHED_SHARE = 0.75

    /** A branch this early writes no resume point: the server ignores one under a minute (`minimumProgressTime`). */
    const val MIN_RESUME_MILLIS = 60_000L

    /** Without a credits marker, the credits card shows for an episode's last minute. */
    const val CARD_BEFORE_END_MILLIS = 60_000L

    /** The credits card goes by itself after this long (K5). */
    const val CARD_MILLIS = 15_000L

    /** How often the player adds up what played and checks the watched point. */
    const val TICK_MILLIS = 5_000L

    /** A program's credits marker may take this long to come from Plex; without an answer by then, it has none. */
    const val CREDITS_TIMEOUT_MILLIS = 5_000L

    /** A branch waits this long, at most, for Plex to take its write (Phase 11 plan, 2). */
    const val WRITE_TIMEOUT_MILLIS = 2_000L

    /** Where a program [durationMs] long counts as watched: its credits marker, else 90% of it. */
    fun watchedPoint(durationMs: Long, creditsMs: Long?): Long =
        creditsMs?.takeIf { it in 1 until durationMs } ?: (durationMs * PLAYED_FRACTION).toLong()

    /** K2: the picture at [positionMs] has reached the watched point, and [watchedMs] of the program's frames have played. */
    fun counts(positionMs: Long, watchedMs: Long, durationMs: Long, creditsMs: Long?): Boolean =
        durationMs > 0 && positionMs >= watchedPoint(durationMs, creditsMs) && watchedMs >= durationMs * WATCHED_SHARE

    /** K5: the picture is in an episode's credits: from its marker, else its last minute, to its end. */
    fun inCredits(positionMs: Long, durationMs: Long, creditsMs: Long?): Boolean {
        if (durationMs <= 0) return false
        val from = creditsMs?.takeIf { it in 1 until durationMs } ?: (durationMs - CARD_BEFORE_END_MILLIS).coerceAtLeast(0)
        return positionMs in from until durationMs
    }
}

/** Where the picture is in the program on screen when a viewer branches (Phase 11): its position, and its watched point. */
data class BranchPoint(val positionMs: Long, val watchedAtMs: Long)

/** What a branch writes to Plex before the hand-off (Phase 11 plan, 2), by the frame on screen. */
sealed interface ResumeWrite {
    /** Under a minute in (or not the program on screen): nothing; Plezy starts where Plex had it. */
    data object Nothing : ResumeWrite

    /** Before the watched point: Plex's resume point, where Plezy starts. */
    data class Position(val positionMs: Long) : ResumeWrite

    /** Past the watched point: watched, so the title opens from the start. */
    data object Watched : ResumeWrite

    companion object {
        fun of(point: BranchPoint?): ResumeWrite = when {
            point == null || point.positionMs < GuideViewing.MIN_RESUME_MILLIS -> Nothing
            point.positionMs >= point.watchedAtMs -> Watched
            else -> Position(point.positionMs)
        }
    }
}

/** The show's next episode (K5): what the credits card offers. [label] is "S2 E5". */
data class NextEpisode(val ratingKey: String, val label: String, val title: String, val art: String?)

/** The credits card (K5): over [program]'s credits, [next] to hand off. */
data class CreditsCard(val program: GuideSlot, val next: NextEpisode)

/**
 * A guide episode's next one, from the Plex cache (K5): the same show, the next by season
 * and episode ([Finished.nextEpisode]: specials left out). None after the last, for a film,
 * or for a title the cache doesn't know.
 */
class NextEpisodes(
    private val item: suspend (ratingKey: String) -> ItemEntity?,
    private val episodesOf: suspend (showKey: String) -> List<ItemEntity>,
) {
    /**
     * The episode after [ratingKey]. With a kid's [ceiling] (the brief's local rating
     * filter, K4), none when it's above it, by the kid filters' own check
     * ([AgeBrackets.allows]): an episode without a rating takes its show's, and one with
     * neither isn't allowed. Null [ceiling] is an adult's: no limit.
     */
    suspend fun after(ratingKey: String, ceiling: Set<String>? = null): NextEpisode? {
        val current = item(ratingKey)?.takeIf { it.type == "episode" } ?: return null
        val show = current.showKey ?: return null
        val next = Finished.nextEpisode(current.season, current.episode, episodesOf(show)) ?: return null
        if (ceiling != null && !AgeBrackets.allows(ceiling, next.contentRating, item(show)?.contentRating)) return null
        return NextEpisode(next.ratingKey, "S${next.season} E${next.episode}", next.title, next.thumb ?: next.art)
    }
}
