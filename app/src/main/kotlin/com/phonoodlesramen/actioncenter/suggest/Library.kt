package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.EpisodeRow
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlayEntity
import com.phonoodlesramen.actioncenter.plex.cache.SuggestLibraryDao

/**
 * One read of the Plex cache: everything a suggestion run works from, in memory (on your
 * Shield about 3,600 movies and shows and 6,000 episodes). Times are epoch seconds.
 */
class Library(
    val titles: List<ItemEntity>,
    val episodes: List<EpisodeRow>,
    val plays: List<PlayEntity>,
    val continueWatching: List<ContinueEntity>,
) {
    val byKey: Map<String, ItemEntity> = titles.associateBy { it.ratingKey }

    /** An episode's show. */
    val showOf: Map<String, String> = episodes.associate { it.ratingKey to it.showKey }

    private val episodesByShow = episodes.groupBy { it.showKey }
    private val playsByTitle = plays.groupBy { showOf[it.ratingKey] ?: it.ratingKey }

    /** Its features, collections and episode order ([Catalog.of]): worked out once, kept with it, gone with it. */
    internal val catalog: Catalog by lazy { Catalog(this) }

    /** A show's episodes; none for a movie. */
    fun episodesOf(showKey: String): List<EpisodeRow> = episodesByShow[showKey].orEmpty()

    /** A movie's plays in history, or every play of a show's episodes. */
    fun playsOf(titleKey: String): List<PlayEntity> = playsByTitle[titleKey].orEmpty()

    /** When a movie, or any of a show's episodes, was last watched, finished or not. */
    fun lastViewed(titleKey: String): Long? = listOfNotNull(
        byKey[titleKey]?.lastViewedAt,
        episodesOf(titleKey).mapNotNull { it.lastViewedAt }.maxOrNull(),
        playsOf(titleKey).maxOfOrNull { it.viewedAt },
    ).maxOrNull()

    companion object {
        suspend fun read(dao: SuggestLibraryDao) = Library(dao.titles(), dao.episodes(), dao.plays(), dao.continueWatching())
    }
}
