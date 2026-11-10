package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import kotlin.math.abs

/** How much each kind of feature counts toward a title's affinity, and the smoothing. Tunable. */
object TasteWeights {
    /** Added below the line, so a feature seen once can't dominate: one finish weighs a third. */
    const val SMOOTHING = 2.0
    const val GENRE = 0.30
    const val COLLECTION = 0.15
    const val DIRECTOR = 0.15
    const val ACTOR = 0.15
    const val DECADE = 0.10
    const val LENGTH = 0.10
    const val RATING = 0.05

    fun of(kind: FeatureKind): Double = when (kind) {
        FeatureKind.GENRE -> GENRE
        FeatureKind.COLLECTION -> COLLECTION
        FeatureKind.DIRECTOR -> DIRECTOR
        FeatureKind.ACTOR -> ACTOR
        FeatureKind.DECADE -> DECADE
        FeatureKind.LENGTH -> LENGTH
        FeatureKind.RATING -> RATING
    }
}

/**
 * A title you finished, as a reason may name it: when you last finished it, and whether you
 * loved it (rated it 4 stars or more, or finished it twice). A show counts from 3 finished
 * episodes. Titles you abandoned, stopped or rated 2 stars or fewer are never one.
 */
data class Finish(val titleKey: String, val atMillis: Long, val loved: Boolean)

/**
 * Your taste (plan §3): each feature's weight from your signals, faded by age. A feature
 * keeps what counted for it (P) and against it (N) and weighs (P − N) / (P + N + 2), so one
 * finish gives a third and only repeated evidence gets near 1. A title's affinity is each
 * kind's [TasteWeights] times the mean weight of its features of that kind. Pure.
 */
class Taste private constructor(
    private val weights: Map<Feature, Double>,
    private val support: Map<Feature, Set<String>>,
    /** The titles you finished that a reason may name, by key. */
    val finished: Map<String, Finish>,
    private val catalog: Catalog,
) : Affinity {
    override fun of(title: ItemEntity): Double =
        catalog.features(title).groupBy { it.kind }.entries.sumOf { (kind, features) ->
            TasteWeights.of(kind) * features.sumOf { weight(it) } / features.size
        }

    /** A feature's weight, from −1 (only against) to 1 (only for); 0 when nothing counted. */
    fun weight(feature: Feature): Double = weights[feature] ?: 0.0

    /** The titles that counted for [feature]: finishes, rewatches and loved ratings. */
    fun evidence(feature: Feature): Set<String> = support[feature].orEmpty()

    companion object {
        private val FOR = setOf(SignalKind.FINISHED, SignalKind.REWATCHED, SignalKind.RATED)

        fun from(library: Library, signals: List<Signal>, now: Long): Taste {
            val catalog = Catalog.of(library)
            val forSums = HashMap<Feature, Double>()
            val againstSums = HashMap<Feature, Double>()
            val support = LinkedHashMap<Feature, MutableSet<String>>()
            val finished = LinkedHashMap<String, Finish>()
            for ((key, own) in signals.groupBy { it.titleKey }) {
                val title = library.byKey[key] ?: continue
                val features = catalog.features(title)
                for (signal in own) {
                    val weight = signal.weightAt(now)
                    if (weight == 0.0) continue
                    val sums = if (weight > 0) forSums else againstSums
                    features.forEach { sums[it] = (sums[it] ?: 0.0) + abs(weight) }
                    if (signal.kind in FOR && signal.weight > 0) features.forEach { support.getOrPut(it) { linkedSetOf() } += key }
                }
                finishOf(title, own)?.let { finished[key] = it }
            }
            val weights = (forSums.keys + againstSums.keys).associateWith { feature ->
                val p = forSums[feature] ?: 0.0
                val n = againstSums[feature] ?: 0.0
                (p - n) / (p + n + TasteWeights.SMOOTHING)
            }
            return Taste(weights, support, finished, catalog)
        }

        private fun finishOf(title: ItemEntity, own: List<Signal>): Finish? {
            val finish = own.filter { it.kind == SignalKind.FINISHED }.maxByOrNull { it.atMillis } ?: return null
            // A show's finish counts in part below 3 episodes: that isn't a show you finished.
            if (finish.weight < SignalWeights.FINISHED) return null
            val rating = title.userRating
            val spoiled = (rating != null && rating <= SignalWeights.DISLIKED_RATING) || own.any {
                it.kind == SignalKind.ABANDONED || it.kind == SignalKind.STALLED || (it.kind == SignalKind.RATED && it.weight < 0)
            }
            if (spoiled) return null
            val loved = (rating != null && rating >= SignalWeights.LOVED_RATING) || own.any { it.kind == SignalKind.REWATCHED }
            return Finish(title.ratingKey, finish.atMillis, loved)
        }
    }
}
