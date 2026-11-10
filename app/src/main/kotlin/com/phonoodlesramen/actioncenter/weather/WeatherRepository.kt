package com.phonoodlesramen.actioncenter.weather

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

enum class Refresh { UPDATED, NO_PLACE, FAILED }

/**
 * The weather location, unit and last reading (D16), in their own DataStore. The UI
 * reads only this cache; [refresh] runs in the background ([WeatherWorker]).
 */
class WeatherRepository(
    private val store: DataStore<Preferences>,
    private val client: OpenMeteoClient,
    private val defaultUnit: TemperatureUnit = TemperatureUnit.forLocale(Locale.getDefault()),
) {
    val state: Flow<WeatherState> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toState() }

    /** A new place: the old reading belongs to somewhere else, so it goes. */
    suspend fun setPlace(place: Place) {
        store.edit {
            it[PLACE] = json.encodeToString(place)
            it.remove(READING)
        }
    }

    suspend fun setUnit(unit: TemperatureUnit) {
        store.edit { it[UNIT] = unit.name }
    }

    suspend fun search(query: String): List<Place> = client.search(query)

    /** Fetches a reading for the saved place. A failure keeps the last reading. */
    suspend fun refresh(): Refresh {
        val placeJson = store.data.first()[PLACE] ?: return Refresh.NO_PLACE
        val place = decodeOrNull<Place>(placeJson) ?: return Refresh.NO_PLACE
        val reading = try {
            client.current(place.latitude, place.longitude)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OpenMeteoException) {
            return Refresh.FAILED
        }
        store.edit {
            // The place may have changed while the request was out.
            if (it[PLACE] == placeJson) it[READING] = json.encodeToString(reading)
        }
        return Refresh.UPDATED
    }

    /** True when there's a place and its reading is missing or older than 30 minutes. */
    suspend fun needsRefresh(nowMillis: Long): Boolean {
        val current = state.first()
        if (current.place == null) return false
        val reading = current.reading ?: return true
        return nowMillis - reading.fetchedAtMillis >= STALE_AFTER_MILLIS
    }

    private fun Preferences.toState() = WeatherState(
        place = this[PLACE]?.let { decodeOrNull<Place>(it) },
        unit = this[UNIT]?.let { name -> TemperatureUnit.entries.firstOrNull { it.name == name } } ?: defaultUnit,
        reading = this[READING]?.let { decodeOrNull<WeatherReading>(it) },
    )

    private inline fun <reified T> decodeOrNull(text: String): T? = runCatching { json.decodeFromString<T>(text) }.getOrNull()

    companion object {
        const val STALE_AFTER_MILLIS = 30 * 60_000L

        /** Older than this, the top bar shows no temperature at all. */
        const val SHOW_FOR_MILLIS = 3 * 60 * 60_000L

        private val PLACE = stringPreferencesKey("place")
        private val UNIT = stringPreferencesKey("unit")
        private val READING = stringPreferencesKey("reading")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
