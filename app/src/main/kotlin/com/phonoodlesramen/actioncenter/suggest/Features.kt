package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity

/** What a title is like: taste weighs these, and More like this matches them. */
enum class FeatureKind { GENRE, COLLECTION, DIRECTOR, ACTOR, DECADE, RATING, LENGTH }

data class Feature(val kind: FeatureKind, val value: String)

/**
 * A movie's or show's features (an episode is scored as its show). The tags come from the
 * title's details once filled (S11); labels aren't features, they only feed Seasonal. Pure.
 */
object Features {
    fun of(title: ItemEntity): Set<Feature> = buildSet {
        fun tags(kind: FeatureKind, joined: String) = joined.split('|').map(String::trim).filter(String::isNotEmpty).forEach { add(Feature(kind, it)) }
        tags(FeatureKind.GENRE, title.genres)
        tags(FeatureKind.COLLECTION, title.collections)
        tags(FeatureKind.DIRECTOR, title.directors)
        tags(FeatureKind.ACTOR, title.actors)
        title.year?.let { add(Feature(FeatureKind.DECADE, "${it / 10 * 10}s")) }
        ratingBand(title.contentRating)?.let { add(Feature(FeatureKind.RATING, it)) }
        if (title.type == "movie") lengthBand(title.durationMs)?.let { add(Feature(FeatureKind.LENGTH, it)) }
    }

    /** US film and TV ratings in four bands; unrated and unknown ones have none. */
    fun ratingBand(contentRating: String?): String? = when (contentRating?.trim()?.uppercase()) {
        "G", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G" -> KIDS
        "PG", "TV-PG" -> FAMILY
        "PG-13", "TV-14" -> TEEN
        "R", "NC-17", "TV-MA" -> ADULT
        else -> null
    }

    /** A film's length: under 1h 30m, under 2h, under 2h 30m, or longer. */
    fun lengthBand(durationMs: Long?): String? {
        val minutes = (durationMs ?: return null) / 60_000
        return when {
            minutes <= 0 -> null
            minutes < 90 -> "short"
            minutes < 120 -> "standard"
            minutes < 150 -> "long"
            else -> "epic"
        }
    }

    const val KIDS = "kids"
    const val FAMILY = "family"
    const val TEEN = "teen"
    const val ADULT = "adult"
}
