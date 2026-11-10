package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.station.PlexLinkStarted
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkState
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkStatus
import com.phonoodlesramen.actioncenter.plex.ConnectionKind
import com.phonoodlesramen.actioncenter.plex.PlexAccount
import com.phonoodlesramen.actioncenter.plex.PlexAccounts
import com.phonoodlesramen.actioncenter.plex.PlexDevice
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexPin
import com.phonoodlesramen.actioncenter.plex.PlexServer
import com.phonoodlesramen.actioncenter.plex.PlexServers
import com.phonoodlesramen.actioncenter.plex.ServerReach
import com.phonoodlesramen.actioncenter.safety.summary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the station tells plex.tv it is: listed under Authorized Devices as "Action Center guide station". */
val STATION_PLEX_DEVICE = PlexDevice(
    platform = "Linux",
    platformVersion = System.getProperty("os.version").orEmpty(),
    device = "Docker",
    deviceName = "Action Center guide station",
)

/**
 * The station's own plex.tv sign-in (`POST/GET /v1/plex/link`): the station's side of the
 * launcher's `PlexSession`, driven from a TV that shows the code.
 *
 * - **The code:** a plex.tv PIN, checked once a second until it's claimed or expires.
 * - **The server (P2, P3):** an owned server, as the launcher picks it; with more than one,
 *   the first that answers on a local connection, else the first that answers. Until one
 *   answers it tries again every [retryMillis], for up to [connectForMillis].
 * - **Another server:** the cache is emptied first, so the guide remakes its lineup from the
 *   new server's library once it has synced (G19).
 * - The sign-in and its server are saved together, so a sign-in that never finds a server
 *   leaves the working link as it was.
 */
class StationPlexLink(
    private val store: FilePlexLinkStore,
    private val accounts: PlexAccounts,
    private val reach: ServerReach,
    /** Empties the Plex cache (`PlexSync.clear`). */
    private val clearCache: suspend () -> Unit,
    /** Syncs the library now and extends the guide. */
    private val syncNow: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val pollMillis: Long = POLL_MILLIS,
    private val retryMillis: Long = RETRY_MILLIS,
    private val connectForMillis: Long = CONNECT_FOR_MILLIS,
) {
    private sealed interface Attempt {
        data class Waiting(val pin: PlexPin) : Attempt

        data object Connecting : Attempt

        data object Expired : Attempt
    }

    @Volatile
    private var attempt: Attempt? = null

    /** Why the last sign-in hasn't linked a server, for `GET /v1/station`. */
    @Volatile
    var problem: String? = null
        private set

    private val starting = Mutex()
    private var job: Job? = null

    /** A new code (the one before stops). Throws [ApiException] when plex.tv can't be reached. */
    suspend fun start(): PlexLinkStarted = starting.withLock {
        job?.cancelAndJoin()
        val pin = try {
            accounts.createPin()
        } catch (e: PlexException) {
            log("Plex: couldn't get a sign-in code: ${e.summary()}")
            throw ApiException(502, "Couldn't reach plex.tv. Try again in a minute.")
        }
        attempt = Attempt.Waiting(pin)
        problem = null
        job = scope.launch { follow(pin) }
        log("Plex: sign-in started; waiting for the code to be entered at plex.tv/link")
        PlexLinkStarted(pin.code, pin.expiresAtMillis)
    }

    fun status(): PlexLinkStatus {
        val link = store.current
        return when (val now = attempt) {
            is Attempt.Waiting -> PlexLinkStatus(if (clock() < now.pin.expiresAtMillis) PlexLinkState.WAITING else PlexLinkState.EXPIRED)
            Attempt.Connecting -> PlexLinkStatus(PlexLinkState.WAITING)
            Attempt.Expired -> PlexLinkStatus(PlexLinkState.EXPIRED)
            null -> if (link.linked) PlexLinkStatus(PlexLinkState.LINKED, link.server?.name) else PlexLinkStatus(PlexLinkState.EXPIRED)
        }
    }

    private suspend fun follow(pin: PlexPin) {
        try {
            val token = claimed(pin)
            if (token == null) {
                attempt = Attempt.Expired
                log("Plex: the sign-in code expired")
                return
            }
            attempt = Attempt.Connecting
            val account = try {
                accounts.account(token)
            } catch (e: PlexException) {
                attempt = Attempt.Expired
                problem = "Couldn't finish signing in to Plex. Try again."
                log("Plex: account not read: ${e.summary()}")
                return
            }
            connect(token, account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            attempt = Attempt.Expired
            problem = "Couldn't finish signing in to Plex. Try again."
            log("Plex: sign-in failed: ${e.summary()}")
        }
    }

    /** The token once the code is claimed; null when it expires first. */
    private suspend fun claimed(pin: PlexPin): String? {
        while (clock() < pin.expiresAtMillis) {
            delay(pollMillis)
            val checked = try {
                accounts.checkPin(pin.id) ?: return null
            } catch (e: PlexException) {
                continue
            }
            checked.authToken?.let { return it }
        }
        return null
    }

    private suspend fun connect(token: String, account: PlexAccount) {
        val giveUpAt = clock() + connectForMillis
        while (true) {
            val why = tryConnect(token, account) ?: return
            problem = why
            if (why == REFUSED || clock() >= giveUpAt) {
                attempt = Attempt.Expired
                log("Plex: not linked: $why")
                return
            }
            delay(retryMillis)
        }
    }

    /** Null once linked; otherwise why not, as a sentence. */
    private suspend fun tryConnect(token: String, account: PlexAccount): String? {
        val owned = try {
            PlexServers.owned(accounts.servers(token))
        } catch (e: PlexException.Unauthorized) {
            return REFUSED
        } catch (e: PlexException) {
            return "Couldn't reach plex.tv."
        }
        if (owned.isEmpty()) return "Your Plex account has no server of its own."
        var chosen: Pair<PlexServer, PlexServers.Candidate>? = null
        for (server in owned) {
            val connection = reach.reach(server) ?: continue
            if (connection.kind == ConnectionKind.LOCAL) {
                chosen = server to connection
                break
            }
            if (chosen == null) chosen = server to connection
        }
        val (server, connection) = chosen ?: return "Can't reach your Plex server ${owned.first().name}."
        val before = store.current.server?.clientIdentifier
        if (before != null && before != server.clientIdentifier) {
            clearCache()
            log("Plex: another server; emptied the cache")
        }
        store.link(token, account, server, connection)
        attempt = null
        problem = null
        log("Plex: linked to ${server.name} (${connection.kind.label.lowercase()})")
        scope.launch { syncNow() }
        return null
    }

    companion object {
        /** Plex's docs: check the code once a second. */
        const val POLL_MILLIS = 1_000L
        const val RETRY_MILLIS = 30_000L
        const val CONNECT_FOR_MILLIS = 15 * 60_000L
        private const val REFUSED = "Plex didn't accept the sign-in. Try again."
    }
}
