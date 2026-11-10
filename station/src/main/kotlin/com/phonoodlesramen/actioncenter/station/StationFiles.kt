package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.LineupServer
import com.phonoodlesramen.actioncenter.plex.LinkedServer
import com.phonoodlesramen.actioncenter.plex.PlexAccount
import com.phonoodlesramen.actioncenter.plex.PlexLink
import com.phonoodlesramen.actioncenter.plex.PlexLinkStore
import com.phonoodlesramen.actioncenter.plex.PlexServer
import com.phonoodlesramen.actioncenter.plex.PlexServers
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * The station's own Plex sign-in (`plex.json`, mode 600): its client identifier, its plex.tv
 * token and account, and the linked server with that server's token. The TVs' sign-ins stay
 * on the TVs ("two clients, two tokens"). Tokens are read only when a request needs one,
 * and never logged.
 */
class FilePlexLinkStore(private val file: Path) : PlexLinkStore {
    @Serializable
    private data class Saved(
        val clientId: String,
        val account: PlexAccount? = null,
        val userToken: String? = null,
        val server: LinkedServer? = null,
        val serverToken: String? = null,
    )

    private val lock = Any()
    private val saved = MutableStateFlow(load())

    override val link: Flow<PlexLink> = saved.map { it.toLink() }.distinctUntilChanged()

    /** What `GET /v1/station` shows, without waiting on a flow. */
    val current: PlexLink get() = saved.value.toLink()

    /** The `X-Plex-Client-Identifier` plex.tv lists the station under, made once. */
    fun clientId(): String = saved.value.clientId

    override suspend fun userToken(): String? = saved.value.userToken

    override suspend fun serverToken(): String? = saved.value.serverToken

    override suspend fun saveServer(server: PlexServer, connection: PlexServers.Candidate) =
        save { it.copy(server = server.linked(connection), serverToken = server.accessToken) }

    /** A finished sign-in and the server it chose, saved together, so a half-done one never replaces a working link. */
    fun link(token: String, account: PlexAccount, server: PlexServer, connection: PlexServers.Candidate) =
        save { it.copy(account = account, userToken = token, server = server.linked(connection), serverToken = server.accessToken) }

    override suspend fun signOut() = save { Saved(clientId = it.clientId) }

    private fun save(change: (Saved) -> Saved) {
        synchronized(lock) {
            val next = change(saved.value)
            SecretFiles.writeJson(file, Saved.serializer(), next)
            saved.value = next
        }
    }

    /** A file that won't read starts over (signed out), rather than stopping the station. */
    private fun load(): Saved {
        val read = try {
            SecretFiles.readJson(file, Saved.serializer())
        } catch (e: IllegalArgumentException) {
            null
        }
        return read ?: Saved(clientId = UUID.randomUUID().toString()).also { SecretFiles.writeJson(file, Saved.serializer(), it) }
    }

    private fun Saved.toLink() = PlexLink(
        account = account?.takeIf { userToken != null },
        server = server?.takeIf { serverToken != null },
    )

    private fun PlexServer.linked(connection: PlexServers.Candidate) = LinkedServer(name, clientIdentifier, connection.uri, connection.kind)
}

/** [LineupServer] in `lineup.json`: the Plex server the lineup was made from (G19), and whether it has had its Family channel (K8). */
class FileLineupServer(private val file: Path) : LineupServer {
    @Serializable
    private data class Saved(val server: String? = null, val familyAdded: Boolean = false)

    private val lock = Any()

    @Volatile
    private var saved: Saved = try {
        SecretFiles.readJson(file, Saved.serializer())
    } catch (e: IllegalArgumentException) {
        null
    } ?: Saved()

    override suspend fun get(): String? = saved.server

    override suspend fun set(clientIdentifier: String?) = save { it.copy(server = clientIdentifier) }

    override suspend fun familyAdded(): Boolean = saved.familyAdded

    override suspend fun setFamilyAdded(added: Boolean) = save { it.copy(familyAdded = added) }

    private fun save(change: (Saved) -> Saved) {
        synchronized(lock) {
            val next = change(saved)
            if (next != saved) SecretFiles.writeJson(file, Saved.serializer(), next)
            saved = next
        }
    }
}
