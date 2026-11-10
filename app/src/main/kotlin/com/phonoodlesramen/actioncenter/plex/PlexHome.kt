package com.phonoodlesramen.actioncenter.plex

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A user of your Plex Home (Phase 5, K2). [protected] means it has a PIN. */
data class PlexHomeUser(
    val id: Long,
    val uuid: String,
    val title: String,
    val admin: Boolean,
    val restricted: Boolean,
    /** "little_kid", "older_kid", "teen", or null when not restricted. */
    val restrictionProfile: String?,
    val protected: Boolean,
)

/**
 * Plex Home on plex.tv (research §4, community-documented): your Home's users, and a
 * user's own plex.tv token from the switch call. That token isn't a server token: the
 * server's comes from `resources` with it ([PlexAccounts.servers]). Tokens are never logged.
 */
class PlexHome(
    private val http: OkHttpClient,
    private val headers: PlexHeaders,
    private val baseUrl: HttpUrl = "https://plex.tv/".toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** How a switch went: the token, and which call gave it. */
    data class Switched(val token: String, val via: String)

    suspend fun users(ownerToken: String): List<PlexHomeUser> {
        val body = call(headers.apply(Request.Builder().url(url("api/v2/home/users")), ownerToken).build())
        val root = json.parseToJsonElement(body).jsonObject
        return root["users"]?.jsonArray.orEmpty().mapNotNull { element ->
            val user = element.jsonObject
            val id = user.long("id") ?: return@mapNotNull null
            PlexHomeUser(
                id = id,
                uuid = user.string("uuid").orEmpty(),
                title = user.string("title") ?: user.string("username").orEmpty(),
                admin = user.bool("admin"),
                restricted = user.bool("restricted"),
                restrictionProfile = user.string("restrictionProfile"),
                protected = user.bool("protected"),
            )
        }
    }

    /**
     * [user]'s plex.tv token, by plex.tv's v2 switch (by uuid), else the older one (by id),
     * with [pin] when the user has one. Throws [PlexException] when neither gives a token.
     */
    suspend fun switchTo(ownerToken: String, user: PlexHomeUser, pin: String? = null): Switched {
        val attempts = listOf(
            "v2" to url("api/v2/home/users/${user.uuid}/switch"),
            "legacy" to url("api/home/users/${user.id}/switch"),
        )
        var last: PlexException? = null
        for ((via, base) in attempts) {
            if (via == "v2" && user.uuid.isEmpty()) continue
            val target = if (pin == null) base else base.newBuilder().addQueryParameter("pin", pin).build()
            try {
                val body = call(headers.apply(Request.Builder().url(target).post(ByteArray(0).toRequestBody()), ownerToken).build())
                tokenIn(body)?.let { return Switched(it, via) }
                last = PlexException.Failed(200, "plex.tv's $via switch gave no token")
            } catch (e: PlexException) {
                last = e
            }
        }
        throw last ?: PlexException.Failed(0, "No switch to try")
    }

    private fun url(path: String) = baseUrl.resolve(path) ?: error("Bad plex.tv path: $path")

    private suspend fun call(request: Request): String = withContext(io) {
        try {
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
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val XML_TOKEN = Regex("""authenticationToken="([^"]+)"""")

        /** The token in a switch's answer: JSON (`authToken` or `authenticationToken`), or the older XML. */
        internal fun tokenIn(body: String): String? {
            val fromJson = try {
                val root = json.parseToJsonElement(body).jsonObject
                root.string("authToken") ?: root.string("authenticationToken")
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            return (fromJson ?: XML_TOKEN.find(body)?.groupValues?.get(1))?.takeIf { it.isNotBlank() }
        }

        private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
        private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false
    }
}

/**
 * Phase 5's Plex Home check (step 2), once per launch after the first Plex sync. It logs
 * under `ActionCenterPlex`, with counts and no names, ids or tokens:
 * - how many users your Home has, which are managed, restricted (and to what) or have a PIN;
 * - for one managed user without a PIN: whether the switch gives a token, which call gave
 *   it, and whether `resources` with it gives a token for your server;
 * - your movies as you and as that user (fewer means Plex's restrictions apply to them).
 */
class PlexHomeCheck(
    private val home: PlexHome,
    private val accounts: PlexAccounts,
    private val http: OkHttpClient,
    private val headers: PlexHeaders,
    private val ownerToken: suspend () -> String?,
    private val server: suspend () -> Pair<String, ServerAccess>?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit,
) {
    private val ran = AtomicBoolean(false)

    /** Never throws; runs at most once per launch. */
    suspend fun runOnce() {
        if (!ran.compareAndSet(false, true)) return
        try {
            run()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("plex home: failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun run() {
        val owner = ownerToken() ?: return log("plex home: not signed in")
        val (serverId, access) = server() ?: return log("plex home: no server linked")
        val users = home.users(owner)
        val managed = users.filter { !it.admin }
        val profiles = managed.mapNotNull { it.restrictionProfile }.groupingBy { it }.eachCount()
        log(
            "plex home: ${users.size} users: ${users.count { it.admin }} admin, ${managed.size} managed, " +
                "${managed.count { it.restricted }} restricted ${profiles.entries.joinToString(prefix = "(", postfix = ")") { "${it.key} ${it.value}" }}, " +
                "${users.count { it.protected }} with a PIN",
        )
        val user = managed.firstOrNull { !it.protected } ?: return log("plex home: no managed user without a PIN to try")
        val switched = try {
            home.switchTo(owner, user)
        } catch (e: PlexException) {
            return log("plex home: switch failed: ${e.javaClass.simpleName} ${(e as? PlexException.Failed)?.code ?: ""}")
        }
        val serverToken = accounts.servers(switched.token).firstOrNull { it.clientIdentifier == serverId }?.accessToken?.takeIf { it.isNotEmpty() }
        log("plex home: switch by ${switched.via}: a plex.tv token; resources: ${if (serverToken == null) "no server token" else "a server token"}")
        if (serverToken == null) return
        val movies = movieSection(access) ?: return log("plex home: no movie library to count")
        val asOwner = total(access.baseUri, movies, access.token)
        val asUser = total(access.baseUri, movies, serverToken)
        log("plex home: movies as you $asOwner, as a ${user.restrictionProfile ?: "unrestricted"} user $asUser")
    }

    private suspend fun movieSection(access: ServerAccess): String? {
        val body = get("${access.baseUri}/library/sections/all", access.token) ?: return null
        val dirs = json.parseToJsonElement(body).jsonObject["MediaContainer"]?.jsonObject?.get("Directory")?.jsonArray.orEmpty()
        return dirs.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "movie" }
            ?.get("key")?.jsonPrimitive?.contentOrNull
    }

    /** A section's movies for [token]: the container's total, without the titles. */
    private suspend fun total(base: String, section: String, token: String): String {
        val body = get("$base/library/sections/$section/all?type=1&X-Plex-Container-Start=0&X-Plex-Container-Size=0", token)
            ?: return "refused"
        val container = json.parseToJsonElement(body).jsonObject["MediaContainer"]?.jsonObject ?: return "unreadable"
        return (container["totalSize"] ?: container["size"])?.jsonPrimitive?.intOrNull?.toString() ?: "unknown"
    }

    private suspend fun get(url: String, token: String): String? = withContext(io) {
        http.newCall(headers.apply(Request.Builder().url(url), token).build()).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
