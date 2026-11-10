package com.phonoodlesramen.actioncenter.search

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.nfl.WatchApp
import com.phonoodlesramen.actioncenter.plex.CatalogDetails
import com.phonoodlesramen.actioncenter.plex.CatalogOffer
import com.phonoodlesramen.actioncenter.plex.CatalogTitle
import com.phonoodlesramen.actioncenter.plex.PlexCatalogApi
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An app on this TV a catalog title streams on (Phase 15, Q3). [url] is the title on that
 * service, when Plex knows it; without one, OK opens the app's home.
 */
data class StreamingApp(val packageName: String, val label: String, val url: String? = null)

/**
 * A title from Plex's catalog on [apps] (Q3): the apps on this TV that whoever's watching may
 * open, best first. [image] is a web address (the catalog's art), never a path on your server.
 */
data class StreamingItem(
    val ratingKey: String,
    val type: String,
    val title: String,
    val year: Int?,
    val contentRating: String?,
    val image: String?,
    val apps: List<StreamingApp>,
) {
    /** "1995 · R". */
    val meta: String get() = listOfNotNull(year?.toString(), contentRating).joinToString(" · ")
}

/** What Search asks of Plex's catalog: the seam tests fake. */
fun interface StreamingSearch {
    /**
     * Titles [words] find in Plex's catalog that stream on [apps] (the apps on this TV the
     * profile on now may open, best first), less those already in [library].
     */
    suspend fun search(words: SearchWords, profile: ActiveProfile, apps: List<LaunchableApp>, library: List<SearchItem>): List<StreamingItem>

    companion object {
        val None = StreamingSearch { _, _, _, _ -> emptyList() }
    }
}

/**
 * Your streaming apps through Plex's catalog (Phase 15, Q3):
 * - the words left after the chips go to the catalog's search. A decade, "movies" and "shows"
 *   narrow what comes back, "kids" by each title's rating; "new" is about your library, so it
 *   doesn't apply here;
 * - titles already in your library are left out (the same title and year);
 * - the rest get their details (the rating) and where to watch, held for the day, [GROUP] at a
 *   time from the best match down, until [SHOWN] are on your apps or the catalog's titles run
 *   out. With a big library, the first few are often on services this TV doesn't have;
 * - only subscription and free offers, on [apps]. YouTube is left out; YouTube TV stays;
 * - a kid sees only titles rated inside their ceiling (unrated hidden, K4), and everyone is
 *   spared titles rated X or NC-17, or unrated with an adult genre (the catalog has them).
 *
 * Where to watch needs your plex.tv [token]: without one (not signed in), there's nothing to show.
 */
class PlexStreamingSearch(
    private val catalog: PlexCatalogApi,
    private val token: suspend () -> String?,
    private val today: () -> LocalDate = { LocalDate.now() },
) : StreamingSearch {
    /** What the catalog said of a title today. */
    private class Known(val details: CatalogDetails?, val offers: List<CatalogOffer>)

    private val lock = Mutex()
    private var day: LocalDate? = null
    private val known = HashMap<String, Known>()

    override suspend fun search(words: SearchWords, profile: ActiveProfile, apps: List<LaunchableApp>, library: List<SearchItem>): List<StreamingItem> {
        if (words.query.isBlank() || apps.isEmpty()) return emptyList()
        val token = token() ?: return emptyList()
        val narrowing = words.chips.filter { it is SearchChip.Decade || it == SearchChip.Movies || it == SearchChip.Shows }
        val candidates = catalog.search(words.query, SEARCH_LIMIT).titles
            .filter { passes(narrowing, it.type, it.year, contentRating = null, addedAtSeconds = null, nowSeconds = 0) }
            .filterNot { title -> library.any { sameTitle(it.title, it.year, title.title, title.year) } }
        // The catalog's best matches first, a group at a time, until enough are on your apps.
        val shown = mutableListOf<StreamingItem>()
        for (group in candidates.chunked(GROUP)) {
            val looked = coroutineScope { group.map { title -> async { title to look(title.ratingKey, token) } }.awaitAll() }
            looked.mapNotNullTo(shown) { (title, known) -> known?.let { show(title, it, words, profile, apps) } }
            if (shown.size >= SHOWN) break
        }
        return shown.take(SHOWN)
    }

    /** [title] as a card, when it's on [apps] and whoever's watching may see it. */
    private fun show(title: CatalogTitle, known: Known, words: SearchWords, profile: ActiveProfile, apps: List<LaunchableApp>): StreamingItem? {
        val rating = known.details?.contentRating
        if (adult(rating, title.genres) || !AgeBrackets.allows(profile.ceiling, rating)) return null
        if (SearchChip.Kids in words.chips && !passes(listOf(SearchChip.Kids), title.type, title.year, rating, null, 0)) return null
        val on = appsFor(known.offers, apps).ifEmpty { return null }
        return StreamingItem(title.ratingKey, title.type, title.title, title.year, rating, title.art ?: title.thumb, on)
    }

    /** A title's details and offers: today's, or asked for now. Null when the catalog couldn't say; that isn't kept. */
    private suspend fun look(ratingKey: String, token: String): Known? {
        lock.withLock {
            val now = today()
            if (day != now) {
                day = now
                known.clear()
            }
            known[ratingKey]?.let { return it }
        }
        val found = try {
            coroutineScope {
                val details = async { catalog.details(ratingKey) }
                val offers = async { catalog.offers(ratingKey, token) }
                Known(details.await(), offers.await())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        lock.withLock { known[ratingKey] = found }
        return found
    }

    companion object {
        /** Titles asked of the catalog's search. */
        const val SEARCH_LIMIT = 20

        /** Titles whose details and offers are looked up at a time. */
        const val GROUP = 6

        /** At most this many show: once a group brings it to this, no more are looked up. */
        const val SHOWN = 6

        /** Offers you can watch on an app you have: rent and buy are left out. */
        private val STREAMING = setOf("subscription", "free", "ads")

        /** YouTube is left out of Search (Phase 15); YouTube TV isn't. */
        private val LEFT_OUT_SLUGS = setOf("youtube")
        private val LEFT_OUT_PACKAGES = setOf(WatchApp.YOUTUBE.packageName)

        /** The services the TV's Search check named (11/03/2026), to their apps; others go by name. */
        private val KNOWN = mapOf(
            "netflix" to WatchApp.NETFLIX.packageName,
            "netflix-basic-with-ads" to WatchApp.NETFLIX.packageName,
            "amazon-prime-video" to WatchApp.PRIME_VIDEO.packageName,
            "youtube-tv" to WatchApp.YOUTUBE_TV.packageName,
        )

        private val ADULT_RATINGS = setOf("X", "XXX", "NC-17")
        private val ADULT_GENRES = setOf("adult", "erotic", "erotica")

        /** The apps in [apps] (best first) that carry one of [offers] you can stream, each once. */
        internal fun appsFor(offers: List<CatalogOffer>, apps: List<LaunchableApp>): List<StreamingApp> {
            val streaming = offers.filter { it.offerType.lowercase() in STREAMING && it.platform !in LEFT_OUT_SLUGS }
            return apps.filter { it.packageName !in LEFT_OUT_PACKAGES }.distinctBy { it.packageName }.mapNotNull { app ->
                val carried = streaming.filter { carries(it, app) }
                if (carried.isEmpty()) null else StreamingApp(app.packageName, app.label, carried.firstNotNullOfOrNull { it.url })
            }
        }

        /** An offer is on [app]: a service the check named, by its package; any other by the app's name ("Disney Plus" is "Disney+"). */
        internal fun carries(offer: CatalogOffer, app: LaunchableApp): Boolean {
            KNOWN[offer.platform]?.let { return it == app.packageName }
            val service = fold(offer.title)
            val label = fold(app.label)
            return label.length >= MIN_LABEL && (service == label || service.startsWith(label))
        }

        /** Titles X or NC-17, or unrated with an adult genre: never on the family-room home. */
        internal fun adult(contentRating: String?, genres: List<String>): Boolean =
            contentRating?.uppercase() in ADULT_RATINGS || (contentRating == null && genres.any { it.lowercase() in ADULT_GENRES })

        /** The same title, and the same year when both say. */
        internal fun sameTitle(a: String, aYear: Int?, b: String, bYear: Int?): Boolean =
            fold(a) == fold(b) && (aYear == null || bYear == null || aYear == bYear)

        private fun fold(name: String) = name.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

        /** Shorter names ("TV") would match too much. */
        private const val MIN_LABEL = 3
    }
}
