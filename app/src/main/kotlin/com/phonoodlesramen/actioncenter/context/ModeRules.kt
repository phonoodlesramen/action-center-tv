package com.phonoodlesramen.actioncenter.context

import com.phonoodlesramen.actioncenter.suggest.Features

/** What [ModeRules] needs to know about one of Tonight's Pick's picks. */
data class HeroPick(
    val kind: Kind,
    /** How long it runs; unknown counts as short. */
    val durationMs: Long? = null,
    /** The title's rating, or for an episode its show's. */
    val contentRating: String? = null,
    /** A [Kind.FINISH] pick's episodes left in its season. */
    val episodesLeft: Int? = null,
) {
    /** The three kinds of pick the engine puts on the hero (S5). */
    enum class Kind {
        FILM,

        /** "Finish it": the next episode of a season you're close to the end of. */
        FINISH,

        /** The one series you haven't started: its first episode. */
        SERIES,
    }
}

/** M3's numbers, in one place for tuning. */
object ModeTuning {
    /** A "finish it" pick leads from this many episodes out on weeknight evenings, and from [FINISH_LEADS_WITHIN] at other times. */
    const val WEEKNIGHT_FINISH_LEADS_WITHIN = 4
    const val FINISH_LEADS_WITHIN = 3

    /** Films longer than these move down: 2h 15m on weeknight evenings, 1h 45m late at night. */
    const val WEEKNIGHT_LONG_FILM_MILLIS = 135 * 60_000L
    const val LATE_LONG_FILM_MILLIS = 105 * 60_000L

    /** Weekend mornings lead with these rating bands: G, PG, TV-Y, TV-Y7, TV-G and TV-PG, as For the family's (S7). */
    val FAMILY_BANDS = setOf(Features.KIDS, Features.FAMILY)
}

/**
 * What each context mode does to Tonight's Pick (M3):
 * - **Weeknight evening:** a "finish it" pick may lead from 4 episodes out; films over 2h 15m
 *   move down.
 * - **Weekend evening:** no length limit, and films come before series (the finish pick and
 *   the series you haven't started move after the films).
 * - **Late night:** films over 1h 45m and the series you haven't started move down; the finish
 *   pick comes first.
 * - **Weekend morning:** titles rated PG / TV-PG or lower come first.
 * - **Daytime:** the engine's order.
 *
 * At any time but a weeknight evening, a finish pick leads only from 3 episodes out, as
 * before Phase 4: further out, it moves below the first film. The rules run when the cards
 * are drawn, as the late-night rule did: they only reorder the engine's picks, never drop
 * one or run the engine again, and the hero you're looking at stays put
 * ([com.phonoodlesramen.actioncenter.home.PickState] keeps it by id). Pure.
 */
object ModeRules {
    /** [picks] in [mode]'s order; [describe] says what each one is. Moves keep the engine's order otherwise. */
    fun <T> order(picks: List<T>, mode: ContextMode, describe: (T) -> HeroPick): List<T> {
        val heroes = picks.map { it to describe(it) }
        val moved = when (mode) {
            ContextMode.WEEKNIGHT_EVENING -> down(heroes) { it.filmOver(ModeTuning.WEEKNIGHT_LONG_FILM_MILLIS) }
            ContextMode.WEEKEND_EVENING -> down(heroes) { it.kind != HeroPick.Kind.FILM }
            ContextMode.LATE_NIGHT -> {
                val shorter = down(heroes) { it.filmOver(ModeTuning.LATE_LONG_FILM_MILLIS) || it.kind == HeroPick.Kind.SERIES }
                first(shorter) { it.kind == HeroPick.Kind.FINISH }
            }
            ContextMode.WEEKEND_MORNING -> first(heroes) { Features.ratingBand(it.contentRating) in ModeTuning.FAMILY_BANDS }
            ContextMode.DAYTIME -> heroes
        }
        val within = if (mode == ContextMode.WEEKNIGHT_EVENING) ModeTuning.WEEKNIGHT_FINISH_LEADS_WITHIN else ModeTuning.FINISH_LEADS_WITHIN
        return finishWithin(moved, within).map { it.first }
    }

    private fun HeroPick.filmOver(millis: Long) = kind == HeroPick.Kind.FILM && (durationMs ?: 0L) > millis

    /** The picks [rule] matches go below the others. */
    private fun <T> down(heroes: List<Pair<T, HeroPick>>, rule: (HeroPick) -> Boolean): List<Pair<T, HeroPick>> {
        val (moved, rest) = heroes.partition { rule(it.second) }
        return rest + moved
    }

    /** The picks [rule] matches go above the others. */
    private fun <T> first(heroes: List<Pair<T, HeroPick>>, rule: (HeroPick) -> Boolean): List<Pair<T, HeroPick>> {
        val (first, rest) = heroes.partition { rule(it.second) }
        return first + rest
    }

    /** A finish pick more than [within] episodes from the end goes just below the first film, when it's above it. */
    private fun <T> finishWithin(heroes: List<Pair<T, HeroPick>>, within: Int): List<Pair<T, HeroPick>> {
        val finish = heroes.indexOfFirst { (_, hero) -> hero.kind == HeroPick.Kind.FINISH && (hero.episodesLeft ?: 0) > within }
        val film = heroes.indexOfFirst { (_, hero) -> hero.kind == HeroPick.Kind.FILM }
        if (finish < 0 || film < 0 || finish > film) return heroes
        // Taking the finish pick out moves the film up one, so it goes back in just below it.
        return heroes.toMutableList().apply { add(film, removeAt(finish)) }
    }
}
