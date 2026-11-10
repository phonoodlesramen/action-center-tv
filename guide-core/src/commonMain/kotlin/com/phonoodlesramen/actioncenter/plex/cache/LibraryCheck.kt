package com.phonoodlesramen.actioncenter.plex.cache

import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import java.util.Locale

/**
 * Phase 3's library check, after each full sync: what the cache holds for scoring, and for
 * a few titles the cache's tags against a single fetch of their details. Step 2 showed the
 * listing cuts tags short, so the cache fills them from batched details (S11); this says
 * whether a batch carries them all. It also counts your ratings and whether "last viewed"
 * moves on a partial play. Counts, ratingKeys and label names only, never a token or a title.
 */
class LibraryCheck(private val server: PlexServerApi, private val dao: PlexDao, private val log: (String) -> Unit) {
    suspend fun run(access: ServerAccess) {
        val fields = dao.libraryFields()
        log(
            "library check: ${fields.items} items: ${fields.withPosition} with a position, ${fields.rated} rated by you, " +
                "${fields.withReleaseDate} with a release date, ${fields.inCollections} in a collection, ${fields.labelled} labelled, " +
                "${fields.withStudio} with a studio, ${fields.withLogo} with a logo, " +
                "${fields.withDirectors} with directors, ${fields.withCast} with cast, " +
                "${fields.showsWithCounts} of ${fields.shows} shows with episode counts, " +
                "${fields.filled} of ${fields.moviesAndShows} movies and shows filled from details; the most any title has: " +
                "${fields.mostGenres} genres, ${fields.mostCast} cast, ${fields.mostDirectors} directors",
        )
        val positions = dao.positionTimes()
        val continueWatching = dao.continueWatchingTimes()
        log(
            "library check: last viewed: ${positions.unfinishedWithLastViewed} of ${positions.unfinished} unfinished titles " +
                "have one; ${positions.rewatchingViewedSinceFinish} of ${positions.rewatching} rewatches in progress were " +
                "viewed after their last finish; ${continueWatching.withLastViewed} of ${continueWatching.items} in Continue Watching",
        )
        log("library check: ${commonLabels(dao.labelLists())}")
        val compared = (dao.mostListedCast("movie", MOVIES) + dao.mostListedCast("show", SHOWS)).map { cached ->
            val details = try {
                server.metadata(access, cached.ratingKey)
            } catch (e: PlexException.Failed) {
                null
            }
            cached to details
        }
        compared.forEach { (cached, details) -> log("library check: ${line(cached, details)}") }
        log("library check: ${verdict(compared)}")
    }

    /** One tag list's names, in the cache and in the title's details (cast up to [PlexSync.CAST_KEPT]). */
    data class TagCounts(val name: String, val cached: Int, val detailed: Int)

    companion object {
        /** Titles compared: the movies and shows with the most cast. */
        const val MOVIES = 3
        const val SHOWS = 2

        fun counts(cached: ItemEntity, details: PlexItem): List<TagCounts> = listOf(
            TagCounts("genres", cached.genres.names(), details.genres.size),
            TagCounts("cast", cached.actors.names(), minOf(details.actors.size, PlexSync.CAST_KEPT)),
            TagCounts("directors", cached.directors.names(), details.directors.size),
            TagCounts("collections", cached.collections.names(), details.collections.size),
            TagCounts("labels", cached.labels.names(), details.labels.size),
        )

        /** "7303 movie, cache/details: genres 2/4, cast 3/10, …", or that it has no details. */
        fun line(cached: ItemEntity, details: PlexItem?): String {
            val what = "${cached.ratingKey} ${cached.type}${if (cached.detailsAt == null) " (not filled)" else ""}"
            if (details == null) return "$what: no details"
            return "$what, cache/details: " + counts(cached, details).joinToString { "${it.name} ${it.cached}/${it.detailed}" }
        }

        /** For each tag list, how many of the compared titles have fewer names cached than their details carry. */
        fun verdict(compared: List<Pair<ItemEntity, PlexItem?>>): String {
            val all = compared.mapNotNull { (cached, details) -> details?.let { counts(cached, it) } }
            if (all.isEmpty()) return "no titles to compare"
            val names = all.first().map { it.name }
            return "short in the cache: " + names.joinToString { name ->
                "$name ${all.count { title -> title.first { it.name == name }.let { it.detailed > it.cached } }} of ${all.size}"
            }
        }

        /** Labels named in the log: the commonest, so a tool's tags stand out from your own (Phase 3 follow-up). */
        const val LABELS = 8

        /** "labels on the most titles: radarr 3,201, 4K 640, Halloween 14", from each title's "|"-joined labels. */
        fun commonLabels(lists: List<String>): String {
            val counts = lists.flatMap { it.split('|') }.map(String::trim).filter(String::isNotEmpty).groupingBy { it }.eachCount()
            if (counts.isEmpty()) return "no labels"
            val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(LABELS)
            return "labels on the most titles: " + top.joinToString { "${it.key} ${"%,d".format(Locale.US, it.value)}" }
        }

        private fun String.names() = if (isEmpty()) 0 else split('|').size
    }
}
