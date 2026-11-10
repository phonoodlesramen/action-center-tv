package com.phonoodlesramen.actioncenter.search

import com.phonoodlesramen.actioncenter.suggest.Features

/**
 * A word Search reads as a filter rather than sending it to Plex (Phase 15, Q2), shown as a
 * chip under the field.
 */
sealed interface SearchChip {
    val label: String

    /** A decade: its titles' year from [start] to [start] + 9. */
    data class Decade(val start: Int) : SearchChip {
        override val label: String get() = "${start}s"
    }

    /** "kids" or "family": rated G to PG, TV-Y to TV-PG. */
    data object Kids : SearchChip {
        override val label = "Kids & family"
    }

    data object Movies : SearchChip {
        override val label = "Movies"
    }

    data object Shows : SearchChip {
        override val label = "Shows"
    }

    /** "new": added in the last [SearchWords.NEW_DAYS] days. */
    data object New : SearchChip {
        override val label = "New"
    }
}

/**
 * What was typed, read (Phase 15, Q2): [query] goes to Plex, [chips] narrow what comes back.
 * [text] is what was typed, trimmed, for the title search that keeps "The Truman Show" a
 * show's name rather than a "show" chip.
 */
data class SearchWords(val text: String, val query: String, val chips: List<SearchChip>) {
    /** Whether anything was read as a chip, so the search runs twice: as typed, and narrowed. */
    val narrowed: Boolean get() = chips.isNotEmpty()

    companion object {
        /** "New" is what was added this many days ago or since. */
        const val NEW_DAYS = 30L

        private val KIDS = setOf("kids", "kid", "kids'", "family", "children", "childrens", "children's")
        private val MOVIES = setOf("movie", "movies", "film", "films")
        private val SHOWS = setOf("show", "shows", "series", "tv")
        private val NEW = setOf("new", "newest", "recent", "latest")

        /** "90s", "'90s", "1990s"; the 00s, 10s and 20s are this century's. */
        private val DECADE = Regex("^'?(\\d{2})s$|^((?:19|20)\\d0)s$")

        fun read(typed: String): SearchWords {
            val text = typed.trim().replace(Regex("\\s+"), " ")
            val chips = LinkedHashSet<SearchChip>()
            val kept = text.split(' ').filter { it.isNotEmpty() }.filter { word ->
                val chip = chipOf(word.lowercase().trim(',', '.', '!', '?'))
                if (chip != null) chips += chip
                chip == null
            }
            return SearchWords(text, kept.joinToString(" "), chips.toList())
        }

        private fun chipOf(word: String): SearchChip? {
            DECADE.matchEntire(word)?.let { match ->
                match.groupValues[2].takeIf { it.isNotEmpty() }?.let { return SearchChip.Decade(it.toInt()) }
                val short = match.groupValues[1].toInt()
                if (short % 10 != 0) return null
                return SearchChip.Decade(if (short < 30) 2000 + short else 1900 + short)
            }
            return when (word) {
                in KIDS -> SearchChip.Kids
                in MOVIES -> SearchChip.Movies
                in SHOWS -> SearchChip.Shows
                in NEW -> SearchChip.New
                else -> null
            }
        }
    }
}

/** Whether a title of [type], [year], [contentRating] and [addedAtSeconds] passes every one of [chips]. */
fun passes(chips: List<SearchChip>, type: String, year: Int?, contentRating: String?, addedAtSeconds: Long?, nowSeconds: Long): Boolean = chips.all { chip ->
    when (chip) {
        is SearchChip.Decade -> year != null && year in chip.start..chip.start + 9
        SearchChip.Kids -> Features.ratingBand(contentRating).let { it == Features.KIDS || it == Features.FAMILY }
        SearchChip.Movies -> type == "movie"
        SearchChip.Shows -> type == "show" || type == "episode"
        SearchChip.New -> addedAtSeconds != null && nowSeconds - addedAtSeconds <= SearchWords.NEW_DAYS * 86_400
    }
}
