package com.phonoodlesramen.actioncenter.guide

import androidx.room.RoomRawQuery
import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a channel plays, as portable JSON (G0): the phone editor (Phase 2.7) or a server
 * later can read and write it. Every filter is optional; an empty list means "any".
 */
@Serializable
data class ChannelRule(
    /** [MOVIE] and/or [EPISODE]. Shows in order and rotating shows use episodes only. */
    val types: List<String> = listOf(MOVIE),
    /** Library section keys. */
    val sections: List<String> = emptyList(),
    /** Any of these genres (an episode has its show's). */
    val genres: List<String> = emptyList(),
    /** Show ratingKeys. */
    val shows: List<String> = emptyList(),
    /** Any of these Plex collections, by name, as genres are (G18); an episode is in its show's. */
    val collections: List<String> = emptyList(),
    /** Any of these studios, by their exact name (Phase 9, F1); an episode has its show's (F2). */
    val studios: List<String> = emptyList(),
    /** Any of these Plex labels, by name, as genres are (Phase 9, F1); an episode has its show's (F2). */
    val labels: List<String> = emptyList(),
    /** Decades by their first year, e.g. 1980 for the 80s. */
    val decades: List<Int> = emptyList(),
    /** Released at least this many years ago, rolling (U18: a Classic is 25+). */
    val minAgeYears: Int? = null,
    /** Content ratings, e.g. "PG", "TV-14" (an episode without one has its show's). */
    val contentRatings: List<String> = emptyList(),
    val minMinutes: Int? = null,
    val maxMinutes: Int? = null,
    /** Only titles never watched. */
    val unwatched: Boolean = false,
    /** Season 0 episodes; left out unless asked for, since they're out of order. */
    val specials: Boolean = false,
    val order: Order = Order.SHUFFLE,
) {
    @Serializable
    enum class Order {
        /** A shuffle bag: nothing repeats until every title has played. */
        @SerialName("shuffle")
        SHUFFLE,

        /** Episodes in order, show by show (a marathon), then from the start again. */
        @SerialName("in_order")
        IN_ORDER,

        /** One episode from each show in turn, each show in order (genre TV, TV Mix). */
        @SerialName("rotate_shows")
        ROTATE_SHOWS,
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MOVIE = "movie"
        const val EPISODE = "episode"

        /** Unknown fields are ignored, so an older launcher reads a newer editor's rules. */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun fromJson(text: String): ChannelRule = json.decodeFromString(serializer(), text)
    }
}

/**
 * A [ChannelRule] as SQL over the Plex cache's `items` (`i`), each episode joined to its
 * show (`s`) for genres, collections, labels, studio, year and rating. [year] is this year, for
 * [ChannelRule.minAgeYears].
 */
internal class RuleSql(rule: ChannelRule, year: Int) {
    private val where: String
    private val args: List<Any>

    init {
        val clauses = mutableListOf(
            // What the guide can schedule: GuideLibraryDao's test.
            "i.partKey IS NOT NULL",
            "i.durationMs >= ${MIN_DURATION_MS}",
        )
        val args = mutableListOf<Any>()
        fun inList(column: String, values: List<Any>) {
            clauses += "$column IN (${values.joinToString { "?" }})"
            args.addAll(values)
        }

        val types = if (rule.order == ChannelRule.Order.SHUFFLE) {
            rule.types.filter { it == ChannelRule.MOVIE || it == ChannelRule.EPISODE }.distinct()
                .ifEmpty { listOf(ChannelRule.MOVIE, ChannelRule.EPISODE) }
        } else {
            listOf(ChannelRule.EPISODE)
        }
        inList("i.type", types)
        if (rule.sections.isNotEmpty()) inList("i.sectionKey", rule.sections)
        if (rule.shows.isNotEmpty()) inList("i.showKey", rule.shows)
        // Genres, collections and labels are "|"-joined names; an episode has its show's.
        fun anyTag(column: String, names: List<String>) {
            if (names.isEmpty()) return
            val tags = "CASE WHEN i.type = 'episode' THEN COALESCE(s.$column, '') ELSE i.$column END"
            clauses += names.joinToString(" OR ", "(", ")") { "('|' || $tags || '|') LIKE ? ESCAPE '\\'" }
            args.addAll(names.map { "%|${it.likeEscaped()}|%" })
        }
        anyTag("genres", rule.genres)
        anyTag("collections", rule.collections)
        anyTag("labels", rule.labels)
        // A title has one studio, matched by its exact name.
        if (rule.studios.isNotEmpty()) inList(STUDIO, rule.studios)
        val releaseYear = "COALESCE(i.year, s.year)"
        if (rule.decades.isNotEmpty()) inList("($releaseYear / 10) * 10", rule.decades.map { it.toLong() / 10 * 10 })
        rule.minAgeYears?.let {
            clauses += "$releaseYear <= ?"
            args += (year - it).toLong()
        }
        if (rule.contentRatings.isNotEmpty()) inList(RATING, rule.contentRatings)
        rule.minMinutes?.let {
            clauses += "i.durationMs >= ?"
            args += it * 60_000L
        }
        rule.maxMinutes?.let {
            clauses += "i.durationMs <= ?"
            args += it * 60_000L
        }
        if (rule.unwatched) clauses += "i.viewCount = 0"
        if (!rule.specials) clauses += "(i.type != 'episode' OR COALESCE(i.season, 1) != 0)"
        where = clauses.joinToString(" AND ")
        this.args = args
    }

    /** `SELECT [select] FROM … WHERE <the rule> [and] …[tail]`, with [and]'s own arguments after the rule's. */
    fun query(select: String, and: String? = null, andArgs: List<Any> = emptyList(), tail: String = ""): RoomRawQuery {
        val sql = buildString {
            append("SELECT ").append(select)
            append(" FROM items AS i LEFT JOIN items AS s ON s.ratingKey = i.showKey")
            append(" WHERE ").append(where)
            if (and != null) append(" AND ").append(and)
            if (tail.isNotEmpty()) append(' ').append(tail)
        }
        val all = args + andArgs
        return RoomRawQuery(sql) { statement -> all.forEachIndexed { i, arg -> statement.bindArg(i + 1, arg) } }
    }

    companion object {
        /** A title shorter than a minute is a broken file, not a program. */
        const val MIN_DURATION_MS = 60_000L

        /** [com.phonoodlesramen.actioncenter.plex.cache.GuidePoolEntry]'s columns. */
        const val ENTRY_COLUMNS = "i.ratingKey AS ratingKey, i.type AS type, i.durationMs AS durationMs, " +
            "i.showKey AS showKey, i.season AS season, i.episode AS episode"

        /** A title's content rating: an episode without one has its show's. */
        const val RATING = "COALESCE(i.contentRating, s.contentRating)"

        /** A title's studio: an episode has its show's (F2). */
        private const val STUDIO = "CASE WHEN i.type = 'episode' THEN COALESCE(s.studio, '') ELSE i.studio END"

        /** Episodes in order: unnumbered ones last, the ratingKey to break ties. */
        const val EPISODE_ORDER = "ORDER BY COALESCE(i.season, 99999), COALESCE(i.episode, 99999), i.ratingKey"

        private fun String.likeEscaped() = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /** Binds [arg] at [index] (from 1) by its type, as `SimpleSQLiteQuery` did before Phase 8. */
        private fun SQLiteStatement.bindArg(index: Int, arg: Any?) {
            when (arg) {
                null -> bindNull(index)
                is ByteArray -> bindBlob(index, arg)
                is Float -> bindDouble(index, arg.toDouble())
                is Double -> bindDouble(index, arg)
                is Long -> bindLong(index, arg)
                is Int -> bindLong(index, arg.toLong())
                is Short -> bindLong(index, arg.toLong())
                is Byte -> bindLong(index, arg.toLong())
                is String -> bindText(index, arg)
                is Boolean -> bindLong(index, if (arg) 1 else 0)
                else -> throw IllegalArgumentException("Can't bind $arg at index $index: a rule's arguments are numbers and text")
            }
        }
    }
}
