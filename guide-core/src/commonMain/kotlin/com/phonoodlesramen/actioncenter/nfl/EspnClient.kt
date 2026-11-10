package com.phonoodlesramen.actioncenter.nfl

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** An ESPN call that failed. [message] is a sentence the TVs show as it is ("Can't reach ESPN."); [code] is ESPN's status, if it answered. */
class EspnException(message: String, val code: Int? = null, cause: Throwable? = null) : IOException(message, cause)

/**
 * ESPN's public site API (no key; F11): the scoreboard, standings, teams, a team's schedule, a
 * game's summary, and team logos through ESPN's image resizer. Undocumented, and Disney's terms
 * allow personal use but not "automated means", so the guide station polls it gently and only
 * while someone's looking (proposal F), and the TVs never call it while paired.
 */
class EspnClient(
    private val http: OkHttpClient,
    private val base: HttpUrl = SITE,
    private val cdn: HttpUrl = CDN,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The current week, or with all three, that week of that season. */
    suspend fun scoreboard(seasonYear: Int? = null, seasonType: Int? = null, week: Int? = null): EspnScoreboard {
        val url = url("apis/site/v2/sports/football/nfl/scoreboard").newBuilder().apply {
            if (seasonYear != null && seasonType != null && week != null) {
                addQueryParameter("dates", seasonYear.toString())
                addQueryParameter("seasontype", seasonType.toString())
                addQueryParameter("week", week.toString())
            }
        }.build()
        return parse(text(url), EspnParser::scoreboard)
    }

    /** By division, seeds first; [season] for another season's (the preseason shows last season's, D). */
    suspend fun standings(season: Int? = null): EspnStandings {
        val url = url("apis/v2/sports/football/nfl/standings").newBuilder()
            .addQueryParameter("level", "3")
            .addQueryParameter("sort", "playoffseed:asc")
            .apply { if (season != null) addQueryParameter("season", season.toString()) }
            .build()
        return parse(text(url), EspnParser::standings)
    }

    /** The 32 teams, each with its division from [divisions] (the standings'). */
    suspend fun teams(divisions: Map<String, NflDivision>): List<NflTeam> =
        parse(text(url("apis/site/v2/sports/football/nfl/teams"))) { EspnParser.teams(it, divisions) }

    suspend fun schedule(abbr: String, seasonYear: Int, seasonType: Int): NflTeamSchedule {
        val url = url("apis/site/v2/sports/football/nfl/teams").newBuilder()
            .addPathSegment(abbr.lowercase())
            .addPathSegment("schedule")
            .addQueryParameter("season", seasonYear.toString())
            .addQueryParameter("seasontype", seasonType.toString())
            .build()
        return parse(text(url), EspnParser::schedule)
    }

    suspend fun summary(eventId: String): NflGameDetail {
        val url = url("apis/site/v2/sports/football/nfl/summary").newBuilder().addQueryParameter("event", eventId).build()
        return parse(text(url), EspnParser::summary)
    }

    /**
     * A team's logo as a [sizePx]-square PNG, through ESPN's resizer. The `500-dark` art: it's
     * made for dark backgrounds, as the TVs draw (the Jets' is a white wordmark, the Raiders'
     * shield has a light edge; the rest match the light art).
     */
    suspend fun logo(abbr: String, sizePx: Int): ByteArray {
        val url = cdn.resolve("combiner/i")!!.newBuilder()
            .addQueryParameter("img", "$LOGO_PATH${abbr.lowercase()}.png")
            .addQueryParameter("w", sizePx.toString())
            .addQueryParameter("h", sizePx.toString())
            .build()
        return call(url) { body, type ->
            if (!type.orEmpty().startsWith("image/") || body.isEmpty()) throw EspnException("ESPN sent no logo for $abbr.")
            body
        }
    }

    private fun url(path: String) = base.resolve(path) ?: error("Bad ESPN path: $path")

    private suspend fun text(url: HttpUrl): String = call(url) { body, _ -> body.toString(Charsets.UTF_8) }

    private suspend fun <T> call(url: HttpUrl, read: (ByteArray, String?) -> T): T = withContext(io) {
        val request = Request.Builder().url(url).header("Accept", "application/json, image/png").build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw EspnException("ESPN answered ${response.code}.", response.code)
                read(response.body.bytes(), response.header("Content-Type"))
            }
        } catch (e: EspnException) {
            throw e
        } catch (e: IOException) {
            throw EspnException("Can't reach ESPN.", cause = e)
        }
    }

    private fun <T> parse(text: String, parser: (String) -> T): T = try {
        parser(text)
    } catch (e: SerializationException) {
        throw EspnException(UNREADABLE, cause = e)
    } catch (e: IllegalArgumentException) {
        throw EspnException(UNREADABLE, cause = e)
    }

    companion object {
        val SITE = "https://site.api.espn.com/".toHttpUrl()
        val CDN = "https://a.espncdn.com/".toHttpUrl()
        const val LOGO_PATH = "/i/teamlogos/nfl/500-dark/"
        private const val UNREADABLE = "ESPN's answer has changed, and can't be read."
    }
}
