package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase

/**
 * How a channel looks in the guide (I1): a [glyph] from the bundled set, and [logo], art drawn
 * over it. With neither, the app draws the channel's monogram (I7).
 */
data class ChannelLook(
    /** A glyph's name, one of [ChannelGlyphs.ALL]; null when nothing matched. */
    val glyph: String? = null,
    /** A Plex image path: a one-show channel's show poster. Never a token or a host. */
    val logo: String? = null,
)

/**
 * The bundled glyphs, each named after the Material Symbol the app draws for it
 * (`rocket_launch`), and which one fits a channel. A name is what's stored, so it never
 * changes; the app keeps the paths.
 */
object ChannelGlyphs {
    /** Each glyph and the words that pick it, lower case, letters and digits only. */
    private val SET: List<Pair<String, List<String>>> = listOf(
        "movie" to listOf("movie", "movies", "film", "films", "cinema"),
        "tv_gen" to listOf("tv", "television", "series"),
        "theaters" to listOf("classic", "classics", "vintage", "oldies"),
        "local_fire_department" to listOf("action", "martialarts", "superhero", "superheroes"),
        "explore" to listOf("adventure", "adventures"),
        "cruelty_free" to listOf("animation", "animated", "cartoon", "cartoons", "toon", "toons"),
        "toys" to listOf("kids", "kid", "children", "childrens", "preschool"),
        "sentiment_very_satisfied" to listOf("comedy", "comedies", "sitcom", "sitcoms", "funny"),
        "fingerprint" to listOf("crime", "detective", "detectives", "heist", "heists", "truecrime"),
        "public" to listOf("documentary", "documentaries", "docs", "nature", "travel", "wildlife"),
        "theater_comedy" to listOf("drama", "dramas"),
        "family_restroom" to listOf("family", "families"),
        "castle" to listOf("fantasy", "fairytale", "fairytales", "medieval"),
        "history_edu" to listOf("history", "historical", "biography", "biopic", "biopics"),
        "featured_seasonal_and_gifts" to listOf("holiday", "holidays", "christmas", "xmas", "festive"),
        "skull" to listOf("horror", "halloween", "scary", "spooky", "slasher", "zombie", "zombies"),
        "music_note" to listOf("music", "musical", "musicals", "concert", "concerts"),
        "visibility" to listOf("mystery", "mysteries", "thriller", "thrillers", "suspense", "noir"),
        "favorite" to listOf("romance", "romantic", "love", "romcom", "romcoms"),
        "rocket_launch" to listOf("scifi", "sciencefiction", "space"),
        "emoji_events" to listOf("sport", "sports", "gameshow", "gameshows", "competition"),
        "sports_football" to listOf("football", "nfl"),
        "military_tech" to listOf("war", "military"),
        "landscape" to listOf("western", "westerns", "cowboy", "cowboys"),
        "newspaper" to listOf("news"),
        "mic" to listOf("talk", "talkshow", "talkshows", "standup"),
        "restaurant" to listOf("food", "cooking", "cookery", "baking"),
        "animation" to listOf("anime"),
    )

    /** Every glyph, in the order the phone editor offers them. */
    val ALL: List<String> = SET.map { it.first }

    private val BY_WORD: Map<String, String> = buildMap {
        for ((glyph, words) in SET) words.forEach { put(it, glyph) }
    }

    /**
     * The glyph for a channel called [name] that plays [rule], first match wins:
     * 1. a one-show channel ([isOneShow]): its show's genres, [showGenres];
     * 2. the words in its name ("Holidays", "90's Family", "TV Mix");
     * 3. the rule's genres, in order;
     * 4. its labels, then its collections ("Christmas").
     *
     * The name comes before the rule's genres: it's the house's own word for the channel. A
     * one-show channel goes by its show, so "Family Guy Marathon" isn't taken for Family.
     */
    fun pick(name: String, rule: ChannelRule?, showGenres: List<String> = emptyList()): String? {
        val oneShow = rule != null && isOneShow(rule)
        val texts = buildList {
            if (oneShow) addAll(showGenres)
            add(name)
            if (rule != null) {
                addAll(rule.genres)
                addAll(rule.labels)
                addAll(rule.collections)
            }
        }
        return texts.firstNotNullOfOrNull(::match)
    }

    /** Episodes of exactly one show: the starter lineup's marathons, or one made like them. */
    fun isOneShow(rule: ChannelRule): Boolean = rule.shows.size == 1 && rule.types == listOf(ChannelRule.EPISODE)

    /**
     * The first word in [text] with a glyph, trying each word joined to the next first, so
     * "Sci-Fi" is "scifi" and "Science Fiction" is "sciencefiction".
     */
    fun match(text: String): String? {
        val words = text.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }
        for (i in words.indices) {
            if (i + 1 < words.size) BY_WORD[words[i] + words[i + 1]]?.let { return it }
            BY_WORD[words[i]]?.let { return it }
        }
        return null
    }

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
}

/**
 * Works out a channel's [ChannelLook]: its glyph, and a one-show channel's show poster from
 * the Plex cache ([plex]). Without a cache it picks from the name and rule alone. Read with
 * each lineup: a one-show channel costs one lookup by key.
 */
class ChannelLooks(plex: PlexDatabase?) {
    private val dao = plex?.guideLibrary()

    suspend fun of(name: String, ruleJson: String): ChannelLook = of(name, ruleOf(ruleJson))

    suspend fun of(name: String, rule: ChannelRule?): ChannelLook {
        val show = rule?.takeIf(ChannelGlyphs::isOneShow)?.let { dao?.item(it.shows.single()) }
        val genres = show?.genres.orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }
        return ChannelLook(ChannelGlyphs.pick(name, rule, genres), show?.thumb?.takeIf { it.isNotBlank() })
    }

    private fun ruleOf(json: String): ChannelRule? = try {
        ChannelRule.fromJson(json)
    } catch (_: IllegalArgumentException) {
        null
    }
}
