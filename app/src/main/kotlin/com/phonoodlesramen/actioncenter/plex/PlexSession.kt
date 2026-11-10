package com.phonoodlesramen.actioncenter.plex

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Where sign-in is, for the hero and Settings › Plex. */
sealed interface PlexSignIn {
    data object GettingCode : PlexSignIn

    /** Enter [code] at plex.tv/link (U14). */
    data class ShowingCode(val code: String, val expiresAtMillis: Long) : PlexSignIn

    /** Signed in; finding the server and a connection that works. */
    data object Connecting : PlexSignIn

    /** You own more than one server: pick one in Settings › Plex (P2). */
    data class ChooseServer(val servers: List<ServerChoice>) : PlexSignIn

    /** The account owns no server (shared servers don't count, P2). */
    data object NoServer : PlexSignIn

    /** Your server is known, but none of its connections answers. Retried while visible. */
    data class CantReachServer(val name: String) : PlexSignIn

    /** plex.tv can't be reached. Retried while visible. */
    data object Offline : PlexSignIn

    data object Linked : PlexSignIn
}

data class ServerChoice(val name: String, val clientIdentifier: String)

/**
 * Signs in with a code at plex.tv/link (D13, P1) and links your server (P2, P3).
 * [link] runs only while the home screen is visible: Android stops it on leaving,
 * so no code is polled in the background.
 */
class PlexSession(
    private val store: PlexStore,
    private val accounts: PlexAccounts,
    private val reach: ServerReach,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<PlexSignIn?>(null)
    val status: StateFlow<PlexSignIn?> = _status.asStateFlow()

    private val chosen = MutableStateFlow<String?>(null)
    private var choices: List<PlexServer> = emptyList()

    /** Returns once linked. Cancel it to stop (leaving the home screen, or Get a new code). */
    suspend fun link() {
        while (true) {
            val current = store.link.first()
            when {
                current.linked -> {
                    _status.value = PlexSignIn.Linked
                    return
                }
                current.signedIn -> {
                    val token = store.userToken()
                    if (token == null || !connect(token)) delay(RETRY_MILLIS)
                }
                else -> signIn()
            }
        }
    }

    /** Settings › Plex, when you own more than one server (P2). */
    fun choose(clientIdentifier: String) {
        chosen.value = clientIdentifier
    }

    suspend fun signOut() {
        store.signOut()
        choices = emptyList()
        chosen.value = null
        _status.value = null
    }

    /** One code: show it, poll once a second until it's claimed or expires. */
    private suspend fun signIn() {
        if (_status.value !is PlexSignIn.ShowingCode) _status.value = PlexSignIn.GettingCode
        val pin = attempt { accounts.createPin() } ?: return offline()
        _status.value = PlexSignIn.ShowingCode(pin.code, pin.expiresAtMillis)
        while (clock() < pin.expiresAtMillis) {
            delay(POLL_MILLIS)
            val checked = try {
                accounts.checkPin(pin.id) ?: return
            } catch (e: PlexException) {
                continue
            }
            val token = checked.authToken ?: continue
            val account = attempt { accounts.account(token) } ?: return offline()
            store.saveAccount(token, account)
            return
        }
    }

    /** True once a server is linked. */
    private suspend fun connect(token: String): Boolean {
        _status.value = PlexSignIn.Connecting
        val owned = try {
            PlexServers.owned(accounts.servers(token))
        } catch (e: PlexException.Unauthorized) {
            store.signOut()
            return false
        } catch (e: PlexException) {
            _status.value = PlexSignIn.Offline
            return false
        }
        val server = when (owned.size) {
            0 -> {
                _status.value = PlexSignIn.NoServer
                return false
            }
            1 -> owned.single()
            else -> {
                choices = owned
                _status.value = PlexSignIn.ChooseServer(owned.map { ServerChoice(it.name, it.clientIdentifier) })
                val id = chosen.first { id -> id != null && owned.any { it.clientIdentifier == id } }
                owned.first { it.clientIdentifier == id }
            }
        }
        _status.value = PlexSignIn.Connecting
        val connection = reach.reach(server)
        if (connection == null) {
            _status.value = PlexSignIn.CantReachServer(server.name)
            return false
        }
        store.saveServer(server, connection)
        _status.value = PlexSignIn.Linked
        return true
    }

    private suspend fun offline() {
        _status.value = PlexSignIn.Offline
        delay(RETRY_MILLIS)
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PlexException) {
        null
    }

    companion object {
        /** Plex's docs: check the code once a second. */
        const val POLL_MILLIS = 1_000L
        const val RETRY_MILLIS = 30_000L
    }
}
