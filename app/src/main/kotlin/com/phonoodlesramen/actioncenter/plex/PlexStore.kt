package com.phonoodlesramen.actioncenter.plex

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Seals secrets before they're stored (P4). [open] returns null for anything it can't read. */
interface SecretBox {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

/** AES-256-GCM with a key that never leaves the Android Keystore. Lose the key and the token is unreadable: sign in again. */
class KeystoreSecretBox(private val alias: String = "plex-tokens") : SecretBox {
    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? = try {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    } catch (_: Exception) {
        null
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * The Plex sign-in, in its own DataStore. The plex.tv token and the server token are
 * sealed with [SecretBox] (P4); backups are off, so neither leaves the Shield.
 */
class PlexStore(private val store: DataStore<Preferences>, private val box: SecretBox) : PlexLinkStore {
    override val link: Flow<PlexLink> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val account = prefs[ACCOUNT]?.let { decode<PlexAccount>(it) }
            val readable = prefs[USER_TOKEN]?.let(box::open) != null
            PlexLink(
                account = account?.takeIf { readable },
                server = prefs[SERVER]?.let { decode<LinkedServer>(it) }?.takeIf { prefs[SERVER_TOKEN]?.let(box::open) != null },
            )
        }

    /** The linked server and its token, for requests and artwork; null until linked. */
    val access: Flow<ServerAccess?> = link
        .map { current -> current.server?.let { server -> serverToken()?.let { ServerAccess(server.uri, it) } } }
        .distinctUntilChanged()

    /** Plex's `X-Plex-Client-Identifier`: made once, kept across sign-outs. */
    suspend fun clientId(): String {
        store.data.first()[CLIENT_ID]?.let { return it }
        var id = ""
        store.edit { id = it[CLIENT_ID] ?: UUID.randomUUID().toString().also { new -> it[CLIENT_ID] = new } }
        return id
    }

    suspend fun saveAccount(token: String, account: PlexAccount) {
        store.edit {
            it[USER_TOKEN] = box.seal(token)
            it[ACCOUNT] = json.encodeToString(account)
        }
    }

    override suspend fun saveServer(server: PlexServer, connection: PlexServers.Candidate) {
        store.edit {
            it[SERVER] = json.encodeToString(LinkedServer(server.name, server.clientIdentifier, connection.uri, connection.kind))
            it[SERVER_TOKEN] = box.seal(server.accessToken)
        }
    }

    override suspend fun userToken(): String? = store.data.first()[USER_TOKEN]?.let(box::open)

    override suspend fun serverToken(): String? = store.data.first()[SERVER_TOKEN]?.let(box::open)

    /** Forgets the account, the server and both tokens. The client identifier stays. */
    override suspend fun signOut() {
        store.edit {
            it.remove(USER_TOKEN)
            it.remove(ACCOUNT)
            it.remove(SERVER)
            it.remove(SERVER_TOKEN)
        }
    }

    private inline fun <reified T> decode(text: String): T? = runCatching { json.decodeFromString<T>(text) }.getOrNull()

    private companion object {
        val CLIENT_ID = stringPreferencesKey("client_id")
        val USER_TOKEN = stringPreferencesKey("user_token")
        val ACCOUNT = stringPreferencesKey("account")
        val SERVER = stringPreferencesKey("server")
        val SERVER_TOKEN = stringPreferencesKey("server_token")
        val json = Json { ignoreUnknownKeys = true }
    }
}
