package com.phonoodlesramen.actioncenter.search

import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexSearchHit
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexDao
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import kotlin.coroutines.cancellation.CancellationException

/**
 * A title Search shows (Phase 15). [meta] is "1995 · R" or "Severance · S2E9"; [why] says why
 * Plex found it when the words weren't in its title ("With Arnold Schwarzenegger"); [image]
 * is a server path for the card's art.
 */
data class SearchItem(
    val ratingKey: String,
    val type: String,
    val title: String,
    val meta: String,
    val why: String? = null,
    val image: String? = null,
    val contentRating: String? = null,
    val year: Int? = null,
)

/** Your library's answer (Q1), best first. [offline]: Plex couldn't be reached, so these are the titles on this TV. */
data class LibraryAnswer(val items: List<SearchItem>, val offline: Boolean = false)

/** What Search asks of your library: the seam tests fake. */
fun interface LibrarySearch {
    suspend fun search(words: SearchWords, profile: ActiveProfile): LibraryAnswer

    companion object {
        val None = LibrarySearch { _, _ -> LibraryAnswer(emptyList()) }
    }
}

/**
 * Your library through Plex's own search (Phase 15, Q1), as the profile on now: their own
 * Plex user for a kid who has one, so Plex's own limits apply, and their ceiling on top
 * (unrated hidden, K4).
 *
 * - Plain words go to Plex as typed.
 * - With chips (Q2), Plex is asked twice: as typed, whose close title matches lead ("the
 *   truman show" stays the film), then the words left, narrowed by the chips. Chips alone
 *   ("kids movies") list the titles on this TV that pass them, newest first.
 * - Plex unreachable, or no server: titles on this TV whose name holds the words.
 */
class PlexLibrarySearch(
    private val server: PlexServerApi,
    private val access: suspend (ActiveProfile) -> ServerAccess?,
    /** The cache's movies and shows whose title holds a `LIKE` pattern ([PlexDao.titlesLike]). */
    private val titlesLike: suspend (pattern: String, limit: Int) -> List<ItemEntity>,
    /** The cache's rows for some keys ([PlexDao.items]). */
    private val items: suspend (keys: List<String>) -> List<ItemEntity>,
    /** Every movie and show in the cache, for chips alone. */
    private val titles: suspend () -> List<ItemEntity>,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) : LibrarySearch {
    override suspend fun search(words: SearchWords, profile: ActiveProfile): LibraryAnswer {
        if (words.text.isBlank()) return LibraryAnswer(emptyList())
        if (words.query.isBlank()) return LibraryAnswer(browse(words, profile))
        val access = access(profile) ?: return LibraryAnswer(onThisTv(words, profile), offline = true)
        return try {
            val asTyped = if (words.narrowed) server.search(access, words.text).flatMap { it.hits }.filter { closeTo(it.title, words.text) } else emptyList()
            val narrowed = server.search(access, words.query).flatMap { it.hits }
            val added = addedAt((asTyped + narrowed).map { it.ratingKey })
            val now = nowSeconds()
            val kept = asTyped + narrowed.filter { passes(words.chips, it.type, it.year, it.contentRating, added[it.ratingKey], now) }
            LibraryAnswer(kept.filter { it.type in TYPES && fits(profile, it.contentRating) }.distinctBy { it.ratingKey }.map(::item))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexException) {
            LibraryAnswer(onThisTv(words, profile), offline = true)
        }
    }

    /** Chips alone: the titles on this TV that pass them, newest first. */
    private suspend fun browse(words: SearchWords, profile: ActiveProfile): List<SearchItem> {
        val now = nowSeconds()
        return titles()
            .filter { passes(words.chips, it.type, it.year, it.contentRating, it.addedAt, now) && fits(profile, it.contentRating) }
            .sortedByDescending { it.addedAt ?: 0 }
            .take(LIMIT)
            .map(::item)
    }

    private suspend fun onThisTv(words: SearchWords, profile: ActiveProfile): List<SearchItem> {
        val pattern = (words.query.ifBlank { words.text }).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val now = nowSeconds()
        return titlesLike(pattern, LIMIT)
            .filter { passes(words.chips, it.type, it.year, it.contentRating, it.addedAt, now) && fits(profile, it.contentRating) }
            .map(::item)
    }

    /** When each title was added, from the copy on this TV ("new" needs it; Plex's search doesn't say). */
    private suspend fun addedAt(keys: List<String>): Map<String, Long> =
        if (keys.isEmpty()) emptyMap() else items(keys.distinct().take(MAX_KEYS)).mapNotNull { row -> row.addedAt?.let { row.ratingKey to it } }.toMap()

    private fun fits(profile: ActiveProfile, contentRating: String?) = AgeBrackets.allows(profile.ceiling, contentRating)

    companion object {
        /** Titles Search shows from your library at most. */
        const val LIMIT = 40

        private const val MAX_KEYS = 500
        private val TYPES = setOf("movie", "show", "episode")

        /** A title close enough to what was typed to lead even with chips: the same, or starting with it. */
        fun closeTo(title: String, text: String): Boolean {
            val a = fold(title)
            val b = fold(text)
            return b.isNotEmpty() && (a == b || a.startsWith(b) || a.removePrefix("the ") == b)
        }

        private fun fold(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

        fun item(hit: PlexSearchHit) = SearchItem(
            ratingKey = hit.ratingKey,
            type = hit.type,
            title = hit.title,
            meta = when (hit.type) {
                "episode" -> listOfNotNull(hit.showTitle, episodeLabel(hit.season, hit.episode)).joinToString(" · ")
                else -> listOfNotNull(hit.year?.toString(), hit.contentRating).joinToString(" · ")
            },
            why = why(hit.reason, hit.reasonTitle),
            image = hit.art ?: hit.thumb,
            contentRating = hit.contentRating,
            year = hit.year,
        )

        fun item(row: ItemEntity) = SearchItem(
            ratingKey = row.ratingKey,
            type = row.type,
            title = row.title,
            meta = listOfNotNull(row.year?.toString(), row.contentRating).joinToString(" · "),
            image = row.art ?: row.thumb,
            contentRating = row.contentRating,
            year = row.year,
        )

        private fun episodeLabel(season: Int?, episode: Int?) = if (season != null && episode != null) "S${season}E$episode" else null

        /** Plex's reason in words: an actor's, a director's, a genre's or a collection's name. */
        fun why(reason: String?, reasonTitle: String?): String? {
            val name = reasonTitle ?: return null
            return when (reason) {
                "actor" -> "With $name"
                "director" -> "Directed by $name"
                "collection" -> "In $name"
                "section" -> null
                else -> name
            }
        }
    }
}

/** A channel Search found on now (Phase 15): a guide channel to tune, or a YouTube TV network to open. */
data class OnNowItem(
    val name: String,
    /** What's on, for a guide channel. */
    val now: String?,
    val image: String?,
    /** A guide channel's id ([com.phonoodlesramen.actioncenter.ui.home.HomeActions.tuneIn]). */
    val channelId: String? = null,
    /** A network's link ([com.phonoodlesramen.actioncenter.ui.home.HomeActions.openIntentUri]). */
    val intentUri: String? = null,
)

/**
 * On now (Phase 15): guide channels whose name, or whose program on at [nowMillis], holds what
 * was typed, then the networks (YouTube TV's) whose name does. [guide] and [networks] are
 * what the profile on now may see.
 */
fun onNow(words: SearchWords, guide: GuideCardState, networks: List<ChannelButton>, nowMillis: Long): List<OnNowItem> {
    val text = words.text.lowercase()
    if (text.length < 2) return emptyList()
    val channels = (guide as? GuideCardState.Ready)?.channels.orEmpty().mapNotNull { channel ->
        val slot = channel.slotAt(nowMillis)?.takeIf { !it.filler && it.offAir == null }
        val matches = text in channel.name.lowercase() || (slot != null && text in slot.title.lowercase())
        if (!matches) return@mapNotNull null
        OnNowItem(channel.name, slot?.title, slot?.art ?: channel.logo, channelId = channel.id)
    }
    val nets = networks.filter { text in it.name.lowercase() }.map { OnNowItem(it.name, null, it.art, intentUri = it.intentUri) }
    return (channels + nets).take(ON_NOW_MAX)
}

/** Channels On now shows at most. */
private const val ON_NOW_MAX = 10
