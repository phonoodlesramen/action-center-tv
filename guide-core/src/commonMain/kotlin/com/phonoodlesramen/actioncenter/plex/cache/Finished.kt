package com.phonoodlesramen.actioncenter.plex.cache

import com.phonoodlesramen.actioncenter.plex.PlexItem

/**
 * When a title in Plex's Continue Watching counts as finished, and what takes its place in
 * Up Next (the Up Next credits fix, 10/27/2026).
 *
 * Plex marks a title played once playback passes its played point: 90% by default, or the
 * first credits marker when that comes sooner (Plex Pass credit detection), and its Continue
 * Watching then lists the show's next episode. Plex still lists a title past that point when
 * it didn't see playback cross it, or is set to wait longer (a higher threshold, the final
 * credits marker). Up Next counts those as finished too, the way Plex's default would.
 *
 * Pure, apart from the episode lookup [upNext] is given, so a kid's Continue Watching
 * (Phase 5) can use the same rule.
 */
object Finished {
    /** Plex's default "Video played threshold". */
    const val PLAYED_FRACTION = 0.9

    /**
     * True when [offsetMs] into a title [durationMs] long is past its first credits marker
     * ([creditsMs], when Plex found the credits) or its played point, whichever comes first.
     * Not started (a next episode) is never finished.
     */
    fun at(offsetMs: Long?, durationMs: Long?, creditsMs: Long? = null): Boolean {
        val offset = offsetMs?.takeIf { it > 0 } ?: return false
        if (creditsMs != null && creditsMs > 0 && offset >= creditsMs) return true
        val duration = durationMs?.takeIf { it > 0 } ?: return false
        return offset >= duration * PLAYED_FRACTION
    }

    /**
     * The episode after [season] [episode] among one show's [episodes]: the next in the
     * season, else the first of a later one. Specials (season 0) don't count, and a special
     * has no next. Null after the last.
     */
    fun nextEpisode(season: Int?, episode: Int?, episodes: List<ItemEntity>): ItemEntity? {
        if (season == null || episode == null || season == 0) return null
        return episodes
            .filter { it.season != null && it.season != 0 && it.episode != null }
            .sortedWith(compareBy<ItemEntity>({ it.season }, { it.episode }))
            .firstOrNull { (it.season!! == season && it.episode!! > episode) || it.season!! > season }
    }

    /**
     * Continue Watching as Up Next shows it, in Plex's order: a finished episode makes way for
     * its show's next one (as Plex lists a next episode: no position), a finished film or
     * finale for nothing. [credits] are the first credits markers by ratingKey;
     * [episodesOf] reads a show's cached episodes.
     */
    suspend fun upNext(
        items: List<PlexItem>,
        credits: Map<String, Long>,
        episodesOf: suspend (showKey: String) -> List<ItemEntity>,
    ): List<PlexItem> {
        val listed = items.mapTo(mutableSetOf()) { it.ratingKey }
        return items.mapNotNull { item ->
            if (!at(item.viewOffsetMs, item.durationMs, credits[item.ratingKey])) return@mapNotNull item
            val show = item.showKey?.takeIf { item.type == "episode" } ?: return@mapNotNull null
            nextEpisode(item.season, item.episode, episodesOf(show))
                ?.takeIf { it.ratingKey !in listed }
                ?.let { item.makingWayFor(it) }
        }
    }

    /** [next] in this episode's place: its own details, and the show's art and last view (Up Next's recency, S3). */
    private fun PlexItem.makingWayFor(next: ItemEntity) = PlexItem(
        ratingKey = next.ratingKey,
        type = "episode",
        title = next.title,
        showTitle = next.showTitle ?: showTitle,
        season = next.season,
        episode = next.episode,
        year = next.year,
        durationMs = next.durationMs,
        viewCount = next.viewCount,
        lastViewedAt = lastViewedAt,
        contentRating = next.contentRating ?: contentRating,
        thumb = next.thumb,
        art = next.art ?: art,
        showThumb = showThumb,
        showArt = showArt,
        showKey = showKey,
    )
}
