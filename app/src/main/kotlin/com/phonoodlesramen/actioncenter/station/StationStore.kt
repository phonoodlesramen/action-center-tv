package com.phonoodlesramen.actioncenter.station

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonoodlesramen.actioncenter.guide.station.ChannelPlayed
import com.phonoodlesramen.actioncenter.guide.station.SpotBody
import com.phonoodlesramen.actioncenter.guide.station.SpotForget
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationSpot
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * What this TV keeps about the guide station (Phase 8): where it is, this TV's room, a
 * pairing under way, the certificates the station issued (PEM: they're public), and its
 * copy of the station's guide and spot. Nothing in it is secret: the private key is in the
 * Keystore. A TV that has never paired has the defaults.
 */
@Serializable
data class StationSaved(
    /** The NAS's address, typed once (U4 is deferred). */
    val host: String? = null,
    val port: Int = StationApi.DEFAULT_PORT,
    /** This TV's room ("Living room"): its name on the station, shown on Back-to cards in other rooms. */
    val room: String? = null,
    /** A pairing waiting for approval. */
    val pairing: SavedPairing? = null,
    /** This TV's certificate, issued by the station, PEM. */
    val certificate: String? = null,
    /** The station's CA, PEM: the only one this TV trusts for it. */
    val caCertificate: String? = null,
    /** This TV's id on the station, once it's been read. */
    val deviceId: String? = null,
    /** The station unpaired this TV (`403 unpaired`): it keeps its copy until it pairs again or leaves. */
    val unpaired: Boolean = false,
    /** About the copy of the station's guide in `guide.db`; null until the first one. */
    val guide: GuideCopy? = null,
    /** The house's saved spot, as last read, and a change to it the station hasn't heard yet. */
    val spot: SpotCopy = SpotCopy(),
) {
    /** Whether this TV has a station's certificate: its guide is then the station's. */
    val paired: Boolean get() = certificate != null && caCertificate != null

    /** "192.168.1.20", or "192.168.1.20:9443" off the default port; empty when there's none. */
    val address: String get() = host?.let { if (port == StationApi.DEFAULT_PORT) it else "$it:$port" }.orEmpty()
}

/** A pairing request the station hasn't answered yet, and the CA it showed (base64 DER). */
@Serializable
data class SavedPairing(val requestId: String, val expiresAtMillis: Long, val code: String, val caDer: String)

/**
 * What `guide.db` can't hold of the station's guide: its version and ETag, and each
 * channel's ratings (for kids, R2), logo and programs off air. `guide.db`'s schema stays as
 * it is, so the TVs' database fingerprints don't change.
 */
@Serializable
data class GuideCopy(
    val version: Long,
    val etag: String? = null,
    /** When the station last changed its schedule. */
    val updatedAtMillis: Long,
    /** When this TV last heard the copy is current (a new copy, or `304`). */
    val checkedAtMillis: Long,
    val timeZone: String,
    val channels: Map<String, ChannelExtras> = emptyMap(),
    /** Whether the station's library has synced: with no channels, the card says why. */
    val synced: Boolean = true,
)

@Serializable
data class ChannelExtras(
    val logo: String? = null,
    val contentRatings: List<String> = emptyList(),
    val unrated: Boolean = false,
    /** Programs that can't play, by start: why. */
    val offAir: Map<Long, String> = emptyMap(),
)

/**
 * The house's spot as last read ([spot]), and the last change this TV made that the station
 * hasn't heard ([unsent], sent first at the next contact), so a pause while the NAS is down
 * isn't lost when it's back.
 */
@Serializable
data class SpotCopy(val spot: StationSpot? = null, val unsent: UnsentSpot? = null)

/** One change to the spot: a save, a forget, or another channel played here. */
@Serializable
data class UnsentSpot(val put: SpotBody? = null, val forget: SpotForget? = null, val played: ChannelPlayed? = null)

/** Where [StationSaved] lives. */
interface StationStore {
    val saved: Flow<StationSaved>

    /** Changes it atomically; returns the new value. */
    suspend fun update(change: (StationSaved) -> StationSaved): StationSaved
}

/** [StationStore] as one JSON value in its own Preferences DataStore file. A file that can't be read reads as never paired. */
class DataStoreStationStore(private val store: DataStore<Preferences>) : StationStore {
    override val saved: Flow<StationSaved> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { read(it[KEY]) }
        .distinctUntilChanged()

    override suspend fun update(change: (StationSaved) -> StationSaved): StationSaved {
        var result = StationSaved()
        store.edit { prefs ->
            result = change(read(prefs[KEY]))
            prefs[KEY] = JSON.encodeToString(StationSaved.serializer(), result)
        }
        return result
    }

    private fun read(text: String?): StationSaved = try {
        text?.let { JSON.decodeFromString(StationSaved.serializer(), it) } ?: StationSaved()
    } catch (e: SerializationException) {
        StationSaved()
    } catch (e: IllegalArgumentException) {
        StationSaved()
    }

    private companion object {
        val KEY = stringPreferencesKey("station")
        val JSON = StationApi.json
    }
}

/** [StationStore] in memory: tests. */
class MemoryStationStore(initial: StationSaved = StationSaved()) : StationStore {
    private val value = MutableStateFlow(initial)
    override val saved: Flow<StationSaved> = value

    val current: StationSaved get() = value.value

    override suspend fun update(change: (StationSaved) -> StationSaved): StationSaved {
        value.update(change)
        return value.value
    }
}
