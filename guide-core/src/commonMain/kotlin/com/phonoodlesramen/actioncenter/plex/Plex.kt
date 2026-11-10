package com.phonoodlesramen.actioncenter.plex

import java.io.IOException
import kotlinx.serialization.Serializable

/** A 4-character sign-in code for plex.tv/link (D13). [authToken] is set once it's claimed. */
data class PlexPin(val id: Long, val code: String, val expiresAtMillis: Long, val authToken: String?)

/** The plex.tv account that signed in (P5: its history drives the home screen). */
@Serializable
data class PlexAccount(val id: Long, val uuid: String, val username: String, val title: String)

data class PlexConnection(
    val uri: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val local: Boolean,
    val relay: Boolean,
    val ipv6: Boolean,
)

/** A Plex Media Server the account can see, with the token for talking to it. */
data class PlexServer(
    val name: String,
    /** The PMS machine identifier; Plezy's deep links use it as the server id. */
    val clientIdentifier: String,
    val accessToken: String,
    val owned: Boolean,
    val connections: List<PlexConnection>,
)

/**
 * The last sync, for the top bar, the hero's offline note (U15) and Settings › Plex.
 * [offline]: the server couldn't be reached. [failure]: it answered, but a request
 * failed (the request path and what went wrong).
 */
data class PlexSyncStatus(
    val offline: Boolean = false,
    val failure: String? = null,
    val lastSuccessMillis: Long? = null,
    val lastAttemptMillis: Long? = null,
)

/** How Action Center reaches its server (P3), shown in Settings › Plex. */
enum class ConnectionKind(val label: String) { LOCAL("Local"), REMOTE("Remote"), RELAY("Relay") }

/** A Plex request that failed. Only [Unauthorized] means "sign in again"; the rest are temporary. */
sealed class PlexException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Unauthorized : PlexException("Plex says the token is no longer valid")
    class Unreachable(cause: Throwable) : PlexException("Couldn't reach Plex: ${cause.message}", cause)
    class Failed(val code: Int, message: String) : PlexException(message)
}
