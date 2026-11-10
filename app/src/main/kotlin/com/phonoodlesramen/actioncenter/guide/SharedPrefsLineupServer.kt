package com.phonoodlesramen.actioncenter.guide

import android.content.SharedPreferences
import androidx.core.content.edit

/** [LineupServer] in its own small preferences file. */
class SharedPrefsLineupServer(private val prefs: SharedPreferences) : LineupServer {
    override suspend fun get(): String? = prefs.getString(KEY, null)

    override suspend fun set(clientIdentifier: String?) = prefs.edit(commit = true) {
        if (clientIdentifier == null) remove(KEY) else putString(KEY, clientIdentifier)
    }

    override suspend fun familyAdded(): Boolean = prefs.getBoolean(FAMILY_KEY, false)

    override suspend fun setFamilyAdded(added: Boolean) = prefs.edit(commit = true) { putBoolean(FAMILY_KEY, added) }

    private companion object {
        const val KEY = "lineup_server"
        const val FAMILY_KEY = "family_channel"
    }
}
