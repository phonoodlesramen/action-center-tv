package com.phonoodlesramen.actioncenter.tvprovider

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Settings › Channels & apps. Every app feeds Continue in your apps until it's turned off (Q2). */
data class ChannelsAndAppsPrefs(
    /** Apps turned off in Continue in your apps, by package name. */
    val hiddenApps: Set<String> = emptySet(),
)

/** Settings › Channels & apps has its own DataStore file (D10), so nothing else can corrupt it. */
class ChannelsAndAppsStore(private val store: DataStore<Preferences>) {
    val prefs: Flow<ChannelsAndAppsPrefs> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { ChannelsAndAppsPrefs(hiddenApps = it[HIDDEN_APPS].orEmpty()) }
        .distinctUntilChanged()

    suspend fun setAppShown(packageName: String, shown: Boolean) {
        store.edit {
            val hidden = it[HIDDEN_APPS].orEmpty()
            it[HIDDEN_APPS] = if (shown) hidden - packageName else hidden + packageName
        }
    }

    private companion object {
        val HIDDEN_APPS = stringSetPreferencesKey("hidden_apps")
    }
}
