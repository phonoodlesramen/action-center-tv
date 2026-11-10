package com.phonoodlesramen.actioncenter.content

/** Tonight's Pick: every pick carries a human-readable reason [REQ]. */
data class Pick(
    val id: String,
    val title: String,
    val meta: String,
    val reason: String,
    val playLabel: String = "Play",
    /** Plex art path for the backdrop; null draws placeholder art. */
    val image: String? = null,
    /** The Plex trailer extra's ratingKey; null searches YouTube instead. */
    val trailerKey: String? = null,
    /** Narrows the YouTube search for a trailer (remakes share titles). */
    val year: Int? = null,
    /** What feedback is about: the movie, or an episode's show. */
    val titleKey: String = id,
    /** Why this pick? adds these after the reason ("Rated 8.4", "New this week"). */
    val evidence: List<String> = emptyList(),
    /** The rule-based reason when an AI blurb shows as [reason] (M9): Why this pick? starts with it. */
    val because: String? = null,
    /** Plex logo path (Phase 12): the hero shows it in the title's place. Null shows the title. */
    val logo: String? = null,
)

data class QuickItem(
    val id: String,
    val title: String,
    val meta: String,
    val image: String? = null,
    /** The movie, or an episode's show: what Hide from Quick Watch hides. */
    val titleKey: String = id,
)

data class UpNextItem(
    val id: String,
    val title: String,
    val episode: String,
    val timeLeft: String,
    /** 0..1 */
    val progress: Float,
    val image: String? = null,
    /** The movie, or an episode's show: what Remove from Up Next removes until you play it again. */
    val titleKey: String = id,
)

/** What the media cards show. Phase 2 builds this from Plex; Phase 3 ranks it. */
data class HomeContent(
    val picks: List<Pick>,
    /** The lens card's titles, per lens (U17). A lens with none is skipped. */
    val lenses: Map<Lens, List<LensPick>>,
    val quickWatch: List<QuickItem>,
    /** "10:30": Quick Watch items fit before this time. */
    val quickWatchCutoff: String,
    val upNext: List<UpNextItem>,
    /** From the Plex cache: Play hands off with these ids (D11). Sample content can't play. */
    val fromPlex: Boolean = false,
    /** More like this (S10): titles like each of [picks], by the pick's id. */
    val moreLike: Map<String, List<LensPick>> = emptyMap(),
)

/** Null means there's nothing to show yet (Plex not connected): the first-run state [U14]. */
fun interface ContentSource {
    fun content(): HomeContent?
}

object EmptyContentSource : ContentSource {
    override fun content(): HomeContent? = null
}
