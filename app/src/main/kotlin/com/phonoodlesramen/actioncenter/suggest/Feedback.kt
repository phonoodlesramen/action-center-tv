package com.phonoodlesramen.actioncenter.suggest

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How long feedback hides a title (S1, S10), and Not tonight's second strike. Pure. */
object FeedbackRules {
    private const val DAY_MILLIS = 24 * 60 * 60_000L
    const val NOT_TONIGHT_MILLIS = 14 * DAY_MILLIS

    /** A second Not tonight within 90 days skips the title for 6 months. */
    const val SECOND_STRIKE_WITHIN_MILLIS = 90 * DAY_MILLIS
    const val SECOND_STRIKE_MILLIS = 182 * DAY_MILLIS
    const val QUICK_WATCH_MILLIS = 30 * DAY_MILLIS
    const val LENS_MILLIS = 182 * DAY_MILLIS

    /** A title passed over on the lens card rests this long (L5). */
    const val REST_MILLIS = 30 * DAY_MILLIS

    /** What [kind] leaves for the title, given the same title's [previous] row of that kind, if any. */
    fun record(
        previous: FeedbackEntity?,
        profile: String,
        ratingKey: String,
        kind: FeedbackKind,
        scope: String,
        now: Long,
    ): FeedbackEntity {
        val strike = kind == FeedbackKind.NOT_TONIGHT && previous != null && now - previous.atMillis <= SECOND_STRIKE_WITHIN_MILLIS
        val until = when (kind) {
            FeedbackKind.NOT_TONIGHT -> now + if (strike) SECOND_STRIKE_MILLIS else NOT_TONIGHT_MILLIS
            // Until you play it again: [hides] lifts it then.
            FeedbackKind.UP_NEXT_REMOVED -> Long.MAX_VALUE
            FeedbackKind.QUICK_WATCH_HIDDEN -> now + QUICK_WATCH_MILLIS
            FeedbackKind.LENS_HIDDEN -> now + LENS_MILLIS
            FeedbackKind.LENS_RESTED -> now + REST_MILLIS
        }
        return FeedbackEntity(profile, ratingKey, kind, scope, now, count = if (strike) previous!!.count + 1 else 1, untilMillis = until)
    }

    /**
     * What's hidden at [now]. [lastViewedSeconds] is when a title (a movie, or any of a
     * show's episodes) was last watched: playing it again lifts Remove from Up Next.
     */
    fun hides(feedback: List<FeedbackEntity>, now: Long, lastViewedSeconds: (String) -> Long?): Hides {
        val active = feedback.filter { row ->
            row.untilMillis > now &&
                (row.kind != FeedbackKind.UP_NEXT_REMOVED || (lastViewedSeconds(row.ratingKey) ?: 0) * 1_000 <= row.atMillis)
        }
        fun of(kind: FeedbackKind) = active.filter { it.kind == kind }
        return Hides(
            notTonight = of(FeedbackKind.NOT_TONIGHT).mapTo(mutableSetOf()) { it.ratingKey },
            upNext = of(FeedbackKind.UP_NEXT_REMOVED).mapTo(mutableSetOf()) { it.ratingKey },
            quickWatch = of(FeedbackKind.QUICK_WATCH_HIDDEN).mapTo(mutableSetOf()) { it.ratingKey },
            lens = of(FeedbackKind.LENS_HIDDEN).groupBy({ it.scope }, { it.ratingKey }).mapValues { it.value.toSet() },
            rested = of(FeedbackKind.LENS_RESTED).associate { it.ratingKey to it.atMillis },
        )
    }
}

/**
 * Titles feedback hides right now. Not tonight keeps a title off the hero and the lens
 * card (S1); the others hide it from one card; [lens] is by the lens's name. [rested] (L5)
 * keeps a title off every lens, by when it started resting, unless a lens has nothing else.
 */
data class Hides(
    val notTonight: Set<String> = emptySet(),
    val upNext: Set<String> = emptySet(),
    val quickWatch: Set<String> = emptySet(),
    val lens: Map<String, Set<String>> = emptyMap(),
    val rested: Map<String, Long> = emptyMap(),
)

/** How to take one piece of feedback back (Undo, U12): the row it replaced, or none. */
data class FeedbackUndo(
    val profile: String,
    val ratingKey: String,
    val kind: FeedbackKind,
    val scope: String,
    val previous: FeedbackEntity?,
)

/** Feedback in `suggestions.db`, by profile (the owner until Phase 5). */
class FeedbackStore(
    private val dao: SuggestionDao,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [ratingKey] was on the lens card today (L5); the store keeps one row a day. */
    suspend fun shown(ratingKey: String, profile: String = Profiles.OWNER) = dao.saveShown(LensShownEntity(profile, ratingKey, today().toString()))

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()

    suspend fun record(ratingKey: String, kind: FeedbackKind, scope: String = "", profile: String = Profiles.OWNER): FeedbackUndo {
        val previous = dao.feedback(profile, ratingKey, kind, scope)
        dao.saveFeedback(FeedbackRules.record(previous, profile, ratingKey, kind, scope, clock()))
        return FeedbackUndo(profile, ratingKey, kind, scope, previous)
    }

    suspend fun undo(undo: FeedbackUndo) {
        val previous = undo.previous
        if (previous != null) dao.saveFeedback(previous) else dao.deleteFeedback(undo.profile, undo.ratingKey, undo.kind, undo.scope)
    }

    suspend fun all(profile: String = Profiles.OWNER): List<FeedbackEntity> = dao.feedback(profile)

    /** Rows that hide nothing and were given over a year ago: their signals have faded. Days on the lens card past any count. */
    suspend fun prune() {
        dao.pruneFeedback(clock() - PRUNE_AFTER_MILLIS)
        dao.pruneShown(today().minusDays(LensFreshness.KEEP_DAYS).toString())
    }

    private companion object {
        const val PRUNE_AFTER_MILLIS = 365 * 24 * 60 * 60_000L
    }
}
