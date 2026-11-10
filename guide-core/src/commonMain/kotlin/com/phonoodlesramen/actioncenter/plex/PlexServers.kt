package com.phonoodlesramen.actioncenter.plex

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Which server to use (P2) and the order to try its connections in (P3). */
object PlexServers {
    /** Your own servers only; shared servers are left out (P2). */
    fun owned(servers: List<PlexServer>): List<PlexServer> = servers.filter { it.owned }

    /**
     * Local first, then remote, relay last; IPv4 before IPv6 within each. A local
     * `plex.direct` address is followed by plain `http://<ip>:<port>`, for routers whose
     * DNS-rebind protection blocks plex.direct names (research §4).
     */
    fun candidates(server: PlexServer): List<Candidate> {
        fun kind(c: PlexConnection) = when {
            c.relay -> ConnectionKind.RELAY
            c.local -> ConnectionKind.LOCAL
            else -> ConnectionKind.REMOTE
        }
        val ordered = server.connections.sortedWith(compareBy({ kind(it).ordinal }, { it.ipv6 }))
        return ordered.flatMap { connection ->
            val kind = kind(connection)
            val direct = Candidate(connection.uri.trimEnd('/'), kind)
            val plain = "http://${connection.address}:${connection.port}"
            if (kind == ConnectionKind.LOCAL && !connection.ipv6 && direct.uri != plain) {
                listOf(direct, Candidate(plain, kind))
            } else {
                listOf(direct)
            }
        }.distinctBy { it.uri }
    }

    data class Candidate(val uri: String, val kind: ConnectionKind)
}

/** Finds the first connection that answers as [PlexServer.clientIdentifier]. */
fun interface ServerReach {
    suspend fun reach(server: PlexServer): PlexServers.Candidate?
}

/**
 * Tries each candidate's `/identity` with a short timeout. A different machine
 * answering (another server on the same IP) doesn't count.
 */
class PlexServerReach(
    http: OkHttpClient,
    private val headers: PlexHeaders,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    timeoutSeconds: Long = 4,
) : ServerReach {
    private val probe = http.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()

    override suspend fun reach(server: PlexServer): PlexServers.Candidate? =
        PlexServers.candidates(server).firstOrNull { identity(it.uri, server.accessToken) == server.clientIdentifier }

    private suspend fun identity(base: String, token: String): String? = withContext(io) {
        val url = "$base/identity".toHttpUrlOrNull() ?: return@withContext null
        val request = headers.apply(Request.Builder().url(url), token).build()
        try {
            probe.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                runCatching { json.decodeFromString<IdentityJson>(response.body.string()).container.machineIdentifier }.getOrNull()
            }
        } catch (_: IOException) {
            null
        }
    }

    @Serializable
    private class IdentityJson(@SerialName("MediaContainer") val container: Container)

    @Serializable
    private class Container(val machineIdentifier: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
