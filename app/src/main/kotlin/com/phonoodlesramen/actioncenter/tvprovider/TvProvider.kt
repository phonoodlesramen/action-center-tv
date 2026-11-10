package com.phonoodlesramen.actioncenter.tvprovider

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri

/**
 * A row another app published to Android TV's provider: a Watch Next entry ("continue
 * watching") or a program in one of its home-screen channels.
 */
data class TvProgram(
    val id: Long,
    val kind: Kind,
    val packageName: String,
    val title: String,
    /** A `TvContract.PreviewPrograms.TYPE_*` value (movie, TV episode, clip, channel…). */
    val type: Int? = null,
    /** Watch Next only: a `TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_*` value. */
    val watchNextType: Int? = null,
    /** Preview only: the channel it belongs to. */
    val channelId: Long? = null,
    val episodeTitle: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val description: String? = null,
    val posterArtUri: String? = null,
    val thumbnailUri: String? = null,
    /** What opening it starts, as `Intent.toUri(URI_INTENT_SCHEME)` or a plain link. */
    val intentUri: String? = null,
    val durationMillis: Long? = null,
    val positionMillis: Long? = null,
    val lastEngagementMillis: Long? = null,
    val live: Boolean = false,
) {
    enum class Kind { WATCH_NEXT, PREVIEW }
}

/** A home-screen channel (a row of preview programs) another app published. */
data class TvChannel(
    val id: Long,
    val packageName: String,
    val displayName: String,
    val appLinkIntentUri: String? = null,
)

/** Everything we can read from the provider, for the rows and the Diagnostics dump. */
data class TvProviderSnapshot(val channels: List<TvChannel>, val programs: List<TvProgram>)

/** Reads other apps' rows from Android TV's provider (needs `READ_TV_LISTINGS`). */
interface TvProviderReader {
    fun canRead(): Boolean
    fun read(): TvProviderSnapshot
}

/**
 * The provider returns rows other apps marked searchable (the default), and throws on a
 * `selection`, so everything is read whole and filtered here (AOSP TvProvider).
 */
class AndroidTvProviderReader(private val context: Context) : TvProviderReader {
    override fun canRead() =
        context.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    override fun read() = TvProviderSnapshot(
        channels = query(TvContract.Channels.CONTENT_URI) { TvRows.channel(it) },
        programs = query(TvContract.WatchNextPrograms.CONTENT_URI) { TvRows.program(it, TvProgram.Kind.WATCH_NEXT) } +
            query(TvContract.PreviewPrograms.CONTENT_URI) { TvRows.program(it, TvProgram.Kind.PREVIEW) },
    )

    private fun <T : Any> query(uri: Uri, row: (Cursor) -> T?): List<T> =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) row(cursor)?.let(::add) }
        }.orEmpty()

    private companion object {
        const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
    }
}

/**
 * Cursor rows to [TvProgram] and [TvChannel]. Columns are looked up by name, because the
 * provider leaves out the ones a non-system caller may not read.
 */
object TvRows {
    fun program(cursor: Cursor, kind: TvProgram.Kind): TvProgram? {
        val title = cursor.string("title") ?: return null
        return TvProgram(
            id = cursor.long("_id") ?: return null,
            kind = kind,
            packageName = cursor.string("package_name").orEmpty(),
            title = title,
            type = cursor.long("type")?.toInt(),
            watchNextType = if (kind == TvProgram.Kind.WATCH_NEXT) cursor.long("watch_next_type")?.toInt() else null,
            channelId = if (kind == TvProgram.Kind.PREVIEW) cursor.long("channel_id") else null,
            episodeTitle = cursor.string("episode_title"),
            season = cursor.string("season_display_number"),
            episode = cursor.string("episode_display_number"),
            description = cursor.string("short_description"),
            posterArtUri = cursor.string("poster_art_uri"),
            thumbnailUri = cursor.string("thumbnail_uri"),
            intentUri = cursor.string("intent_uri"),
            durationMillis = cursor.long("duration_millis"),
            positionMillis = cursor.long("last_playback_position_millis"),
            lastEngagementMillis = cursor.long("last_engagement_time_utc_millis"),
            live = cursor.long("live") == 1L,
        )
    }

    fun channel(cursor: Cursor): TvChannel? = TvChannel(
        id = cursor.long("_id") ?: return null,
        packageName = cursor.string("package_name").orEmpty(),
        displayName = cursor.string("display_name").orEmpty(),
        appLinkIntentUri = cursor.string("app_link_intent_uri"),
    )

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.long(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }
}

/**
 * Diagnostics: one line per channel and program, grouped by app, so the Shield's rows
 * can be read with logcat. Query parameters that look like secrets are blanked.
 */
fun TvProviderSnapshot.dumpLines(perApp: Int = 20): List<String> = buildList {
    val names = channels.associateBy { it.id }
    channels.groupBy { it.packageName }.toSortedMap().forEach { (pkg, list) ->
        list.forEach { add("tv channel pkg=$pkg id=${it.id} name=\"${it.displayName}\" link=${redact(it.appLinkIntentUri)}") }
    }
    programs.groupBy { it.kind to it.packageName }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (key, list) ->
        list.take(perApp).forEach { p ->
            val channel = p.channelId?.let { names[it]?.displayName }?.let { " channel=\"$it\"" }.orEmpty()
            val episode = listOfNotNull(p.season?.let { "S$it" }, p.episode?.let { "E$it" }).joinToString("").ifEmpty { null }
            add(
                "tv program ${key.first} pkg=${key.second}$channel type=${typeName(p.type)} live=${p.live} " +
                    "title=\"${p.title}\"${episode?.let { " $it" }.orEmpty()} intent=${redact(p.intentUri)}",
            )
        }
        if (list.size > perApp) add("tv program ${key.first} pkg=${key.second}: ${list.size - perApp} more")
    }
}

/**
 * Parameter names that carry secrets: anything ending in "token" (X-Plex-Token), with
 * "auth" or "session" in it, a signature, or a bare key / api_key. Not ratingKey.
 */
private val SECRET_PARAM = Regex(
    """([?&;](?:[\w.-]*token|[\w.-]*auth[\w.-]*|[\w.-]*session[\w.-]*|sig|signature|key|api[_-]?key)=)[^&;#]*""",
    RegexOption.IGNORE_CASE,
)

/** Blanks the values of query (and intent-extra) parameters named like tokens, keys or signatures. */
fun redact(uri: String?): String = uri?.replace(SECRET_PARAM, "$1…") ?: "none"

/** `TvContract.PreviewPrograms.TYPE_*` names, for the dump. */
fun typeName(type: Int?): String = when (type) {
    null -> "none"
    0 -> "movie"
    1 -> "series"
    2 -> "season"
    3 -> "episode"
    4 -> "clip"
    5 -> "event"
    6 -> "channel"
    7 -> "track"
    8 -> "album"
    9 -> "artist"
    10 -> "playlist"
    11 -> "station"
    12 -> "game"
    else -> "type$type"
}
