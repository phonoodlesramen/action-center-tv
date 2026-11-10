package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.profiles.KidPlayEntity
import java.time.LocalDate

/** What a kid's run reads besides the shared library (Phase 5): their ceiling, plays and Continue Watching (K2-K4). */
data class KidView(
    val id: String,
    /** The ratings they may see today (K3). */
    val ceiling: Set<String>,
    val plays: List<KidPlayEntity> = emptyList(),
    val continueWatching: List<ContinueEntity> = emptyList(),
)

/** The kids, for the engine's runs: none until there are kids. */
fun interface KidSource {
    /** Every kid, with their ceiling on [today]. */
    suspend fun kids(today: LocalDate): List<KidView>

    companion object {
        val None = KidSource { emptyList() }
    }
}

/**
 * A kid's view of the shared library (Phase 5, K2-K4): only what their ceiling allows (an
 * episode by its own rating, else its show's; unrated hidden), with your watch state taken
 * off and theirs put on. Their plays are their finishes, their Continue Watching their
 * positions; your stars and positions don't count. The engine then runs on it as on
 * yours, every lens included (Phase 6, R1), so their plays drive their taste. Pure.
 */
object KidLibrary {
    fun of(shared: Library, kid: KidView): Library {
        val ceiling = kid.ceiling
        fun showRating(showKey: String?) = showKey?.let(shared.byKey::get)?.contentRating
        val titles = shared.titles.filter { AgeBrackets.allows(ceiling, it.contentRating) }
        val episodes = shared.episodes.filter { AgeBrackets.allows(ceiling, it.contentRating, showRating(it.showKey)) }
        val allowed = titles.mapTo(HashSet()) { it.ratingKey } + episodes.map { it.ratingKey }

        // Their history and positions, on what they may see.
        val plays = kid.plays.filter { it.ratingKey in allowed }
        val finishes = plays.groupBy { it.ratingKey }
        val continueWatching = kid.continueWatching.filter { AgeBrackets.allows(ceiling, it.contentRating, showRating(it.showKey)) }
        val positions = continueWatching.associateBy { it.ratingKey }
        fun finished(key: String) = finishes[key]?.size ?: 0
        fun lastViewed(key: String) = listOfNotNull(finishes[key]?.maxOf { it.viewedAt }, positions[key]?.lastViewedAt).maxOrNull()
        fun position(key: String) = positions[key]?.viewOffsetMs?.takeIf { it > 0 }

        val theirEpisodes = episodes.map {
            it.copy(viewCount = finished(it.ratingKey), lastViewedAt = lastViewed(it.ratingKey), viewOffsetMs = position(it.ratingKey))
        }
        val byShow = theirEpisodes.groupBy { it.showKey }
        val theirTitles = titles.map { title ->
            if (title.type == "show") {
                val ofShow = byShow[title.ratingKey].orEmpty()
                title.copy(
                    viewCount = ofShow.sumOf { it.viewCount },
                    lastViewedAt = ofShow.mapNotNull { it.lastViewedAt }.maxOrNull(),
                    viewOffsetMs = null,
                    watchedEpisodeCount = ofShow.count { it.viewCount > 0 },
                    userRating = null,
                    lastRatedAt = null,
                )
            } else {
                title.copy(
                    viewCount = finished(title.ratingKey),
                    lastViewedAt = lastViewed(title.ratingKey),
                    viewOffsetMs = position(title.ratingKey),
                    userRating = null,
                    lastRatedAt = null,
                )
            }
        }
        return Library(
            theirTitles,
            theirEpisodes,
            plays.map { PlayEntity("${it.ratingKey}@${it.viewedAt}", it.ratingKey, it.type, "", it.viewedAt, null) },
            continueWatching,
        )
    }
}
