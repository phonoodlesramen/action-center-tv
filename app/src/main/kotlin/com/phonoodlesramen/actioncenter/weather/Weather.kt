package com.phonoodlesramen.actioncenter.weather

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A place from Open-Meteo's geocoding search, set once in Settings › Weather (D16). */
@Serializable
data class Place(
    val name: String,
    /** State or province (Open-Meteo's `admin1`). */
    val region: String? = null,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
) {
    /** "Portland, Oregon, United States": enough to tell same-named places apart. */
    val description: String get() = listOfNotNull(name, region, country).distinct().joinToString(", ")
}

enum class TemperatureUnit(val symbol: String) {
    FAHRENHEIT("°F"),
    CELSIUS("°C");

    fun fromCelsius(celsius: Double): Double = if (this == FAHRENHEIT) celsius * 9 / 5 + 32 else celsius

    companion object {
        /** Countries that use Fahrenheit day to day; everyone else gets Celsius. */
        private val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")

        fun forLocale(locale: Locale): TemperatureUnit =
            if (locale.country in FAHRENHEIT_COUNTRIES) FAHRENHEIT else CELSIUS
    }
}

/** The last reading. Kept in Celsius, so switching units needs no new request. */
@Serializable
data class WeatherReading(
    val celsius: Double,
    val code: Int,
    val isDay: Boolean,
    val fetchedAtMillis: Long,
    /**
     * Today's sunrise and sunset at the place, on its own clock (`timezone=auto`), for
     * Living sky. Null in readings cached before Phase 2.5, and when the sun doesn't rise
     * or set that day (polar night or day: Open-Meteo returns the same time for both).
     */
    @Serializable(with = LocalDateTimeIso::class) val sunrise: LocalDateTime? = null,
    @Serializable(with = LocalDateTimeIso::class) val sunset: LocalDateTime? = null,
)

/** `2026-10-26T07:19`, as Open-Meteo writes local times, and as the cache stores them. */
object LocalDateTimeIso : KSerializer<LocalDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDateTimeIso", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString())
}

data class WeatherState(
    val place: Place? = null,
    val unit: TemperatureUnit = TemperatureUnit.FAHRENHEIT,
    val reading: WeatherReading? = null,
)

/** What the top bar shows: an icon, the temperature, and the condition for Settings. */
data class WeatherDisplay(
    val sky: Sky,
    val isDay: Boolean,
    /** "54°" */
    val temperature: String,
    /** "Overcast" */
    val condition: String,
    /** "updated 12 min ago" */
    val updated: String,
)

/**
 * The reading to show, or null when there's no place, no reading yet, or the last
 * reading is too old to trust. The UI never shows a stale temperature as current.
 */
fun WeatherState.display(nowMillis: Long): WeatherDisplay? {
    val reading = reading ?: return null
    val age = nowMillis - reading.fetchedAtMillis
    if (place == null || age > WeatherRepository.SHOW_FOR_MILLIS) return null
    val condition = WeatherCodes.condition(reading.code)
    return WeatherDisplay(
        sky = condition.sky,
        isDay = reading.isDay,
        temperature = "${unit.fromCelsius(reading.celsius).roundToInt()}°",
        condition = condition.label,
        updated = updatedAgo(age),
    )
}

private fun updatedAgo(ageMillis: Long): String {
    val minutes = (ageMillis / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "updated just now"
        minutes < 60 -> "updated $minutes min ago"
        else -> "updated ${minutes / 60} h ago"
    }
}
