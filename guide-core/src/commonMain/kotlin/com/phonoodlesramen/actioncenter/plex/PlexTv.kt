package com.phonoodlesramen.actioncenter.plex

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What sign-in needs from plex.tv, so the sign-in flow can be tested without a network. */
interface PlexAccounts {
    suspend fun createPin(): PlexPin

    /** Null when the code has expired or doesn't exist (404). */
    suspend fun checkPin(id: Long): PlexPin?

    /** Throws [PlexException.Unauthorized] when the token is no longer valid. */
    suspend fun account(token: String): PlexAccount

    suspend fun servers(token: String): List<PlexServer>
}

/**
 * What Action Center runs on, as plex.tv lists it under Authorized Devices: the
 * `X-Plex-Platform`, `-Platform-Version`, `-Device` and `-Device-Name` headers. The TVs
 * say they're a SHIELD on Android 11 (`:app`); the guide station says what it is.
 */
data class PlexDevice(val platform: String, val platformVersion: String, val device: String, val deviceName: String)

/**
 * Who Action Center says it is on every Plex request (the `X-Plex-*` headers). The
 * client identifier is stored once and reused; it's what plex.tv lists under
 * Authorized Devices.
 */
class PlexHeaders(private val clientId: suspend () -> String, private val version: String, private val device: PlexDevice) {
    suspend fun apply(builder: Request.Builder, token: String? = null): Request.Builder {
        builder
            .header("Accept", "application/json")
            .header("X-Plex-Client-Identifier", clientId())
            .header("X-Plex-Product", PRODUCT)
            .header("X-Plex-Version", version)
            .header("X-Plex-Platform", device.platform)
            .header("X-Plex-Platform-Version", device.platformVersion)
            .header("X-Plex-Device", device.device)
            .header("X-Plex-Device-Name", device.deviceName)
        if (token != null) builder.header("X-Plex-Token", token)
        return builder
    }

    /**
     * The same identity for a media request (the guide's player): no `Accept`, and the
     * token as a header so it never appears in a URL or a log.
     */
    suspend fun media(token: String): Map<String, String> = mapOf(
        "X-Plex-Client-Identifier" to clientId(),
        "X-Plex-Product" to PRODUCT,
        "X-Plex-Version" to version,
        "X-Plex-Platform" to device.platform,
        "X-Plex-Device" to device.device,
        "X-Plex-Token" to token,
    )

    companion object {
        const val PRODUCT = "Action Center"
    }
}

/**
 * plex.tv's account API (official PMS docs, "Traditional Token Authentication"): the
 * sign-in code, the account, and the servers it can reach.
 */
class PlexTv(
    private val http: OkHttpClient,
    private val headers: PlexHeaders,
    private val baseUrl: HttpUrl = "https://plex.tv/".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlexAccounts {
    /** A short code (no `strong`): 4 characters, valid for 15 minutes. */
    override suspend fun createPin(): PlexPin {
        val request = headers.apply(Request.Builder().url(url("api/v2/pins")).post(ByteArray(0).toRequestBody())).build()
        return call<PinJson>(request).toPin()
    }

    override suspend fun checkPin(id: Long): PlexPin? {
        val request = headers.apply(Request.Builder().url(url("api/v2/pins/$id"))).build()
        return try {
            call<PinJson>(request).toPin()
        } catch (e: PlexException.Failed) {
            if (e.code == 404) null else throw e
        }
    }

    override suspend fun account(token: String): PlexAccount {
        val request = headers.apply(Request.Builder().url(url("api/v2/user")), token).build()
        return call<UserJson>(request).let { PlexAccount(it.id, it.uuid, it.username, it.title ?: it.username) }
    }

    override suspend fun servers(token: String): List<PlexServer> {
        val url = url("api/v2/resources").newBuilder()
            .addQueryParameter("includeHttps", "1")
            .addQueryParameter("includeRelay", "1")
            .addQueryParameter("includeIPv6", "1")
            .build()
        val request = headers.apply(Request.Builder().url(url), token).build()
        return call<List<ResourceJson>>(request)
            .filter { "server" in it.provides.split(",") && it.accessToken != null }
            .map { resource ->
                PlexServer(
                    name = resource.name,
                    clientIdentifier = resource.clientIdentifier,
                    accessToken = resource.accessToken.orEmpty(),
                    owned = resource.owned,
                    connections = resource.connections.map {
                        PlexConnection(it.uri, it.protocol, it.address, it.port, it.local, it.relay, it.ipv6)
                    },
                )
            }
    }

    private fun url(path: String) = baseUrl.resolve(path) ?: error("Bad plex.tv path: $path")

    private suspend inline fun <reified T> call(request: Request): T = withContext(io) {
        val body = try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 401 -> throw PlexException.Unauthorized()
                    !response.isSuccessful -> throw PlexException.Failed(response.code, "plex.tv answered ${response.code}")
                }
                text
            }
        } catch (e: PlexException) {
            throw e
        } catch (e: IOException) {
            throw PlexException.Unreachable(e)
        }
        try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw PlexException.Failed(200, "Unexpected answer from plex.tv: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw PlexException.Failed(200, "Unexpected answer from plex.tv: ${e.message}")
        }
    }

    private fun PinJson.toPin(): PlexPin {
        val expires = expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: (clock() + (expiresIn ?: DEFAULT_LIFETIME_SECONDS) * 1_000L)
        return PlexPin(id, code, expires, authToken?.takeIf { it.isNotBlank() })
    }

    @Serializable
    private class PinJson(
        val id: Long,
        val code: String,
        val expiresIn: Long? = null,
        val expiresAt: String? = null,
        val authToken: String? = null,
    )

    @Serializable
    private class UserJson(val id: Long, val uuid: String, val username: String, val title: String? = null)

    @Serializable
    private class ResourceJson(
        val name: String,
        val clientIdentifier: String,
        val provides: String = "",
        val accessToken: String? = null,
        val owned: Boolean = false,
        val connections: List<ConnectionJson> = emptyList(),
    )

    @Serializable
    private class ConnectionJson(
        val protocol: String = "http",
        val address: String,
        val port: Int,
        val uri: String,
        val local: Boolean = false,
        val relay: Boolean = false,
        @SerialName("IPv6") val ipv6: Boolean = false,
    )

    private companion object {
        /** plex.tv gave 900 s on 10/26/2026; used only if a response leaves both expiry fields out. */
        const val DEFAULT_LIFETIME_SECONDS = 900L
        val json = Json { ignoreUnknownKeys = true }
    }
}
