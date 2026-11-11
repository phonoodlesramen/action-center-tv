package com.phonoodlesramen.actioncenter.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** Updates in Settings › About, on this TV. */
enum class UpdateMode {
    /** Checks once a day and says so in About; downloads and installs only on a press. The default. */
    ASK,

    /** Never checks by itself; Check for updates still works. */
    OFF,
}

/** What the updater remembers between runs, on this TV. Nothing in it leaves the TV. */
@Serializable
data class UpdateRecord(
    /** When the last check got an answer (wall clock). */
    val checkedMillis: Long? = null,
    /** The latest release's `update.json`, as [UpdateInfo.encode] wrote it, from the last good check. */
    val latest: String? = null,
    /** The last check's or install's failure, for About's line, and when. */
    val problem: String? = null,
    val problemMillis: Long? = null,
    /** The version code whose APK is downloaded and checked, in the cache. */
    val downloaded: Long? = null,
    /** The version code this app last started as: a higher one now means it was updated. */
    val seenVersionCode: Long? = null,
)

/** Where [UpdateRecord] and [UpdateMode] live. */
interface UpdateStore {
    val mode: Flow<UpdateMode>
    suspend fun setMode(mode: UpdateMode)

    val record: Flow<UpdateRecord>
    suspend fun update(change: (UpdateRecord) -> UpdateRecord)

    /** In memory, for tests and previews. */
    class InMemory(mode: UpdateMode = UpdateMode.ASK, record: UpdateRecord = UpdateRecord()) : UpdateStore {
        private val modeFlow = MutableStateFlow(mode)
        private val recordFlow = MutableStateFlow(record)
        override val mode: Flow<UpdateMode> = modeFlow
        override suspend fun setMode(mode: UpdateMode) = modeFlow.update { mode }
        override val record: Flow<UpdateRecord> = recordFlow
        override suspend fun update(change: (UpdateRecord) -> UpdateRecord) = recordFlow.update(change)
    }
}

/** [UpdateStore] in its own DataStore file, `updates`. A record it can't read starts over. */
class DataStoreUpdates(private val store: DataStore<Preferences>) : UpdateStore {
    override val mode: Flow<UpdateMode> = store.data.map { prefs ->
        UpdateMode.entries.firstOrNull { it.name == prefs[MODE] } ?: UpdateMode.ASK
    }

    override suspend fun setMode(mode: UpdateMode) {
        store.edit { it[MODE] = mode.name }
    }

    override val record: Flow<UpdateRecord> = store.data.map { decode(it[RECORD]) }

    override suspend fun update(change: (UpdateRecord) -> UpdateRecord) {
        store.edit { it[RECORD] = UpdateInfo.json.encodeToString(UpdateRecord.serializer(), change(decode(it[RECORD]))) }
    }

    /** The record as it is now. */
    suspend fun current(): UpdateRecord = record.first()

    private fun decode(text: String?): UpdateRecord =
        text?.let { runCatching { UpdateInfo.json.decodeFromString(UpdateRecord.serializer(), it) }.getOrNull() } ?: UpdateRecord()

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val RECORD = stringPreferencesKey("record")
    }
}
