package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import kotlin.math.min

/** What each thing two titles share counts toward More like this. Tunable. */
object LikeWeights {
    const val COLLECTION = 3.0
    const val DIRECTOR = 2.0
    const val ACTOR = 1.0

    /** Only the first three shared cast count. */
    const val ACTORS_COUNTED = 3
    const val GENRE = 0.5
    const val DECADE = 0.5
    const val LIMIT = 8
}

/**
 * More like this (S10): the titles most like one of the hero's picks, of the same kind (films
 * for a film, shows for a show). A shared collection counts most, then a director, cast,
 * genres and the decade; at the same likeness the ones you haven't seen come first, then
 * your taste. Each has its own line ("Also Dena Varga", "Crime · 1990s, like Heat"). Pure.
 */
object MoreLikeThis {
    /** Never [title] itself, nothing Not tonight hides, and no film without a file. */
    fun like(title: ItemEntity, input: RunInput, taste: Taste, limit: Int = LikeWeights.LIMIT): List<Picked> {
        val catalog = Catalog.of(input.library)
        val own = catalog.features(title)
        val hidden = input.hides.notTonight
        return input.library.titles.asSequence()
            .filter { it.ratingKey != title.ratingKey && it.type == title.type && it.ratingKey !in hidden }
            .filter { it.type != "movie" || it.partKey != null }
            .mapNotNull { candidate ->
                val theirs = catalog.features(candidate)
                val shared = own.filter { it in theirs }
                if (shared.none { it.kind != FeatureKind.DECADE && it.kind in COUNTED }) return@mapNotNull null
                // A show plays its next episode (Picked's ratingKey is what Play hands off).
                val playKey = when (candidate.type) {
                    "show" -> catalog.nextEpisode(candidate.ratingKey)?.ratingKey?.takeIf { it !in hidden } ?: return@mapNotNull null
                    else -> candidate.ratingKey
                }
                Match(candidate, playKey, shared, similarity(shared), !catalog.watched(candidate), taste.of(candidate), Scoring.seed(candidate.ratingKey, input.today))
            }
            .sortedWith(ORDER)
            .take(limit)
            .map { Picked(it.playKey, line(title, it, taste, catalog)) }
            .toList()
    }

    /** Each pick's similar titles, by the pick's ratingKey; an episode's are its show's. */
    fun forPicks(picks: List<Picked>, input: RunInput, taste: Taste): Map<String, List<Picked>> = picks.associate { pick ->
        val title = input.library.byKey[input.library.showOf[pick.ratingKey] ?: pick.ratingKey]
        pick.ratingKey to (title?.let { like(it, input, taste) } ?: emptyList())
    }

    private val COUNTED = setOf(FeatureKind.COLLECTION, FeatureKind.DIRECTOR, FeatureKind.ACTOR, FeatureKind.GENRE, FeatureKind.DECADE)

    private class Match(
        val title: ItemEntity,
        val playKey: String,
        val shared: List<Feature>,
        val similarity: Double,
        val unseen: Boolean,
        val affinity: Double,
        val seed: Int,
    )

    private val ORDER = compareByDescending<Match> { it.similarity }
        .thenByDescending { it.unseen }
        .thenByDescending { it.affinity }
        .thenBy { it.seed }
        .thenBy { it.title.ratingKey }

    private fun similarity(shared: List<Feature>): Double {
        fun has(kind: FeatureKind) = shared.any { it.kind == kind }
        val actors = min(LikeWeights.ACTORS_COUNTED, shared.count { it.kind == FeatureKind.ACTOR })
        return (if (has(FeatureKind.COLLECTION)) LikeWeights.COLLECTION else 0.0) +
            (if (has(FeatureKind.DIRECTOR)) LikeWeights.DIRECTOR else 0.0) +
            actors * LikeWeights.ACTOR +
            shared.count { it.kind == FeatureKind.GENRE } * LikeWeights.GENRE +
            (if (has(FeatureKind.DECADE)) LikeWeights.DECADE else 0.0)
    }

    /**
     * "Next in the {collection} collection" when it comes straight after [source], or after one
     * you finished; else "From the…", "Also {director}", "Also starring {actor}", and last
     * "{genre} · {decade}, like {source}".
     */
    private fun line(source: ItemEntity, match: Match, taste: Taste, catalog: Catalog): String {
        val collections = match.shared.filter { it.kind == FeatureKind.COLLECTION }
        collections.firstOrNull { collection ->
            val before = catalog.previousIn(collection.value, match.title.ratingKey)?.ratingKey
            before != null && (before == source.ratingKey || (match.unseen && before in taste.finished))
        }?.let { return "Next in the ${it.value} collection" }
        collections.firstOrNull()?.let { return "From the ${it.value} collection" }
        match.shared.firstOrNull { it.kind == FeatureKind.DIRECTOR }?.let { return "Also ${it.value}" }
        match.shared.firstOrNull { it.kind == FeatureKind.ACTOR }?.let { return "Also starring ${it.value}" }
        val genre = match.shared.first { it.kind == FeatureKind.GENRE }.value
        val decade = match.shared.firstOrNull { it.kind == FeatureKind.DECADE }?.value
        return if (decade != null) "$genre · $decade, like ${source.title}" else "$genre, like ${source.title}"
    }
}
