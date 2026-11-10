package com.phonoodlesramen.actioncenter.guide.editor

import android.util.Log
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The phone editor's server (plan §4, G17): a small HTTP/1.1 server on a plain
 * [ServerSocket], serving [EditorSite] on the home network while Edit on your phone is
 * open. No server library: Ktor doesn't support Android as a server, and NanoHTTPD's last
 * release was 2016.
 *
 * - **Runs** from [start] to [stop]: the screen starts it, and stops it when it closes or
 *   the home stops. It also stops itself after [idleMillis] without a request
 *   ([StopReason.IDLE]). Each start is a new run, with a new PIN, key and sessions.
 * - **Port:** the first free one of [ports], on [address]'s interface only.
 * - **Connections:** one coroutine each, at most [MAX_CONNECTIONS] at once, with a read
 *   timeout and a deadline for the whole request; one request each, then closed.
 * - **Never a crash:** nothing thrown on its threads leaves them (CrashGuard would open
 *   safe mode), and [stop] closes every socket it opened.
 * - **Secrets:** the PIN, key and cookies are never logged; [State.Running] leaves them
 *   out of its `toString`.
 * - **Pages:** the guide editor's ([EditorApi] and `index.html`), or Settings › AI blurbs'
 *   key page (M10: [api] takes the key, [page] is `key.html`), on the same sign-in.
 *
 * @param api what `/api` answers once signed in.
 * @param page the page's bytes, read once per run (`assets/editor/index.html`).
 * @param address the Shield's address on the home network; null shows [State.NoNetwork].
 * @param clock monotonic milliseconds, for the idle stop and request deadlines.
 */
class EditorServer(
    private val api: PhoneApi,
    private val page: () -> ByteArray,
    private val address: () -> InetAddress?,
    private val ports: List<Int> = DEFAULT_PORTS,
    private val idleMillis: Long = IDLE_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
    private val log: (String) -> Unit = { Log.w(LOG_TAG, it) },
) {
    /** The guide editor's server (G17): [editor] behind [EditorApi]. */
    constructor(
        editor: GuideEditor,
        page: () -> ByteArray,
        address: () -> InetAddress?,
        ports: List<Int> = DEFAULT_PORTS,
        idleMillis: Long = IDLE_MILLIS,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        random: SecureRandom = SecureRandom(),
        log: (String) -> Unit = { Log.w(LOG_TAG, it) },
    ) : this(EditorApi(editor, log), page, address, ports, idleMillis, clock, dispatcher, random, log)

    sealed interface State {
        data object Starting : State

        /**
         * Serving at [address] ("http://192.168.1.20:8137"): the TV shows it and [pin], and
         * the QR code carries [link], which holds the one-time key.
         */
        data class Running(val address: String, val pin: String, val link: String, val phoneConnected: Boolean) : State {
            override fun toString() = "Running(phoneConnected=$phoneConnected)"
        }

        /** The Shield has no IPv4 address on a network. */
        data object NoNetwork : State

        /** Every port in [ports] is taken. */
        data object NoPort : State

        data class Stopped(val reason: StopReason) : State
    }

    enum class StopReason {
        /** [idleMillis] without a request. */
        IDLE,

        /** The home stopped: Home, another app in front, or a screensaver that stops it. */
        AWAY,

        /** The screen closed. */
        CLOSED,
    }

    private val _state = MutableStateFlow<State>(State.Stopped(StopReason.CLOSED))
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var run: Run? = null

    /** Starts a new run unless one is going. Safe to call from the main thread: the socket opens on [dispatcher]. */
    fun start() {
        synchronized(lock) {
            if (run != null) return
            val handler = CoroutineExceptionHandler { _, e -> log("phone editor: ${e.javaClass.simpleName}") }
            val run = Run(CoroutineScope(SupervisorJob() + dispatcher + handler))
            this.run = run
            _state.value = State.Starting
            run.scope.launch { serve(run) }
        }
    }

    /** Stops the run, if any, closing its listening socket and every connection. */
    fun stop(reason: StopReason) = stop(reason, expected = null)

    private fun stop(reason: StopReason, expected: Run?) {
        val stopped = synchronized(lock) {
            val current = run ?: return
            if (expected != null && current !== expected) return
            run = null
            _state.value = State.Stopped(reason)
            current
        }
        stopped.close()
    }

    /** Ends [run] with [state] (no network, no port) if it's still the current one. */
    private fun fail(run: Run, state: State) {
        synchronized(lock) {
            if (this.run !== run) return
            this.run = null
            _state.value = state
        }
        run.close()
    }

    private suspend fun serve(run: Run) {
        val address = runCatching(address).getOrNull() ?: return fail(run, State.NoNetwork)
        val page = try {
            page()
        } catch (e: Exception) {
            log("phone editor: page ${e.javaClass.simpleName}")
            MISSING_PAGE
        }
        val server = bind(address) ?: return fail(run, State.NoPort)
        if (!run.adopt(server)) return
        val signIn = EditorSignIn(random)
        val site = EditorSite(signIn, api, page)
        val base = "http://${address.hostAddress}:${server.localPort}"
        synchronized(lock) {
            if (this.run !== run) return
            _state.value = State.Running(base, signIn.pin, "$base/?k=${signIn.key}", phoneConnected = false)
        }
        run.scope.launch {
            signIn.signedIn.collect { connected ->
                synchronized(lock) {
                    if (this@EditorServer.run === run) _state.update { (it as? State.Running)?.copy(phoneConnected = connected) ?: it }
                }
            }
        }
        run.scope.launch { watchIdle(run) }
        accept(run, server, site)
    }

    /** The first free port of [ports] on [address]; null if every one is taken. */
    private fun bind(address: InetAddress): ServerSocket? {
        for (port in ports) {
            val socket = ServerSocket()
            try {
                // A port the last run used may still be in TIME_WAIT; reuse doesn't let two runs share one.
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(address, port), BACKLOG)
                return socket
            } catch (_: IOException) {
                socket.closeQuietly()
            }
        }
        return null
    }

    private fun accept(run: Run, server: ServerSocket, site: EditorSite) {
        while (run.scope.isActive) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                // Closed by stop().
                return
            }
            if (!run.track(client)) return
            if (run.connections > MAX_CONNECTIONS) {
                run.untrack(client)
                continue
            }
            run.scope.launch { answer(run, client, site) }
        }
    }

    private suspend fun answer(run: Run, client: Socket, site: EditorSite) {
        try {
            client.soTimeout = READ_TIMEOUT_MILLIS
            client.tcpNoDelay = true
            val response = try {
                val request = HttpParser(clock = clock).read(BufferedInputStream(client.getInputStream()), clock() + REQUEST_DEADLINE_MILLIS)
                run.lastRequest = clock()
                site.handle(request)
            } catch (e: HttpError) {
                val allow = if (e.status == 405) listOf("Allow" to HttpParser.METHODS.joinToString()) else emptyList()
                HttpResponse.error(e.status, e.message ?: HttpWriter.reason(e.status), allow)
            }
            HttpWriter.write(client.getOutputStream(), response)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // The phone went away, sent nothing (a browser's spare connection) or was too slow.
        } catch (e: Exception) {
            log("phone editor: ${e.javaClass.simpleName}")
        } finally {
            run.untrack(client)
        }
    }

    private suspend fun watchIdle(run: Run) {
        while (true) {
            val left = run.lastRequest + idleMillis - clock()
            if (left <= 0) break
            delay(left)
        }
        stop(StopReason.IDLE, expected = run)
    }

    /** One run's scope and sockets. Closing it closes them all, including any opened after. */
    private inner class Run(val scope: CoroutineScope) {
        @Volatile var lastRequest: Long = clock()
        private var server: ServerSocket? = null
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        private var closed = false

        val connections: Int get() = clients.size

        @Synchronized
        fun adopt(socket: ServerSocket): Boolean {
            if (closed) socket.closeQuietly() else server = socket
            return !closed
        }

        @Synchronized
        fun track(client: Socket): Boolean {
            if (closed) client.closeQuietly() else clients += client
            return !closed
        }

        fun untrack(client: Socket) {
            clients -= client
            client.closeQuietly()
        }

        @Synchronized
        fun close() {
            closed = true
            scope.cancel()
            server?.closeQuietly()
            clients.forEach { it.closeQuietly() }
            clients.clear()
        }
    }

    companion object {
        /** 8137, or the next few if it's taken. */
        val DEFAULT_PORTS = (8137..8141).toList()

        const val IDLE_MILLIS = 15 * 60_000L

        /** A phone opens a few at once; more than this is someone else. */
        const val MAX_CONNECTIONS = 16

        const val READ_TIMEOUT_MILLIS = 10_000
        const val REQUEST_DEADLINE_MILLIS = 30_000L
        private const val BACKLOG = 16

        const val PAGE_ASSET = "editor/index.html"

        /** Settings › AI blurbs › Add a key on your phone (M10): one field, on the same sign-in. */
        const val KEY_PAGE_ASSET = "editor/key.html"
        const val LOG_TAG = "ActionCenterEditor"

        private val MISSING_PAGE = "<!doctype html><title>Action Center</title><p>The editor page is missing. Reinstall Action Center.".toByteArray()

        private fun Closeable.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // Closing is best effort.
            }
        }
    }
}
