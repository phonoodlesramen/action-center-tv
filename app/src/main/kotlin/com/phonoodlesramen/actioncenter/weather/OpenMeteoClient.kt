package com.phonoodlesramen.actioncenter.weather

import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A failed Open-Meteo request: no network, an HTTP error, or a body we can't read. */
class OpenMeteoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Open-Meteo's forecast and geocoding APIs (D16): free for non-commercial use, no key,
 * under 10,000 calls a day, and the data needs a "Weather data by Open-Meteo.com" credit.
 */
class OpenMeteoClient(
    private val http: OkHttpClient,
    private val forecastUrl: HttpUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl(),
    private val geocodingUrl: HttpUrl = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * The current temperature (°C), weather code and day/night for a place, plus today's
     * sunrise and sunset for Living sky. Daily values need a time zone: `auto` uses the
     * place's own, so the times come back on its local clock, without an offset.
     */
    suspend fun current(latitude: Double, longitude: Double): WeatherReading {
        val url = forecastUrl.newBuilder()
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter("current", "temperature_2m,weather_code,is_day")
            .addQueryParameter("daily", "sunrise,sunset")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "1")
            .build()
        val forecast = get<ForecastResponse>(url)
        val current = forecast.current ?: throw OpenMeteoException("No current weather in the response")
        val sunrise = forecast.daily?.sunrise?.firstOrNull()?.let(::localDateTimeOrNull)
        val sunset = forecast.daily?.sunset?.firstOrNull()?.let(::localDateTimeOrNull)
        // In the polar night and the midnight sun, both are midnight: there's no sunrise to show.
        val sunMoves = sunrise != null && sunset != null && sunrise < sunset
        return WeatherReading(
            celsius = current.temperature,
            code = current.weatherCode,
            isDay = current.isDay == 1,
            fetchedAtMillis = clock(),
            sunrise = sunrise.takeIf { sunMoves },
            sunset = sunset.takeIf { sunMoves },
        )
    }

    /** A missing or unreadable sun time only costs Living sky its real schedule, never the reading. */
    private fun localDateTimeOrNull(text: String): LocalDateTime? = runCatching { LocalDateTime.parse(text) }.getOrNull()

    /** Places matching [name], best match first. Blank searches return nothing. */
    suspend fun search(name: String, language: String = "en"): List<Place> {
        val query = name.trim()
        if (query.isEmpty()) return emptyList()
        val url = geocodingUrl.newBuilder()
            .addQueryParameter("name", query)
            .addQueryParameter("count", SEARCH_RESULTS.toString())
            .addQueryParameter("language", language)
            .addQueryParameter("format", "json")
            .build()
        return get<SearchResponse>(url).results.map {
            Place(it.name, it.admin1, it.country, it.latitude, it.longitude)
        }
    }

    private suspend inline fun <reified T> get(url: HttpUrl): T = withContext(io) {
        val body = try {
            http.newCall(Request(url)).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    val reason = runCatching { json.decodeFromString<ErrorResponse>(text).reason }.getOrNull()
                    throw OpenMeteoException(reason ?: "HTTP ${response.code}")
                }
                text
            }
        } catch (e: OpenMeteoException) {
            throw e
        } catch (e: IOException) {
            throw OpenMeteoException("Couldn't reach Open-Meteo: ${e.message}", e)
        }
        try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw OpenMeteoException("Unexpected response from Open-Meteo", e)
        } catch (e: IllegalArgumentException) {
            throw OpenMeteoException("Unexpected response from Open-Meteo", e)
        }
    }

    @Serializable
    private class ForecastResponse(val current: Current? = null, val daily: Daily? = null)

    @Serializable
    private class Current(
        @SerialName("temperature_2m") val temperature: Double,
        @SerialName("weather_code") val weatherCode: Int,
        @SerialName("is_day") val isDay: Int,
    )

    /** One entry per day asked for (`forecast_days=1`): local ISO 8601 times such as `2026-10-26T07:19`. */
    @Serializable
    private class Daily(val sunrise: List<String?> = emptyList(), val sunset: List<String?> = emptyList())

    /** No matches: Open-Meteo leaves `results` out entirely. */
    @Serializable
    private class SearchResponse(val results: List<Result> = emptyList())

    @Serializable
    private class Result(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country: String? = null,
        val admin1: String? = null,
    )

    @Serializable
    private class ErrorResponse(val reason: String? = null)

    private companion object {
        const val SEARCH_RESULTS = 6
        val json = Json { ignoreUnknownKeys = true }
    }
}
