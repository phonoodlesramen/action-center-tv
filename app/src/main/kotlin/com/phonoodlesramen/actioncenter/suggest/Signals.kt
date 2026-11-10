package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import kotlin.math.min
import kotlin.math.pow

enum class SignalKind { FINISHED, REWATCHED, RATED, ABANDONED, STALLED, NOT_TONIGHT, HIDDEN }

/** How a signal's weight fades with its age. */
sealed interface Fade {
    fun factor(ageMillis: Long): Double

    /** Half the weight after [millis], a quarter after twice that (S2). */
    data class HalfLife(val millis: Long) : Fade {
        override fun factor(ageMillis: Long) = 0.5.pow(ageMillis.coerceAtLeast(0).toDouble() / millis)
    }

    /** Gone after [millis] (S1's Not tonight). */
    data class Linear(val millis: Long) : Fade {
        override fun factor(ageMillis: Long) = (1.0 - ageMillis.coerceAtLeast(0).toDouble() / millis).coerceIn(0.0, 1.0)
    }
}

/** One thing you did that counts for or against a title: a movie or a show. Times are millis. */
data class Signal(val titleKey: String, val kind: SignalKind, val weight: Double, val atMillis: Long, val fade: Fade) {
    fun weightAt(now: Long): Double = weight * fade.factor(now - atMillis)
}

/** S1 and S2's weights and windows, in one place for tuning. */
object SignalWeights {
    private const val DAY_MILLIS = 24 * 60 * 60_000L
    const val FINISHED = 1.0
    const val REWATCHED = 0.5
    const val ABANDONED = -1.0
    const val STALLED = -0.5

    /** A third of an abandon, gone over 60 days (S1). */
    const val NOT_TONIGHT = -0.3
    const val NOT_TONIGHT_FADE_MILLIS = 60 * DAY_MILLIS

    /** Hidden from Quick Watch or a lens: a small minus (S10). */
    const val HIDDEN = -0.15
    const val HIDDEN_FADE_MILLIS = 60 * DAY_MILLIS

    /** Your rating: 4 stars or more counts like a rewatch, 2 or fewer like an abandon. */
    const val LOVED_RATING = 8.0
    const val RATED_LOVED = 0.5
    const val DISLIKED_RATING = 4.0
    const val RATED_DISLIKED = -1.0

    /** Everything else fades to half after 6 months (S2). */
    const val HALF_LIFE_MILLIS = 182 * DAY_MILLIS

    /** Abandoned: 10+ minutes in, unfinished, and untouched for 3 weeks (S2). */
    const val ABANDON_MIN_OFFSET_MILLIS = 10 * 60_000L
    const val ABANDON_AFTER_MILLIS = 21 * DAY_MILLIS

    /** A stopped show: last watched 2 months ago, with episodes left that were there then. */
    const val STALLED_AFTER_MILLIS = 60 * DAY_MILLIS

    /** A show counts as fully finished from this many finished episodes; fewer count in part. */
    const val SHOW_FULL_FINISH = 3
}

/**
 * Every signal in the library and your feedback (S1, S2). Plex logs only finishes, so an
 * abandon is a position that stopped moving: `lastViewedAt` moves on a partial play
 * (checked on the Shield, step 2). An episode's signals land on its show. Pure.
 */
object Signals {
    private val halfLife = Fade.HalfLife(SignalWeights.HALF_LIFE_MILLIS)

    fun from(library: Library, feedback: List<FeedbackEntity>, now: Long): List<Signal> {
        val fromFeedback = feedback.mapNotNull { fromFeedback(it, library) }
        val removed = fromFeedback.filter { it.kind == SignalKind.ABANDONED }.mapTo(mutableSetOf()) { it.titleKey }
        val fromLibrary = library.titles.flatMap { title ->
            when (title.type) {
                "movie" -> movie(title, library, now, abandonedAlready = title.ratingKey in removed)
                "show" -> show(title, library, now)
                else -> emptyList()
            }
        }
        return (fromLibrary + fromFeedback).sortedWith(compareBy<Signal>({ it.titleKey }, { it.kind }, { it.atMillis }))
    }

    private fun movie(title: ItemEntity, library: Library, now: Long, abandonedAlready: Boolean): List<Signal> = buildList {
        val key = title.ratingKey
        val plays = library.playsOf(key)
        val finishes = maxOf(title.viewCount, plays.size)
        // History has the finish; a play older than history's 400 days has only the item's last view.
        val finishedAt = plays.maxOfOrNull { it.viewedAt } ?: title.lastViewedAt?.takeIf { title.viewCount > 0 }
        if (finishes > 0 && finishedAt != null) {
            add(Signal(key, SignalKind.FINISHED, SignalWeights.FINISHED, finishedAt * 1_000, halfLife))
            if (finishes >= 2) add(Signal(key, SignalKind.REWATCHED, SignalWeights.REWATCHED, finishedAt * 1_000, halfLife))
        }
        rated(title)?.let(::add)
        val last = title.lastViewedAt
        val offset = title.viewOffsetMs ?: 0
        if (!abandonedAlready && finishes == 0 && last != null && offset >= SignalWeights.ABANDON_MIN_OFFSET_MILLIS &&
            now - last * 1_000 >= SignalWeights.ABANDON_AFTER_MILLIS
        ) {
            add(Signal(key, SignalKind.ABANDONED, SignalWeights.ABANDONED, last * 1_000, halfLife))
        }
    }

    private fun show(title: ItemEntity, library: Library, now: Long): List<Signal> = buildList {
        val key = title.ratingKey
        // Specials (season 0) don't say whether you finished or stopped a show.
        val episodes = library.episodesOf(key).filter { (it.season ?: 1) != 0 }
        val finished = episodes.filter { it.viewCount > 0 }
        val finishedAt = library.playsOf(key).maxOfOrNull { it.viewedAt } ?: finished.mapNotNull { it.lastViewedAt }.maxOrNull()
        if (finished.isNotEmpty() && finishedAt != null) {
            val share = min(1.0, finished.size / SignalWeights.SHOW_FULL_FINISH.toDouble())
            add(Signal(key, SignalKind.FINISHED, SignalWeights.FINISHED * share, finishedAt * 1_000, halfLife))
            if (episodes.count { it.viewCount >= 2 } >= 2) {
                add(Signal(key, SignalKind.REWATCHED, SignalWeights.REWATCHED, finishedAt * 1_000, halfLife))
            }
        }
        rated(title)?.let(::add)
        val lastView = episodes.mapNotNull { it.lastViewedAt }.maxOrNull()
        // Episodes added after your last view (a new season) don't make it a show you stopped.
        val left = episodes.any { episode ->
            val added = episode.addedAt
            episode.viewCount == 0 && added != null && lastView != null && added <= lastView
        }
        if (finished.isNotEmpty() && lastView != null && left && now - lastView * 1_000 >= SignalWeights.STALLED_AFTER_MILLIS) {
            add(Signal(key, SignalKind.STALLED, SignalWeights.STALLED, lastView * 1_000, halfLife))
        }
    }

    private fun rated(title: ItemEntity): Signal? {
        val rating = title.userRating ?: return null
        val weight = when {
            rating >= SignalWeights.LOVED_RATING -> SignalWeights.RATED_LOVED
            rating <= SignalWeights.DISLIKED_RATING -> SignalWeights.RATED_DISLIKED
            else -> return null
        }
        val at = title.lastRatedAt ?: title.lastViewedAt ?: return null
        return Signal(title.ratingKey, SignalKind.RATED, weight, at * 1_000, halfLife)
    }

    private fun fromFeedback(row: FeedbackEntity, library: Library): Signal? {
        val key = library.showOf[row.ratingKey] ?: row.ratingKey
        return when (row.kind) {
            FeedbackKind.NOT_TONIGHT -> Signal(
                key, SignalKind.NOT_TONIGHT, SignalWeights.NOT_TONIGHT * row.count, row.atMillis,
                Fade.Linear(SignalWeights.NOT_TONIGHT_FADE_MILLIS),
            )
            // Playing it again takes the removal back.
            FeedbackKind.UP_NEXT_REMOVED -> if ((library.lastViewed(key) ?: 0) * 1_000 > row.atMillis) {
                null
            } else {
                Signal(key, SignalKind.ABANDONED, SignalWeights.ABANDONED, row.atMillis, halfLife)
            }
            FeedbackKind.QUICK_WATCH_HIDDEN, FeedbackKind.LENS_HIDDEN -> Signal(
                key, SignalKind.HIDDEN, SignalWeights.HIDDEN, row.atMillis, Fade.Linear(SignalWeights.HIDDEN_FADE_MILLIS),
            )
            // Passed over on the lens card (L5) is about freshness, not taste.
            FeedbackKind.LENS_RESTED -> null
        }
    }
}
