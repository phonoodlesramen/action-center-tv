package com.phonoodlesramen.actioncenter.profiles

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonoodlesramen.actioncenter.plex.SecretBox
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * [KidTokens] in their own DataStore file (K2): each linked kid's server token, sealed with
 * [SecretBox] like yours (P4), under their profile's id, and their Plex Home user's UUID
 * (Phase 11). Backups are off, so none leaves the
 * Shield. A token that can't be unsealed reads as none: link the kid again.
 */
class SealedKidTokens(private val store: DataStore<Preferences>, private val box: SecretBox) : KidTokens {
    override suspend fun serverToken(kidId: String): String? = read()[key(kidId)]?.let(box::open)

    override suspend fun save(kidId: String, serverToken: String) {
        store.edit { it[key(kidId)] = box.seal(serverToken) }
    }

    override suspend fun forget(kidId: String) {
        store.edit {
            it.remove(key(kidId))
            it.remove(uuidKey(kidId))
        }
    }

    /** Not a secret (plex.tv lists it to anyone in your Home), so it's kept as it is. */
    override suspend fun plexUuid(kidId: String): String? = read()[uuidKey(kidId)]?.takeIf { it.isNotBlank() }

    override suspend fun saveUuid(kidId: String, uuid: String) {
        store.edit { it[uuidKey(kidId)] = uuid }
    }

    private suspend fun read(): Preferences = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()

    private fun key(kidId: String) = stringPreferencesKey("server_token_$kidId")

    private fun uuidKey(kidId: String) = stringPreferencesKey("plex_uuid_$kidId")
}
