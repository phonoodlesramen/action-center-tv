package com.phonoodlesramen.actioncenter.station

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.phonoodlesramen.actioncenter.guide.GuideDatabase
import com.phonoodlesramen.actioncenter.guide.GuideEngine
import com.phonoodlesramen.actioncenter.guide.RoomGuideEditor
import com.phonoodlesramen.actioncenter.guide.station.Changed
import com.phonoodlesramen.actioncenter.guide.station.ChannelPlayed
import com.phonoodlesramen.actioncenter.guide.station.Favorites
import com.phonoodlesramen.actioncenter.guide.station.PairRequest
import com.phonoodlesramen.actioncenter.guide.station.SpotAnswer
import com.phonoodlesramen.actioncenter.guide.station.SpotForget
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationInfo
import com.phonoodlesramen.actioncenter.guide.station.StationLibrary
import com.phonoodlesramen.actioncenter.guide.station.StationPlex
import com.phonoodlesramen.actioncenter.nfl.EspnClient
import com.phonoodlesramen.actioncenter.plex.PlexHeaders
import com.phonoodlesramen.actioncenter.plex.PlexLibrary
import com.phonoodlesramen.actioncenter.plex.PlexServerReach
import com.phonoodlesramen.actioncenter.plex.PlexTv
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.PlexSync
import com.phonoodlesramen.actioncenter.safety.summary
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * The guide station (Phase 8): the house's Plex Guide, for every TV. One of these per
 * container; [start] opens the port and the background work, [close] stops both.
 *
 * Its volume (`STATION_DATA`):
 * - `guide.db` and `plex-cache.db`: the TVs' own schemas, through Room on the bundled SQLite;
 * - `station/` (700): the CA and server certificate, the TVs, the Plex sign-in, the lineup's
 *   server, the spot, each profile's favorites, the NFL copy and settings, and the versions,
 *   each mode 600;
 * - `nfl-logos/`: the teams' logos, at the two sizes the TVs draw;
 * - `approve/`: where a pairing code dropped as a file or folder approves that TV.
 */
class Station(val config: StationConfig, private val options: Options = Options()) : AutoCloseable {
    /** What the tests change: plex.tv's and ESPN's addresses, the clock, and how long things wait. */
    class Options(
        val plexTv: HttpUrl = "https://plex.tv/".toHttpUrl(),
        val espn: HttpUrl = EspnClient.SITE,
        val espnCdn: HttpUrl = EspnClient.CDN,
        /** Whether ESPN is polled on its schedule; the tests poll by hand. */
        val nflPolls: Boolean = true,
        val clock: () -> Long = System::currentTimeMillis,
        val log: (String) -> Unit = StationLog::line,
        val changesWaitMillis: Long = StationApi.CHANGES_WAIT_MILLIS,
        val approveEveryMillis: Long = 1_000L,
        val plexPollMillis: Long = StationPlexLink.POLL_MILLIS,
        val plexRetryMillis: Long = StationPlexLink.RETRY_MILLIS,
        /** The library syncs on start and this often (`PlexSync`'s 30 minutes), and the guide extends after it. */
        val syncEveryMillis: Long = PlexSync.STALE_AFTER_MILLIS,
        /** The guide is read again this often anyway, for anything that changes with the clock. */
        val refreshEveryMillis: Long = 5 * 60_000L,
        val random: SecureRandom = SecureRandom(),
    )

    private val log = options.log
    private val clock = options.clock
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log("station: ${e.summary()}") },
    )

    init {
        SecretFiles.ensureDir(config.dataDir, ownerOnly = false)
        SecretFiles.ensureDir(config.stateDir)
        SecretFiles.ensureDir(config.approveDir, ownerOnly = false)
    }

    internal val ca = StationCa.load(config.stateDir, config.hosts, clock(), log, options.random)
    internal val devices = DeviceStore(config.stateDir.resolve("devices.json"))
    internal val versions = Versions(config.stateDir.resolve("version"))

    internal val guideDatabase: GuideDatabase = Room.databaseBuilder<GuideDatabase>(name = config.dataDir.resolve(GuideDatabase.NAME).toString())
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()

    internal val plexDatabase: PlexDatabase = Room.databaseBuilder<PlexDatabase>(name = config.dataDir.resolve(PlexDatabase.NAME).toString())
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // 5 to 6 migrates (Phase 9); a version with no migration syncs from scratch, as on the TVs.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    internal val plexStore = FilePlexLinkStore(config.stateDir.resolve("plex.json"))

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val plexHeaders = PlexHeaders(clientId = { plexStore.clientId() }, version = StationApi.VERSION, device = STATION_PLEX_DEVICE)
    private val plexTv = PlexTv(http, plexHeaders, options.plexTv)
    private val plexReach = PlexServerReach(http, plexHeaders)

    private val plexSync = PlexSync(
        plexStore, plexTv, plexReach, PlexLibrary(http, plexHeaders), plexDatabase.dao(), clock,
        log = log,
    )

    internal val engine = GuideEngine(
        guideDatabase, plexDatabase, clock = clock, zone = { config.zone }, io = Dispatchers.IO, log = log,
        linkedServer = { plexStore.link.first().server?.clientIdentifier },
        lineupServer = FileLineupServer(config.stateDir.resolve("lineup.json")),
        horizonMillis = StationApi.HORIZON_MILLIS,
    )

    internal val guide = GuideService(
        guideDatabase, plexDatabase, RoomGuideEditor(guideDatabase, plexDatabase, engine, log = log), versions, clock, config.zone,
    )

    internal val spot = HouseSpot(config.stateDir.resolve("spot.json"), versions, guide::slot, clock)

    internal val favorites = FavoritesStore(config.stateDir.resolve("favorites.json"), versions)

    /** The NFL's own version: a score moving doesn't send every TV back for the guide. */
    internal val nflVersions = Versions(config.stateDir.resolve("nfl-version"))

    internal val nfl = NflService(
        EspnClient(http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build(), options.espn, options.espnCdn),
        config.stateDir, config.dataDir.resolve("nfl-logos"), nflVersions, clock, config.zone, log,
    )

    internal val pairing = Pairing(ca, devices, config.approveDir, clock, log, options.random)

    internal val plexLink = StationPlexLink(
        plexStore, plexTv, plexReach, clearCache = { plexSync.clear() }, syncNow = ::syncAndExtend, scope = scope, clock = clock, log = log,
        pollMillis = options.plexPollMillis, retryMillis = options.plexRetryMillis,
    )

    internal val routes: List<Route> = routes()

    private val server = StationServer(config.port, ca, StationHandler(routes, ca, devices, clock, log))

    /** The port it listens on: [StationConfig.port], or the one picked for port 0 (tests). */
    val port: Int get() = server.port

    fun start(): Station {
        runBlocking { guide.refresh() }
        server.start()
        every(options.approveEveryMillis) { pairing.scanApprovals() }
        every(options.syncEveryMillis) { syncAndExtend() }
        every(options.refreshEveryMillis, initialDelay = true) { guide.refresh() }
        every(5 * 60_000L, initialDelay = true) { devices.flush() }
        if (options.nflPolls) scope.launch { nfl.run() }
        val link = plexStore.current
        log(
            "Guide station ${StationApi.VERSION} on port $port, time zone ${config.zone.id}; ${devices.all().size} TVs paired; " +
                if (link.linked) "Plex: ${link.server?.name}" else "Plex not linked yet",
        )
        if (config.hosts.isEmpty()) log("STATION_HOSTS is empty: the certificate names only localhost, so the TVs won't accept it. Set it to the NAS's address.")
        return this
    }

    override fun close() {
        versions.close()
        nflVersions.close()
        server.close()
        scope.cancel()
        devices.flush()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        guideDatabase.close()
        plexDatabase.close()
    }

    /** Syncs the library, then extends the guide and reads it again. Never throws. */
    private suspend fun syncAndExtend() {
        try {
            plexSync.sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("plex sync: ${e.summary()}")
        }
        engine.extend()
        guide.refresh()
    }

    /** Runs [block] now (or after one wait) and then every [millis]; a failure is logged and the next run goes ahead. */
    private fun every(millis: Long, initialDelay: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            if (initialDelay) delay(millis)
            while (isActive) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("station: ${e.summary()}")
                }
                delay(millis)
            }
        }
    }

    private suspend fun info(): StationInfo {
        val link = plexStore.current
        val sync = plexDatabase.dao().syncState()
        val error = plexLink.problem ?: sync?.error?.let { if (it == PlexSync.OFFLINE) "Can't reach the Plex server." else it }
        return StationInfo(
            version = StationApi.VERSION,
            plex = StationPlex(linked = link.linked, serverName = link.server?.name),
            library = StationLibrary(lastSyncMillis = sync?.lastSuccessAt, error = error),
            timeZone = config.zone.id,
        )
    }

    /** The API (`docs/design/guide-station-api.md`). Only the first two are open; [StationHandler] guards the rest. */
    private fun routes(): List<Route> = listOf(
        Route("POST", "/v1/pair", open = true) { Reply.ok(pairing.start(it.body<PairRequest>())) },
        Route("GET", "/v1/pair/{id}", open = true) { Reply.ok(pairing.status(it.param("id"))) },

        Route("GET", "/v1/guide") { call ->
            val guide = guide.guide() ?: throw ApiException(503, "The guide station is still starting. Try again in a moment.")
            val tag = mapOf("ETag" to guide.version.toString())
            if (matches(call.header("If-None-Match"), guide.version)) Reply.Empty(304, tag) else Reply.ok(guide, tag)
        },
        Route("GET", "/v1/changes") { call ->
            val after = call.query("after")?.toLongOrNull()
            val version = if (after == null) versions.current else versions.awaitChange(after, options.changesWaitMillis)
            if (version == null) Reply.Empty(204) else Reply.ok(Changed(version))
        },

        Route("GET", "/v1/editor/choices") { Reply.ok(guide.choices()) },
        Route("POST", "/v1/channels") { Reply.ok(guide.create(it.body())) },
        Route("PUT", "/v1/channels/{id}") { Reply.ok(guide.update(it.param("id"), it.body())) },
        Route("PATCH", "/v1/channels/{id}") { Reply.ok(guide.patch(it.param("id"), it.body())) },
        Route("POST", "/v1/channels/{id}/duplicate") { Reply.ok(guide.duplicate(it.param("id"))) },
        Route("DELETE", "/v1/channels/{id}") { Reply.ok(guide.delete(it.param("id"))) },
        Route("POST", "/v1/preview") { Reply.ok(guide.preview(it.body())) },
        Route("POST", "/v1/lineup/remake") { Reply.ok(guide.remake()) },

        Route("GET", "/v1/spot") { Reply.ok(SpotAnswer(spot.current())) },
        Route("PUT", "/v1/spot") { Reply.ok(Changed(spot.put(it.body(), it.tv))) },
        Route("DELETE", "/v1/spot") { Reply.ok(Changed(spot.forget(it.optionalBody<SpotForget>()?.only))) },
        Route("POST", "/v1/spot/played") { Reply.ok(Changed(spot.played(it.body<ChannelPlayed>().channelId, it.tv))) },

        Route("GET", "/v1/favorites/{profileKey}") { Reply.ok(favorites.get(it.param("profileKey"))) },
        Route("PUT", "/v1/favorites/{profileKey}") { Reply.ok(favorites.put(it.param("profileKey"), it.body<Favorites>().channels)) },

        nflRoute("GET", "/v1/nfl") { call ->
            val state = nfl.state() ?: throw ApiException(503, nfl.problem ?: NflService.NOT_YET)
            val tag = mapOf("ETag" to state.version.toString())
            if (matches(call.header("If-None-Match"), state.version)) Reply.Empty(304, tag) else Reply.ok(state, tag)
        },
        nflRoute("GET", "/v1/nfl/changes") { call ->
            val after = call.query("after")?.toLongOrNull()
            val version = if (after == null) nflVersions.current else nflVersions.awaitChange(after, options.changesWaitMillis)
            if (version == null) Reply.Empty(204) else Reply.ok(Changed(version))
        },
        nflRoute("GET", "/v1/nfl/week/{type}/{week}") { call ->
            val type = call.param("type").toIntOrNull() ?: 0
            val week = call.param("week").toIntOrNull() ?: 0
            Reply.ok(nfl.week(type, week, call.query("season")?.toIntOrNull()))
        },
        nflRoute("GET", "/v1/nfl/teams/{abbr}/schedule") { call ->
            Reply.ok(nfl.schedule(call.param("abbr"), call.query("season")?.toIntOrNull(), call.query("seasonType")?.toIntOrNull()))
        },
        nflRoute("GET", "/v1/nfl/games/{id}") { Reply.ok(nfl.game(it.param("id"))) },
        nflRoute("GET", "/v1/nfl/logos/{abbr}") { call ->
            Reply.Bytes(200, nfl.logo(call.param("abbr"), call.query("size") ?: "s"), "image/png", mapOf("Cache-Control" to "max-age=$LOGO_MAX_AGE"))
        },
        nflRoute("GET", "/v1/nfl/settings") { Reply.ok(nfl.settings()) },
        nflRoute("PUT", "/v1/nfl/settings") { Reply.ok(Changed(nfl.putSettings(it.body()))) },

        Route("GET", "/v1/devices") { Reply.ok(devices.all().sortedBy { it.pairedAtMillis }.map { it.toApi() } + pairing.pending()) },
        Route("POST", "/v1/devices/{id}/approve") { call ->
            if (!pairing.approveDevice(call.param("id"))) throw ApiException(404, "No TV is waiting to pair with that id. Codes last 5 minutes.")
            Reply.done
        },
        Route("DELETE", "/v1/devices/{id}") { call ->
            val id = call.param("id")
            // A TV still waiting is refused; a paired one (the caller itself, too) is unpaired.
            if (!pairing.refuseDevice(id)) {
                val gone = devices.revoke(id) ?: throw ApiException(404, "That TV isn't paired with the guide station.")
                log("Unpaired \"${gone.name.replace('"', '\'')}\" ($id), from \"${call.tv.name.replace('"', '\'')}\"")
            }
            Reply.done
        },
        Route("GET", "/v1/station") { Reply.ok(info()) },
        Route("POST", "/v1/plex/link") { Reply.ok(plexLink.start()) },
        Route("GET", "/v1/plex/link") { Reply.ok(plexLink.status()) },
    )

    /** A `/v1/nfl…` call: each one tells the poller a TV is looking (proposal F). */
    private fun nflRoute(method: String, pattern: String, handle: suspend (Call) -> Reply) = Route(method, pattern) { call ->
        nfl.asked()
        handle(call)
    }

    private companion object {
        /** A week: a logo changes about never, and the station fetches it again monthly anyway. */
        const val LOGO_MAX_AGE = 7 * 24 * 60 * 60

        /** `If-None-Match`: the version as sent back, quoted or not, weak or not, or `*`. */
        fun matches(ifNoneMatch: String?, version: Long): Boolean = ifNoneMatch.orEmpty().split(',').any { tag ->
            val bare = tag.trim().removePrefix("W/").trim('"')
            bare == "*" || bare == version.toString()
        }
    }
}
