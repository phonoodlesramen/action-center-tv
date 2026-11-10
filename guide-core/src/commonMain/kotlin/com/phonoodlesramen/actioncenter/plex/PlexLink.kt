package com.phonoodlesramen.actioncenter.plex

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** The server Action Center talks to, and how it reaches it (P2, P3). */
@Serializable
data class LinkedServer(val name: String, val clientIdentifier: String, val uri: String, val kind: ConnectionKind)

/** What's linked. Tokens stay out of it: they're read only when a request needs one. */
data class PlexLink(val account: PlexAccount? = null, val server: LinkedServer? = null) {
    val signedIn: Boolean get() = account != null
    val linked: Boolean get() = account != null && server != null
}

/**
 * The Plex sign-in as the library sync ([com.phonoodlesramen.actioncenter.plex.cache.PlexSync])
 * reads and updates it: what's linked, its tokens, a server's new address, and signing out
 * when plex.tv no longer accepts the account. The TVs keep it sealed in a DataStore
 * (`PlexStore`, in `:app`).
 */
interface PlexLinkStore {
    /** The account and server; each counts only while its token can be read. */
    val link: Flow<PlexLink>

    /** The plex.tv token; null when signed out or unreadable. */
    suspend fun userToken(): String?

    /** The linked server's token; null until linked, or when unreadable. */
    suspend fun serverToken(): String?

    /** Links [server] at [connection], with the server's own token. */
    suspend fun saveServer(server: PlexServer, connection: PlexServers.Candidate)

    /** Forgets the account, the server and both tokens. */
    suspend fun signOut()
}
