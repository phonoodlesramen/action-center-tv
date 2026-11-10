package com.phonoodlesramen.actioncenter.ai

import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.suggest.PickEntity
import com.phonoodlesramen.actioncenter.suggest.Surfaces
import kotlinx.serialization.Serializable

/**
 * One pick as Claude sees it (M8): the title's details and why it was picked, in words.
 * Nothing about you, your server or your account: no Plex key, path, server or account
 * name, and no watch dates. [id] is this request's own ("p1", "p2"…), not Plex's.
 */
@Serializable
data class BlurbPick(
    val id: String,
    val title: String,
    /** "film" or "series". */
    val kind: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    /** The content rating ("PG-13", "TV-MA"). */
    val rating: String? = null,
    val minutes: Int? = null,
    /** The pick's reason line ("Because you liked Heat"). */
    val reason: String,
    /** Why this pick?'s other facts ("Rated 8.4", "New this week"). */
    val evidence: List<String> = emptyList(),
)

/** A pick to write for: what's sent, and the pick and reason its blurb is saved against (M9). */
data class BlurbTarget(val ratingKey: String, val reason: String, val sent: BlurbPick)

/**
 * Which picks get a blurb (M8): Tonight's Pick's (up to [HERO]) and the first [PER_LENS]
 * of each lens that's on, about 30, each title once (the first card it's on wins, so the
 * hero's reason is the one written for). Pure.
 */
object BlurbPicks {
    const val HERO = 6
    const val PER_LENS = 3

    /**
     * "Last watched 3 days ago", "Watched 12 days ago": when you watched something stays
     * on the Shield (M8), so a fact like this is left out, and a pick whose reason is one
     * isn't sent at all.
     */
    private val WATCH_TIME = Regex("""\bwatched\b.*\b(today|yesterday|ago)\b""", RegexOption.IGNORE_CASE)

    /**
     * @param picks the engine's last run, every surface.
     * @param titles the picks' titles and their episodes' shows, by ratingKey.
     */
    fun choose(picks: List<PickEntity>, enabledLenses: Set<Lens>, titles: Map<String, ItemEntity>): List<BlurbTarget> {
        fun first(surface: String, count: Int) = picks.filter { it.surface == surface }.sortedBy { it.rank }.take(count)
        val chosen = first(Surfaces.HERO, HERO) + Lens.entries.filter { it in enabledLenses }.flatMap { first(Surfaces.lens(it), PER_LENS) }
        return chosen
            .distinctBy { it.ratingKey }
            .filterNot { WATCH_TIME.containsMatchIn(it.reason) }
            .mapNotNull { row -> titles[row.ratingKey]?.let { row to it } }
            .mapIndexed { index, (row, item) -> BlurbTarget(row.ratingKey, row.reason, describe("p${index + 1}", row, item, titles)) }
    }

    /** An episode goes as its show: the show's title, year and genres, and the episode's length. */
    private fun describe(id: String, row: PickEntity, item: ItemEntity, titles: Map<String, ItemEntity>): BlurbPick {
        val show = if (item.type == "episode") item.showKey?.let(titles::get) else null
        val series = item.type == "episode" || item.type == "show"
        return BlurbPick(
            id = id,
            title = if (item.type == "episode") item.showTitle ?: show?.title ?: item.title else item.title,
            kind = if (series) "series" else "film",
            year = show?.year ?: item.year,
            genres = (show?.genres?.takeIf(String::isNotBlank) ?: item.genres).split('|').filter(String::isNotBlank),
            rating = item.contentRating ?: show?.contentRating,
            minutes = item.durationMs?.let { (it / 60_000).toInt() }?.takeIf { it > 0 },
            reason = row.reason,
            evidence = row.evidence.split('|').filter { it.isNotBlank() && !WATCH_TIME.containsMatchIn(it) },
        )
    }
}
