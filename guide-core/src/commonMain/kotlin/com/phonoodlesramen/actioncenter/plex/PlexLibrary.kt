package com.phonoodlesramen.actioncenter.plex

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where to send server requests: the linked connection and the server token. */
data class ServerAccess(val baseUri: String, val token: String)

/** A library section. Only Movies and TV are cached (P7). */
data class PlexSection(val key: String, val type: String, val title: String)

/** A movie, show or episode, with the fields the cards and Phase 3 need. Times are epoch seconds. */
data class PlexItem(
    val ratingKey: String,
    val type: String,
    val title: String,
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val viewOffsetMs: Long? = null,
    val viewCount: Int = 0,
    val lastViewedAt: Long? = null,
    val addedAt: Long? = null,
    val updatedAt: Long? = null,
    val contentRating: String? = null,
    val audienceRating: Double? = null,
    val rating: Double? = null,
    val thumb: String? = null,
    val art: String? = null,
    val showThumb: String? = null,
    val showArt: String? = null,
    val genres: List<String> = emptyList(),
    /** A movie's trailer: the ratingKey of its primary extra, or null when it has none. */
    val trailerKey: String? = null,
    /** Plex's description, for the guide's info panel. */
    val summary: String? = null,
    /** An episode's show (`grandparentRatingKey`). */
    val showKey: String? = null,
    /** The title's first file, for the guide's player. Null when the listing has none. */
    val media: PlexMedia? = null,
    // Phase 3 (suggestions): what the engine scores on.
    /** Your star rating, 0-10 (0 to 5 stars), and when you gave it (epoch seconds). Null when unrated. */
    val userRating: Double? = null,
    val lastRatedAt: Long? = null,
    /** Plex's `originallyAvailableAt`: "YYYY-MM-DD". */
    val releaseDate: String? = null,
    /** A show's episodes, and how many of them you've watched (`leafCount`, `viewedLeafCount`). */
    val episodeCount: Int? = null,
    val watchedEpisodeCount: Int? = null,
    val collections: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    /** The cast, as Plex lists them: a listing may carry only the first few (the library check says). */
    val actors: List<String> = emptyList(),
    /** A movie's or show's studio (Phase 9), from the listing or the details, whichever has it. Null when it has none. */
    val studio: String? = null,
    /**
     * The title's logo (Phase 12): the path of its `clearLogo` image, e.g.
     * `/library/metadata/22/clearLogo/1730116804`. Null when it has none. An episode's card
     * uses its show's.
     */
    val logo: String? = null,
)

/**
 * A title's first file as Plex describes it: what the guide plays and what the Playback
 * check picks from. Never a token, and never the file's path on the server.
 */
data class PlexMedia(
    /** The part's own path on the server, e.g. `/library/parts/123/1600000000/file.mkv`. */
    val partKey: String,
    val container: String? = null,
    val videoCodec: String? = null,
    /** E.g. "main 10" for HEVC 10-bit. */
    val videoProfile: String? = null,
    /** E.g. "1080" or "4k". */
    val videoResolution: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val sizeBytes: Long? = null,
    val bitrateKbps: Int? = null,
    /** Only from a title's details (its video stream): [HDR10], [HLG] or [DOLBY_VISION]; null for SDR or unknown. */
    val dynamicRange: String? = null,
) {
    companion object {
        const val HDR10 = "hdr10"
        const val HLG = "hlg"
        const val DOLBY_VISION = "dolby-vision"
    }
}

/** One play from the server's history. [showKey] is an episode's show (`grandparentRatingKey`). */
data class PlexPlay(
    val historyKey: String,
    val ratingKey: String,
    val type: String,
    val title: String,
    val viewedAt: Long,
    val thumb: String?,
    val showKey: String? = null,
)

/**
 * An account the server knows (`/accounts`, Phase 5): you are 1 on your own server, and
 * each Plex Home user has their own, under which their plays are logged (research §4).
 */
data class PlexServerAccount(val id: Long, val name: String)

data class PlexPage<T>(val items: List<T>, val offset: Int, val totalSize: Int?)

/** What the sync needs from the server, so it can be tested without one. */
interface PlexServerApi {
    suspend fun continueWatching(access: ServerAccess, count: Int = 12): List<PlexItem>
    suspend fun sections(access: ServerAccess): List<PlexSection>
    suspend fun items(access: ServerAccess, section: String, type: Int, start: Int, size: Int, updatedSince: Long? = null): PlexPage<PlexItem>
    suspend fun history(access: ServerAccess, sinceSeconds: Long, accountId: Int = 1, size: Int = 500): List<PlexPlay>

    /** One title's details, with its file's streams (HDR, Dolby Vision): `/library/metadata/{id}`. Null when it's gone. */
    suspend fun metadata(access: ServerAccess, ratingKey: String): PlexItem? = null

    /**
     * Several titles' details at once (`/library/metadata/{ids}`): their full genres, cast,
     * directors, collections and labels, which a section listing cuts short (S11). A title
     * that's gone is left out.
     */
    suspend fun details(access: ServerAccess, ratingKeys: List<String>): List<PlexItem> = emptyList()

    /** The server's accounts (`/accounts`, with your token): a kid's plays are logged under theirs (Phase 5, K2). */
    suspend fun accounts(access: ServerAccess): List<PlexServerAccount> = emptyList()

    /**
     * Where each title's credits start (millis), from its first credits marker: Plex Pass
     * credit detection, read with `includeMarkers=1` on `/library/metadata/{ids}`. A title
     * without one is left out (the Up Next credits fix).
     */
    suspend fun creditsStart(access: ServerAccess, ratingKeys: List<String>): Map<String, Long> = emptyMap()

    /**
     * Plex's own search (Phase 15, Q1; the official spec's "Search Hub", meant to be "called
     * as the user types"): every section, spell-checked, with related titles (an actor's
     * films, a genre's). Its hubs best first, up to [limit] titles each.
     */
    suspend fun search(access: ServerAccess, query: String, limit: Int = 12): List<PlexSearchHub> = emptyList()
}

/**
 * The linked server's API (official PMS spec): Continue Watching, library sections,
 * paged items and history.
 */
class PlexLibrary(
    private val http: OkHttpClient,
    private val headers: PlexHeaders,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlexServerApi {
    /** Plex's own Continue Watching: in-progress items and next episodes, most recent first (D15). */
    override suspend fun continueWatching(access: ServerAccess, count: Int): List<PlexItem> {
        val url = url(access, "/hubs/continueWatching").newBuilder().addQueryParameter("count", count.toString()).build()
        return get<HubsJson>(url, access).container.hubs.flatMap { it.metadata }.map { it.toItem() }
    }

    override suspend fun sections(access: ServerAccess): List<PlexSection> =
        get<SectionsJson>(url(access, "/library/sections/all"), access).container.directories
            .filter { !it.hidden }
            .map { PlexSection(it.key, it.type, it.title) }

    /**
     * One page of a section's items of [type] (1 movie, 2 show, 4 episode). [updatedSince]
     * (epoch seconds) asks only for items changed after it: the incremental sync.
     */
    override suspend fun items(access: ServerAccess, section: String, type: Int, start: Int, size: Int, updatedSince: Long?): PlexPage<PlexItem> {
        val builder = url(access, "/library/sections/$section/all").newBuilder()
            .addQueryParameter("type", type.toString())
            .addQueryParameter("includeGuids", "1")
        // Media query: "updatedAt>>=<epoch>" means "updated after".
        if (updatedSince != null) builder.addQueryParameter("updatedAt>>", updatedSince.toString())
        val page = get<ItemsJson>(builder.build(), access, start to size).container
        return PlexPage(page.metadata.map { it.toItem() }, page.offset ?: start, page.totalSize)
    }

    /** The account's plays since [sinceSeconds], newest first. The owner is accountID 1 on its own server (P5). */
    override suspend fun history(access: ServerAccess, sinceSeconds: Long, accountId: Int, size: Int): List<PlexPlay> {
        val url = url(access, "/status/sessions/history/all").newBuilder()
            .addQueryParameter("sort", "viewedAt:desc")
            .addQueryParameter("accountID", accountId.toString())
            .addQueryParameter("viewedAt>", sinceSeconds.toString())
            .build()
        return get<HistoryJson>(url, access, 0 to size).container.metadata.mapNotNull { play ->
            val key = play.ratingKey ?: return@mapNotNull null
            PlexPlay(play.historyKey ?: "$key@${play.viewedAt}", key, play.type, play.title, play.viewedAt, play.thumb, play.grandparentRatingKey)
        }
    }

    override suspend fun accounts(access: ServerAccess): List<PlexServerAccount> =
        get<AccountsJson>(url(access, "/accounts"), access).container.accounts.map { PlexServerAccount(it.id, it.name.orEmpty()) }

    override suspend fun metadata(access: ServerAccess, ratingKey: String): PlexItem? {
        require(ratingKey.all(Char::isDigit)) { "Bad Plex ratingKey: $ratingKey" }
        return try {
            get<ItemsJson>(url(access, "/library/metadata/$ratingKey"), access).container.metadata.firstOrNull()?.toItem()
        } catch (e: PlexException.Failed) {
            if (e.code == 404) null else throw e
        }
    }

    override suspend fun details(access: ServerAccess, ratingKeys: List<String>): List<PlexItem> {
        if (ratingKeys.isEmpty()) return emptyList()
        require(ratingKeys.all { it.isNotEmpty() && it.all(Char::isDigit) }) { "Bad Plex ratingKey in $ratingKeys" }
        return try {
            get<ItemsJson>(url(access, "/library/metadata/${ratingKeys.joinToString(",")}"), access).container.metadata.map { it.toItem() }
        } catch (e: PlexException.Failed) {
            if (e.code == 404) emptyList() else throw e
        }
    }

    override suspend fun creditsStart(access: ServerAccess, ratingKeys: List<String>): Map<String, Long> {
        val keys = ratingKeys.filter { it.isNotEmpty() && it.all(Char::isDigit) }
        if (keys.isEmpty()) return emptyMap()
        val url = url(access, "/library/metadata/${keys.joinToString(",")}").newBuilder()
            .addQueryParameter("includeMarkers", "1")
            .build()
        val titles = try {
            get<MarkersJson>(url, access).container.metadata
        } catch (e: PlexException.Failed) {
            if (e.code == 404) return emptyMap() else throw e
        }
        return titles.mapNotNull { title ->
            title.markers.filter { it.isCredits }.mapNotNull { it.startTimeOffset }.minOrNull()?.let { title.ratingKey to it }
        }.toMap()
    }

    /**
     * `/hubs/search?query=…&limit=…&includeCollections=1`. A hub lists its titles under
     * `Metadata`, or `Directory` for shows in the spec's example; both are read. A tag's entry
     * (an actor, a genre) has no ratingKey and is left out: Plex returns their titles too.
     */
    override suspend fun search(access: ServerAccess, query: String, limit: Int): List<PlexSearchHub> {
        val url = url(access, "/hubs/search").newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("includeCollections", "1")
            .build()
        return get<SearchJson>(url, access).container.hubs.map { hub ->
            PlexSearchHub(hub.type.orEmpty(), hub.title.orEmpty(), (hub.metadata + hub.directories).mapNotNull { it.toHit() })
        }
    }

    private fun url(access: ServerAccess, path: String): HttpUrl =
        "${access.baseUri.trimEnd('/')}$path".toHttpUrlOrNull() ?: throw PlexException.Failed(0, "Bad server address")

    private suspend inline fun <reified T> get(url: HttpUrl, access: ServerAccess, page: Pair<Int, Int>? = null): T = withContext(io) {
        val builder = headers.apply(Request.Builder().url(url), access.token)
        if (page != null) {
            builder.header("X-Plex-Container-Start", page.first.toString())
            builder.header("X-Plex-Container-Size", page.second.toString())
        }
        val body = try {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 401 -> throw PlexException.Unauthorized()
                    !response.isSuccessful -> throw PlexException.Failed(response.code, "${url.encodedPath} answered ${response.code}")
                }
                text
            }
        } catch (e: PlexException) {
            throw e
        } catch (e: IOException) {
            throw PlexException.Unreachable(e)
        }
        try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw PlexException.Failed(200, "${url.encodedPath}: unexpected answer: ${e.message?.take(160)}")
        } catch (e: IllegalArgumentException) {
            throw PlexException.Failed(200, "${url.encodedPath}: unexpected answer: ${e.message?.take(160)}")
        }
    }

    @Serializable
    private class HubsJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(@SerialName("Hub") val hubs: List<Hub> = emptyList())

        @Serializable
        class Hub(@SerialName("Metadata") val metadata: List<MetadataJson> = emptyList())
    }

    /** [search]'s answer (official spec, "Search Hub"). */
    @Serializable
    private class SearchJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(@SerialName("Hub") val hubs: List<Hub> = emptyList())

        @Serializable
        class Hub(
            val type: String? = null,
            val title: String? = null,
            @SerialName("Metadata") val metadata: List<Hit> = emptyList(),
            @SerialName("Directory") val directories: List<Hit> = emptyList(),
        )

        @Serializable
        class Hit(
            val ratingKey: String? = null,
            val type: String? = null,
            val title: String? = null,
            val year: Int? = null,
            val contentRating: String? = null,
            val thumb: String? = null,
            val art: String? = null,
            val grandparentTitle: String? = null,
            val grandparentRatingKey: String? = null,
            val grandparentThumb: String? = null,
            val parentIndex: Int? = null,
            val index: Int? = null,
            val reason: String? = null,
            val reasonTitle: String? = null,
        ) {
            fun toHit(): PlexSearchHit? {
                val key = ratingKey?.takeIf { it.isNotBlank() } ?: return null
                val name = title?.takeIf { it.isNotBlank() } ?: return null
                return PlexSearchHit(
                    ratingKey = key,
                    type = type.orEmpty(),
                    title = name,
                    year = year,
                    contentRating = contentRating?.takeIf { it.isNotBlank() },
                    thumb = (if (type == "episode") grandparentThumb ?: thumb else thumb)?.takeIf { it.isNotBlank() },
                    art = art?.takeIf { it.isNotBlank() },
                    showTitle = grandparentTitle,
                    showKey = grandparentRatingKey,
                    season = parentIndex,
                    episode = index,
                    reason = reason?.takeIf { it.isNotBlank() },
                    reasonTitle = reasonTitle?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    @Serializable
    private class SectionsJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(@SerialName("Directory") val directories: List<Directory> = emptyList())

        @Serializable
        class Directory(
            val key: String,
            val type: String,
            val title: String,
            @Serializable(with = PlexBoolean::class) val hidden: Boolean = false,
        )
    }

    @Serializable
    private class ItemsJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(
            val offset: Int? = null,
            val totalSize: Int? = null,
            @SerialName("Metadata") val metadata: List<MetadataJson> = emptyList(),
        )
    }

    @Serializable
    private class HistoryJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(@SerialName("Metadata") val metadata: List<Play> = emptyList())

        @Serializable
        class Play(
            val historyKey: String? = null,
            val ratingKey: String? = null,
            val type: String = "",
            val title: String = "",
            val viewedAt: Long = 0,
            val thumb: String? = null,
            val grandparentRatingKey: String? = null,
        )
    }

    /** Titles with only their markers read: [creditsStart]. */
    @Serializable
    private class MarkersJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(@SerialName("Metadata") val metadata: List<Titled> = emptyList())

        @Serializable
        class Titled(val ratingKey: String, @SerialName("Marker") val markers: List<Marker> = emptyList())

        @Serializable
        class Marker(val type: String? = null, val startTimeOffset: Long? = null) {
            /**
             * Real servers say "credits" (python-plexapi's `hasCreditsMarker`); the community
             * spec's enum says "credit". Either is the credits.
             */
            val isCredits get() = type == "credits" || type == "credit"
        }
    }

    @Serializable
    private class AccountsJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(@SerialName("Account") val accounts: List<Account> = emptyList())

        @Serializable
        class Account(val id: Long, val name: String? = null)
    }

    @Serializable
    private class MetadataJson(
        val ratingKey: String,
        val type: String,
        val title: String,
        val grandparentTitle: String? = null,
        val parentIndex: Int? = null,
        val index: Int? = null,
        val year: Int? = null,
        val duration: Long? = null,
        val viewOffset: Long? = null,
        val viewCount: Int = 0,
        val lastViewedAt: Long? = null,
        val addedAt: Long? = null,
        val updatedAt: Long? = null,
        val contentRating: String? = null,
        val audienceRating: Double? = null,
        val rating: Double? = null,
        val thumb: String? = null,
        val art: String? = null,
        val grandparentThumb: String? = null,
        val grandparentArt: String? = null,
        @SerialName("Genre") val genres: List<Tag> = emptyList(),
        val primaryExtraKey: String? = null,
        val summary: String? = null,
        val grandparentRatingKey: String? = null,
        @SerialName("Media") val media: List<MediaJson> = emptyList(),
        val userRating: Double? = null,
        val lastRatedAt: Long? = null,
        val originallyAvailableAt: String? = null,
        val leafCount: Int? = null,
        val viewedLeafCount: Int? = null,
        @SerialName("Collection") val collections: List<Tag> = emptyList(),
        @SerialName("Label") val labels: List<Tag> = emptyList(),
        @SerialName("Director") val directors: List<Tag> = emptyList(),
        @SerialName("Role") val roles: List<Tag> = emptyList(),
        val studio: String? = null,
        @SerialName("Image") val images: List<ImageJson> = emptyList(),
    ) {
        fun toItem() = PlexItem(
            ratingKey, type, title, grandparentTitle, parentIndex, index, year, duration, viewOffset, viewCount,
            lastViewedAt, addedAt, updatedAt, contentRating, audienceRating, rating, thumb, art, grandparentThumb,
            grandparentArt, genres.names(),
            // Spec: "for a movie, this is a trailer", at the extra's metadata details
            // endpoint. Other types' primary extras aren't documented as trailers.
            trailerKey = primaryExtraKey?.takeIf { type == "movie" }?.let { EXTRA_KEY.matchEntire(it)?.groupValues?.get(1) },
            summary = summary?.takeIf { it.isNotBlank() },
            showKey = grandparentRatingKey,
            media = media.firstNotNullOfOrNull { it.toMedia() },
            userRating = userRating,
            lastRatedAt = lastRatedAt,
            releaseDate = originallyAvailableAt?.takeIf { it.isNotBlank() },
            episodeCount = leafCount,
            watchedEpisodeCount = viewedLeafCount,
            collections = collections.names(),
            labels = labels.names(),
            directors = directors.names(),
            actors = roles.names(),
            studio = studio?.trim()?.takeIf(String::isNotEmpty),
            logo = images.firstOrNull { it.type == CLEAR_LOGO }?.url?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    /**
     * One of a title's images (official PMS spec): its `type` is `background`,
     * `backgroundSquare`, `clearLogo`, `coverPoster` or `snapshot`, and its `url` a path on
     * the server. Its `alt` text is unread.
     */
    @Serializable
    private class ImageJson(val type: String? = null, val url: String? = null)

    /** A title's file (`Media`), with its parts and, in a title's details, their streams. */
    @Serializable
    private class MediaJson(
        val container: String? = null,
        val videoCodec: String? = null,
        val videoProfile: String? = null,
        val videoResolution: String? = null,
        val audioCodec: String? = null,
        val audioChannels: Int? = null,
        val bitrate: Int? = null,
        @SerialName("Part") val parts: List<PartJson> = emptyList(),
    ) {
        fun toMedia(): PlexMedia? {
            val part = parts.firstOrNull { it.key != null } ?: return null
            val video = part.streams.firstOrNull { it.streamType == VIDEO_STREAM }
            return PlexMedia(
                partKey = part.key!!,
                container = container ?: part.container,
                videoCodec = videoCodec,
                videoProfile = videoProfile,
                videoResolution = videoResolution,
                audioCodec = audioCodec,
                audioChannels = audioChannels,
                sizeBytes = part.size,
                bitrateKbps = bitrate,
                dynamicRange = video?.dynamicRange(),
            )
        }
    }

    /** One file of a title. Its `file` (the path on the server) is deliberately not read. */
    @Serializable
    private class PartJson(
        val key: String? = null,
        val size: Long? = null,
        val container: String? = null,
        @SerialName("Stream") val streams: List<StreamJson> = emptyList(),
    )

    /** A stream of a part; only a title's details carry them. */
    @Serializable
    private class StreamJson(
        val streamType: Int? = null,
        @SerialName("DOVIPresent") @Serializable(with = PlexBoolean::class) val doviPresent: Boolean = false,
        val colorTrc: String? = null,
    ) {
        fun dynamicRange(): String? = when {
            doviPresent -> PlexMedia.DOLBY_VISION
            colorTrc.equals("smpte2084", ignoreCase = true) -> PlexMedia.HDR10
            colorTrc.equals("arib-std-b67", ignoreCase = true) -> PlexMedia.HLG
            else -> null
        }
    }

    /** A genre, collection, label, director or cast member. A cast entry also has its `role`, unread. */
    @Serializable
    private class Tag(val tag: String? = null)

    /**
     * A true/false field that a real server sends as 0/1. The official spec says boolean,
     * but the operator's PMS answered `"hidden": 0` (10/26/2026): read either.
     */
    private object PlexBoolean : KSerializer<Boolean> {
        override val descriptor = PrimitiveSerialDescriptor("PlexBoolean", PrimitiveKind.BOOLEAN)

        override fun deserialize(decoder: Decoder): Boolean {
            val json = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
            val content = json.decodeJsonElement().jsonPrimitive.content
            return content == "1" || content.equals("true", ignoreCase = true)
        }

        override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    }

    private companion object {
        // Lenient: the spec leaves ratingKey untyped, so accept it quoted or not.
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** `primaryExtraKey`: "/library/metadata/1073" names the extra with ratingKey 1073. */
        val EXTRA_KEY = Regex("/library/metadata/([0-9]+)")

        /** Plex's `streamType` for video (2 audio, 3 subtitles). */
        const val VIDEO_STREAM = 1

        /** The `Image` type of a title's logo (Phase 12), as python-plexapi's `LogoUrlMixin` reads it. */
        const val CLEAR_LOGO = "clearLogo"

        /** The tags' names, blanks left out: they're stored "|"-joined. */
        fun List<Tag>.names(): List<String> = mapNotNull { it.tag?.trim()?.takeIf(String::isNotEmpty) }
    }
}
