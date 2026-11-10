package com.phonoodlesramen.actioncenter.plex

/**
 * One of Plex's search hubs (Phase 15, the official spec's "Search Hub"): its [type] (movie,
 * show, episode, collection, or a tag's, such as actor or genre) and the titles in it, in the
 * server's order. Hubs come best first.
 */
data class PlexSearchHub(val type: String, val title: String, val hits: List<PlexSearchHit>)

/**
 * A title Plex's search found. When it isn't a direct match, [reason] and [reasonTitle] say
 * why (official spec): "actor" and "Arnold Schwarzenegger" for a film found through him, or a
 * genre's or a collection's name. Never a token or URL: [thumb] and [art] are server paths.
 */
data class PlexSearchHit(
    val ratingKey: String,
    val type: String,
    val title: String,
    val year: Int? = null,
    val contentRating: String? = null,
    val thumb: String? = null,
    val art: String? = null,
    /** An episode's show: its title and ratingKey, and the episode's season and number. */
    val showTitle: String? = null,
    val showKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val reason: String? = null,
    val reasonTitle: String? = null,
)
