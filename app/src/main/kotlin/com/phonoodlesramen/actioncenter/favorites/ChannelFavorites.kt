package com.phonoodlesramen.actioncenter.favorites

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonoodlesramen.actioncenter.guide.station.ProfileKeys
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.safety.summary
import com.phonoodlesramen.actioncenter.station.FavoritesStation
import com.phonoodlesramen.actioncenter.station.StationFavorites
import com.phonoodlesramen.actioncenter.suggest.Profiles
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * Who a profile's favorites belong to (Phase 13, proposal A). Pure.
 *
 * - **On the station** ([station]): [ProfileKeys.OWNER] for you, [ProfileKeys.plexUser] for a
 *   kid linked to a Plex Home user, so every TV agrees on them.
 * - **On this TV** ([local]): the station's key, or for a kid with no Plex user, their profile
 *   alone: their favorites never leave this TV.
 */
object FavoriteKeys {
    fun station(profile: ActiveProfile): String? = when {
        !profile.kid || profile.id == Profiles.OWNER -> ProfileKeys.OWNER
        else -> profile.plexUserId?.let(ProfileKeys::plexUser)
    }

    fun local(profile: ActiveProfile): String = station(profile) ?: "$KID${profile.id}"

    private const val KID = "kid:"
}

/** What favorites say. */
object FavoriteText {
    /** The toast after Add or Remove (C2): "Sci-Fi is a favorite", "Sci-Fi is no longer a favorite". */
    fun changed(channel: String, favorite: Boolean): String = if (favorite) "$channel is a favorite" else "$channel is no longer a favorite"
}

/** What a change to a profile's favorites came to. */
sealed interface FavoriteChange {
    data object Saved : FavoriteChange

    /** The station couldn't take it (N5): nothing changed, and [sentence] says why. */
    data class Refused(val sentence: String) : FavoriteChange
}

/**
 * The profile's favorites as this TV keeps them: [channels] in order, the station's [version]
 * of them, and whether they were changed here and the station has yet to keep them
 * ([unsent]: made unpaired, or while the station was a 1.1 one that keeps none).
 */
@Serializable
data class FavoritesCopy(val channels: List<String> = emptyList(), val version: Long = 0, val unsent: Boolean = false)

/** Every profile's favorites on this TV, by [FavoriteKeys.local]. */
@Serializable
data class FavoritesSaved(val profiles: Map<String, FavoritesCopy> = emptyMap())

/** Where [FavoritesSaved] lives. */
interface FavoritesStore {
    val saved: Flow<FavoritesSaved>

    /** Changes it atomically. */
    suspend fun update(change: (FavoritesSaved) -> FavoritesSaved)
}

/** [FavoritesStore] as one JSON value in its own Preferences DataStore file. A file that can't be read reads as none. */
class DataStoreFavorites(private val store: DataStore<Preferences>) : FavoritesStore {
    override val saved: Flow<FavoritesSaved> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { read(it[KEY]) }
        .distinctUntilChanged()

    override suspend fun update(change: (FavoritesSaved) -> FavoritesSaved) {
        store.edit { prefs -> prefs[KEY] = JSON.encodeToString(FavoritesSaved.serializer(), change(read(prefs[KEY]))) }
    }

    private fun read(text: String?): FavoritesSaved = try {
        text?.let { JSON.decodeFromString(FavoritesSaved.serializer(), it) } ?: FavoritesSaved()
    } catch (e: SerializationException) {
        FavoritesSaved()
    } catch (e: IllegalArgumentException) {
        FavoritesSaved()
    }

    private companion object {
        val KEY = stringPreferencesKey("favorites")
        val JSON = StationApi.json
    }
}

/** [FavoritesStore] in memory: tests and previews. */
class MemoryFavorites(initial: FavoritesSaved = FavoritesSaved()) : FavoritesStore {
    private val value = MutableStateFlow(initial)
    override val saved: Flow<FavoritesSaved> = value

    val current: FavoritesSaved get() = value.value

    override suspend fun update(change: (FavoritesSaved) -> FavoritesSaved) = value.update(change)
}

/**
 * Favorite channels (Phase 13, C1-C5): the current profile's, drawn at once from this TV's
 * copy ([current]), and changed by Add, Remove and Move.
 *
 * - **Paired**, a profile with a key keeps them on the station (`GET`/`PUT /v1/favorites/{key}`):
 *   a change is sent first and kept here once the station has it. One the station can't take
 *   (it can't be reached) is refused with editing's sentence (N5), and nothing changes.
 *   [keepUp] fetches them again whenever the station's `changes` version moves.
 * - **A 1.1 station** answers 404: they're kept here, marked unsent, and the station page says
 *   it needs an update. Once it keeps favorites, and has none yet, this TV's go up to it.
 * - **Unpaired**, or a kid with no Plex user: they're this TV's own.
 *
 * A deleted channel leaves the list the next time it's saved ([knownChannels]); an off one,
 * or one a kid can no longer see, stays. Nothing here throws.
 *
 * @param knownChannels every channel in the lineup, off ones too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelFavorites(
    private val store: FavoritesStore,
    private val station: FavoritesStation,
    private val profile: Flow<ActiveProfile>,
    private val knownChannels: suspend () -> Collection<String> = { emptyList() },
    private val log: (String) -> Unit = {},
) {
    /** One change or fetch at a time, so a fetch never lands over a change made meanwhile. */
    private val lock = Mutex()

    /** The current profile's favorites, in order. */
    val current: Flow<List<String>> = profile.map { FavoriteKeys.local(it) }.distinctUntilChanged()
        .flatMapLatest { key -> store.saved.map { it.profiles[key]?.channels.orEmpty() } }
        .distinctUntilChanged()

    /** Adds [channelId] to the current profile's favorites (at the end), or takes it out. */
    suspend fun set(channelId: String, favorite: Boolean): FavoriteChange = change { FavoriteOrder.toggled(it, channelId, favorite) }

    /** Move put down (C3): [order] is the profile's whole list as it now stands. */
    suspend fun reorder(order: List<String>): FavoriteChange = change { order }

    private suspend fun change(next: (List<String>) -> List<String>): FavoriteChange = lock.withLock {
        try {
            val who = profile.first()
            val local = FavoriteKeys.local(who)
            val key = FavoriteKeys.station(who)
            val before = store.saved.first().profiles[local]?.channels.orEmpty()
            val channels = FavoriteOrder.pruned(next(before), known())
            if (key == null || !station.paired.first()) {
                keep(local, FavoritesCopy(channels, unsent = true))
                return@withLock FavoriteChange.Saved
            }
            when (val answer = station.saveFavorites(key, channels)) {
                is StationFavorites.Kept -> keep(local, FavoritesCopy(channels, answer.favorites.version))
                StationFavorites.NotKept -> keep(local, FavoritesCopy(channels, unsent = true))
                is StationFavorites.Refused -> return@withLock FavoriteChange.Refused(answer.sentence)
            }
            FavoriteChange.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("favorites: not saved: ${e.summary()}")
            FavoriteChange.Refused(CANT)
        }
    }

    /**
     * While the home is in front: the current profile's favorites from the station, each time
     * the profile changes, the TV pairs, or the station's `changes` version moves. Unpaired,
     * or for a kid with no Plex user, it does nothing. Never throws but its own cancellation.
     */
    suspend fun keepUp() {
        combine(profile.map { FavoriteKeys.station(it) }.distinctUntilChanged(), station.paired, station.changes, ::Triple)
            .distinctUntilChanged()
            .collectLatest { (key, paired, _) -> if (key != null && paired) fetch() }
    }

    /** The current profile's favorites from the station into this TV's copy, once. */
    suspend fun fetch() = lock.withLock {
        try {
            val who = profile.first()
            val key = FavoriteKeys.station(who) ?: return@withLock
            if (!station.paired.first()) return@withLock
            val local = FavoriteKeys.local(who)
            val answer = station.favorites(key) as? StationFavorites.Kept ?: return@withLock
            val remote = answer.favorites
            val mine = store.saved.first().profiles[local]
            // Made here before the station kept favorites (unpaired, or a 1.1 station): they go up, unless it has some already.
            if (mine != null && mine.unsent && remote.version == 0L && remote.channels.isEmpty() && mine.channels.isNotEmpty()) {
                val sent = station.saveFavorites(key, mine.channels) as? StationFavorites.Kept ?: return@withLock
                keep(local, FavoritesCopy(mine.channels, sent.favorites.version))
                log("favorites: this TV's sent to the station")
            } else if (mine == null || mine.channels != remote.channels || mine.version != remote.version || mine.unsent) {
                keep(local, FavoritesCopy(remote.channels, remote.version))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("favorites: not fetched: ${e.summary()}")
        }
    }

    private suspend fun keep(key: String, copy: FavoritesCopy) = store.update { it.copy(profiles = it.profiles + (key to copy)) }

    private suspend fun known(): Collection<String> = try {
        knownChannels()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private companion object {
        const val CANT = "That didn't work. Try again."
    }
}
