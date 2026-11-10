package com.phonoodlesramen.actioncenter.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** What setup remembers on this TV. Nothing in it leaves the TV. */
interface SetupStore {
    /** Settings › Setup › Show in the top bar is off. */
    val chipHidden: Flow<Boolean>
    suspend fun setChipHidden(hidden: Boolean)

    /** HomeWatch has been seen on, so its going off is news ([HomeRoute.WENT_OFF]). */
    suspend fun homeWatchWasOn(): Boolean
    suspend fun setHomeWatchWasOn(on: Boolean)

    /** Android has been asked for [permission] before: after a refusal, the next ask may never show. */
    suspend fun asked(permission: SetupPermission): Boolean
    suspend fun markAsked(permission: SetupPermission)

    /** In memory, for tests and previews. */
    class InMemory : SetupStore {
        private val hidden = MutableStateFlow(false)
        private var wasOn = false
        private val askedFor = mutableSetOf<SetupPermission>()

        override val chipHidden: Flow<Boolean> = hidden
        override suspend fun setChipHidden(hidden: Boolean) = this.hidden.update { hidden }
        override suspend fun homeWatchWasOn() = wasOn
        override suspend fun setHomeWatchWasOn(on: Boolean) {
            wasOn = on
        }

        override suspend fun asked(permission: SetupPermission) = permission in askedFor
        override suspend fun markAsked(permission: SetupPermission) {
            askedFor += permission
        }
    }
}

/** [SetupStore] in its own DataStore file, `setup`. */
class DataStoreSetup(private val store: DataStore<Preferences>) : SetupStore {
    override val chipHidden: Flow<Boolean> = store.data.map { it[CHIP_HIDDEN] ?: false }

    override suspend fun setChipHidden(hidden: Boolean) {
        store.edit { it[CHIP_HIDDEN] = hidden }
    }

    override suspend fun homeWatchWasOn(): Boolean = store.data.first()[HOME_WATCH_WAS_ON] ?: false

    override suspend fun setHomeWatchWasOn(on: Boolean) {
        store.edit { it[HOME_WATCH_WAS_ON] = on }
    }

    override suspend fun asked(permission: SetupPermission): Boolean = permission.name in (store.data.first()[ASKED] ?: emptySet())

    override suspend fun markAsked(permission: SetupPermission) {
        store.edit { it[ASKED] = (it[ASKED] ?: emptySet()) + permission.name }
    }

    private companion object {
        val CHIP_HIDDEN = booleanPreferencesKey("chip_hidden")
        val HOME_WATCH_WAS_ON = booleanPreferencesKey("home_watch_was_on")
        val ASKED = stringSetPreferencesKey("asked")
    }
}
