package com.phonoodlesramen.actioncenter.plex

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A movie or show from Plex's catalog (Phase 15). [ratingKey] is the catalog's id; [score] how well it matched, 0 to 1. */
data class CatalogTitle(
    val ratingKey: String,
    val type: String,
    val title: String,
    val year: Int? = null,
    val thumb: String? = null,
    val art: String? = null,
    val genres: List<String> = emptyList(),
    val score: Double? = null,
)

/** A catalog search: its titles, best first, and Plex's "did you mean" terms. */
data class CatalogSearch(val titles: List<CatalogTitle>, val suggestedTerms: List<String> = emptyList())

/** What a catalog title's details add (Phase 15): its rating, and Common Sense Media's age when it has one. */
data class CatalogDetails(val ratingKey: String, val contentRating: String?, val commonSenseAge: Int? = null)

/**
 * One way to watch a catalog title (Plex's "where to watch", from JustWatch): [platform] a
 * slug, [title] the service's name, [offerType] subscription, free, rent or buy, [url] the
 * title on that service.
 */
data class CatalogOffer(val platform: String, val title: String, val offerType: String, val country: String? = null, val url: String? = null)

/**
 * Plex's own catalog (Phase 15, Q3), as Plex's apps and python-plexapi call it. None of it is
 * in Plex's documentation, so any of it may change: a call that fails says so and returns
 * nothing new.
 *
 * - **Search** (`discover.provider.plex.tv/library/search`) and **details**
 *   (`metadata.provider.plex.tv/library/metadata/{id}`) answer without a token (checked
 *   11/02/2026). The search has no ratings; the details do.
 * - **Where to watch** (`…/library/metadata/{id}/availabilities`) needs a plex.tv token: 401
 *   without one. It goes in the `X-Plex-Token` header, never in a URL or a log.
 */
interface PlexCatalogApi {
    suspend fun search(query: String, limit: Int = 20): CatalogSearch

    /** Null when the catalog doesn't know the title. */
    suspend fun details(ratingKey: String): CatalogDetails?

    suspend fun offers(ratingKey: String, token: String): List<CatalogOffer>
}

class PlexCatalog(
    private val http: OkHttpClient,
    private val headers: PlexHeaders,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val discover: HttpUrl = "https://discover.provider.plex.tv/".toHttpUrl(),
    private val metadata: HttpUrl = "https://metadata.provider.plex.tv/".toHttpUrl(),
) : PlexCatalogApi {
    override suspend fun search(query: String, limit: Int): CatalogSearch {
        val url = discover.newBuilder().addPathSegments("library/search")
            .addQueryParameter("query", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("searchTypes", "movies,tv")
            .addQueryParameter("searchProviders", "discover")
            .addQueryParameter("includeMetadata", "1")
            .build()
        val container = get(url, token = null, SearchJson.serializer()).container
        val found = container.results.firstOrNull { it.id == "external" }?.results.orEmpty()
        return CatalogSearch(
            titles = found.mapNotNull { result -> result.metadata?.toTitle(result.score) },
            suggestedTerms = container.suggestedTerms,
        )
    }

    override suspend fun details(ratingKey: String): CatalogDetails? {
        val url = metadata.newBuilder().addPathSegments("library/metadata").addPathSegment(id(ratingKey)).build()
        val title = try {
            get(url, token = null, DetailsJson.serializer()).container.metadata.firstOrNull()
        } catch (e: PlexException.Failed) {
            if (e.code == 404) return null else throw e
        } ?: return null
        val age = title.commonSense.flatMap { it.ages }.firstOrNull { it.type == "official" }?.age
            ?: title.commonSense.flatMap { it.ages }.firstOrNull()?.age
        return CatalogDetails(ratingKey, title.contentRating?.takeIf { it.isNotBlank() }, age)
    }

    override suspend fun offers(ratingKey: String, token: String): List<CatalogOffer> {
        val url = metadata.newBuilder().addPathSegments("library/metadata").addPathSegment(id(ratingKey)).addPathSegment("availabilities").build()
        return get(url, token, OffersJson.serializer()).container.offers.mapNotNull { it.toOffer() }
    }

    /** The catalog's ids are hex; anything else isn't one of its titles. */
    private fun id(ratingKey: String): String {
        require(ratingKey.isNotEmpty() && ratingKey.all { it.isLetterOrDigit() }) { "Bad catalog ratingKey: $ratingKey" }
        return ratingKey
    }

    private suspend fun <T> get(url: HttpUrl, token: String?, serializer: KSerializer<T>): T = withContext(io) {
        val request = headers.apply(Request.Builder().url(url), token).build()
        val body = try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 401 -> throw PlexException.Unauthorized()
                    !response.isSuccessful -> throw PlexException.Failed(response.code, "${url.host}${url.encodedPath} answered ${response.code}")
                }
                text
            }
        } catch (e: PlexException) {
            throw e
        } catch (e: IOException) {
            throw PlexException.Unreachable(e)
        }
        try {
            json.decodeFromString(serializer, body)
        } catch (e: SerializationException) {
            throw PlexException.Failed(200, "${url.host}${url.encodedPath}: unexpected answer: ${e.message?.take(160)}")
        } catch (e: IllegalArgumentException) {
            throw PlexException.Failed(200, "${url.host}${url.encodedPath}: unexpected answer: ${e.message?.take(160)}")
        }
    }

    @Serializable
    private class SearchJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(
            val suggestedTerms: List<String> = emptyList(),
            @SerialName("SearchResults") val results: List<Group> = emptyList(),
        )

        @Serializable
        class Group(val id: String? = null, @SerialName("SearchResult") val results: List<Result> = emptyList())

        @Serializable
        class Result(val score: Double? = null, @SerialName("Metadata") val metadata: Title? = null)

        @Serializable
        class Title(
            val ratingKey: String? = null,
            val type: String? = null,
            val title: String? = null,
            val year: Int? = null,
            val thumb: String? = null,
            val art: String? = null,
            @SerialName("Genre") val genres: List<Tag> = emptyList(),
        ) {
            fun toTitle(score: Double?): CatalogTitle? {
                val key = ratingKey?.takeIf { it.isNotBlank() } ?: return null
                val name = title?.takeIf { it.isNotBlank() } ?: return null
                val kind = type?.takeIf { it == "movie" || it == "show" } ?: return null
                return CatalogTitle(key, kind, name, year, thumb, art, genres.mapNotNull { it.tag }, score)
            }
        }
    }

    @Serializable
    private class DetailsJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(@SerialName("Metadata") val metadata: List<Title> = emptyList())

        @Serializable
        class Title(
            val contentRating: String? = null,
            @SerialName("CommonSenseMedia") val commonSense: List<CommonSense> = emptyList(),
        )

        @Serializable
        class CommonSense(@SerialName("AgeRating") val ages: List<Age> = emptyList())

        @Serializable
        class Age(val age: Int? = null, val type: String? = null)
    }

    @Serializable
    private class OffersJson(@SerialName("MediaContainer") val container: Container) {
        @Serializable
        class Container(@SerialName("Availability") val offers: List<Offer> = emptyList())

        @Serializable
        class Offer(
            val platform: String? = null,
            val title: String? = null,
            val offerType: String? = null,
            val country: String? = null,
            val platformUrl: String? = null,
        ) {
            fun toOffer(): CatalogOffer? {
                val slug = platform?.takeIf { it.isNotBlank() } ?: return null
                return CatalogOffer(slug, title?.takeIf { it.isNotBlank() } ?: slug, offerType.orEmpty(), country, platformUrl?.takeIf { it.isNotBlank() })
            }
        }
    }

    @Serializable
    private class Tag(val tag: String? = null)

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
