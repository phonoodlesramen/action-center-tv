package com.phonoodlesramen.actioncenter.player

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Where the guide player was left (P3): Home, or anything else that stopped it but Back,
 * paused it there. The program is its slot (channel and start) and its item, and the place
 * is a position in that file, so resuming opens the same frame, paused, however far live
 * has moved on since [savedAtMillis].
 */
data class SavedSpot(
    val channelId: String,
    /** The program's slot on [channelId]: its start. */
    val slotStartMillis: Long,
    /** The program's Plex item. */
    val itemKey: String,
    /** Where the picture was in the program's file. */
    val positionMs: Long,
    /** When it was saved, by the wall clock. */
    val savedAtMillis: Long,
    /**
     * The room of the TV that saved it ("Living room"), when that's another TV paired with
     * the same guide station (Phase 8, N6); null for this TV's own.
     */
    val room: String? = null,
) {
    /** Whether [slot] is the program this spot is in. */
    fun isFor(slot: GuideSlot): Boolean = slot.startMillis == slotStartMillis && slot.itemKey == itemKey

    /** The picture held where it was left, behind live and paused, from [nowMillis] (G11): Play carries on. */
    fun behindLive(slot: GuideSlot, nowMillis: Long): BehindLive =
        BehindLive(slot, positionMs.coerceIn(0, (slot.endMillis - slot.startMillis).coerceAtLeast(0)), nowMillis, paused = true, startedOver = false)

    companion object {
        /**
         * The spot a stopped player leaves on [channel]: its picture held behind live on a
         * program with a file, at [nowMillis]. Null when nothing is held (filler, a card, a gap).
         */
        fun of(channel: GuideChannel?, behind: BehindLive?, nowMillis: Long): SavedSpot? {
            val slot = behind?.slot ?: return null
            val key = slot.itemKey?.takeIf { GuideTuning.canStartOver(slot) } ?: return null
            if (channel == null || !behind.paused) return null
            return SavedSpot(channel.id, slot.startMillis, key, behind.positionAt(nowMillis), nowMillis)
        }
    }
}

/** A saved spot that still holds (P4): its channel and program, for the home's Back-to card. */
data class BackTo(val channel: GuideChannel, val slot: GuideSlot, val spot: SavedSpot) {
    /** How far through the program the picture was, from 0 to 1, for the card's bar. */
    val progress: Float
        get() {
            val length = slot.endMillis - slot.startMillis
            return if (length <= 0) 1f else (spot.positionMs.toFloat() / length).coerceIn(0f, 1f)
        }
}

/**
 * How long a saved spot holds (P3). Pure, so it's unit-tested.
 *
 * A spot holds for [KEEP_MILLIS] from when it was saved, while its channel is in the lineup
 * and its program is still in that channel's schedule window with its file (the lineup keeps
 * about 3 hours of what's gone by). Another channel playing forgets it outright
 * ([SpotStore.channelPlays]), so that isn't a rule here.
 */
object SpotRules {
    /** Four hours. */
    const val KEEP_MILLIS = 4 * 60 * 60_000L

    /** Whether [spot] is young enough at [nowMillis]. A clock that went back keeps it. */
    fun fresh(spot: SavedSpot, nowMillis: Long): Boolean = nowMillis - spot.savedAtMillis < KEEP_MILLIS

    /**
     * [spot] with its channel and program from [lineup] (a kid's, for a kid: [com.phonoodlesramen.actioncenter.guide.KidGuide]),
     * or null when it no longer holds at [nowMillis]: too old, its channel gone, or its program
     * gone from the window or off air.
     */
    fun backTo(spot: SavedSpot?, lineup: GuideCardState, nowMillis: Long): BackTo? {
        if (spot == null || !fresh(spot, nowMillis)) return null
        val channel = (lineup as? GuideCardState.Ready)?.channels?.firstOrNull { it.id == spot.channelId } ?: return null
        val slot = channel.slots.firstOrNull(spot::isFor)?.takeIf(GuideTuning::canStartOver) ?: return null
        return BackTo(channel, slot, spot)
    }
}

/**
 * The one saved spot (P3), kept where it survives the process being killed. The player saves
 * it and takes it back; the home reads [spot] for its Back-to card and forgets it.
 */
interface SpotStore {
    /** The spot saved last, whether or not it still holds ([SpotRules]); null when there's none. */
    val spot: Flow<SavedSpot?>

    suspend fun save(spot: SavedSpot)

    /** Forgets the spot; with [only], only while it's still that one. */
    suspend fun clear(only: SavedSpot? = null)

    /** A tune-in to [channelId]: a spot on any other channel is forgotten. */
    suspend fun channelPlays(channelId: String)

    /** No spot is ever kept (screenshots, previews). */
    object None : SpotStore {
        override val spot: Flow<SavedSpot?> = flowOf(null)
        override suspend fun save(spot: SavedSpot) = Unit
        override suspend fun clear(only: SavedSpot?) = Unit
        override suspend fun channelPlays(channelId: String) = Unit
    }
}

/** A [SpotStore] in its own Preferences DataStore file. A file that can't be read reads as no spot. */
class DataStoreSpots(private val store: DataStore<Preferences>) : SpotStore {
    override val spot: Flow<SavedSpot?> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::read)
        .distinctUntilChanged()

    override suspend fun save(spot: SavedSpot) {
        store.edit {
            it[CHANNEL] = spot.channelId
            it[SLOT_START] = spot.slotStartMillis
            it[ITEM_KEY] = spot.itemKey
            it[POSITION] = spot.positionMs
            it[SAVED_AT] = spot.savedAtMillis
        }
    }

    override suspend fun clear(only: SavedSpot?) {
        store.edit { if (only == null || read(it) == only) forget(it) }
    }

    override suspend fun channelPlays(channelId: String) {
        store.edit { prefs -> if (prefs[CHANNEL]?.let { it != channelId } == true) forget(prefs) }
    }

    private fun read(prefs: Preferences): SavedSpot? = SavedSpot(
        channelId = prefs[CHANNEL] ?: return null,
        slotStartMillis = prefs[SLOT_START] ?: return null,
        itemKey = prefs[ITEM_KEY] ?: return null,
        positionMs = prefs[POSITION] ?: return null,
        savedAtMillis = prefs[SAVED_AT] ?: return null,
    )

    private fun forget(prefs: MutablePreferences) {
        prefs.remove(CHANNEL)
        prefs.remove(SLOT_START)
        prefs.remove(ITEM_KEY)
        prefs.remove(POSITION)
        prefs.remove(SAVED_AT)
    }

    private companion object {
        val CHANNEL = stringPreferencesKey("channel")
        val SLOT_START = longPreferencesKey("slot_start")
        val ITEM_KEY = stringPreferencesKey("item_key")
        val POSITION = longPreferencesKey("position")
        val SAVED_AT = longPreferencesKey("saved_at")
    }
}

/** A [SpotStore] in memory: tests, and the player's parts when nothing else is given. */
class MemorySpots(initial: SavedSpot? = null) : SpotStore {
    private val current = MutableStateFlow(initial)
    override val spot: Flow<SavedSpot?> = current

    /** The spot now, for tests. */
    val value: SavedSpot? get() = current.value

    override suspend fun save(spot: SavedSpot) {
        current.value = spot
    }

    override suspend fun clear(only: SavedSpot?) {
        if (only == null || current.value == only) current.value = null
    }

    override suspend fun channelPlays(channelId: String) {
        if (current.value?.channelId?.let { it != channelId } == true) current.value = null
    }
}
