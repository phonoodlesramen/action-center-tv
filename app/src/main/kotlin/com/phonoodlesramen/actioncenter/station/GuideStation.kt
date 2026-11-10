package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.ChannelEntity
import com.phonoodlesramen.actioncenter.guide.EditRejected
import com.phonoodlesramen.actioncenter.guide.GuideDatabase
import com.phonoodlesramen.actioncenter.guide.GuideEngine
import com.phonoodlesramen.actioncenter.guide.LineupServer
import com.phonoodlesramen.actioncenter.guide.SlotEntity
import com.phonoodlesramen.actioncenter.guide.station.ChannelPlayed
import com.phonoodlesramen.actioncenter.guide.station.Favorites
import com.phonoodlesramen.actioncenter.guide.station.PairRequest
import com.phonoodlesramen.actioncenter.guide.station.PairState
import com.phonoodlesramen.actioncenter.guide.station.PlexLinkState
import com.phonoodlesramen.actioncenter.guide.station.SpotBody
import com.phonoodlesramen.actioncenter.guide.station.SpotForget
import com.phonoodlesramen.actioncenter.guide.station.SpotKey
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationDevice
import com.phonoodlesramen.actioncenter.guide.station.StationGuide
import com.phonoodlesramen.actioncenter.guide.station.StationInfo
import com.phonoodlesramen.actioncenter.guide.station.StationSlot
import com.phonoodlesramen.actioncenter.guide.station.StationSpot
import com.phonoodlesramen.actioncenter.nfl.NflGameDetail
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.NflState
import com.phonoodlesramen.actioncenter.nfl.NflTeamSchedule
import com.phonoodlesramen.actioncenter.nfl.NflWeek
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.safety.summary
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * This TV and the guide station on the NAS (Phase 8). Unpaired, nothing here runs and the
 * TV keeps its own guide. Paired, the station owns the guide and this keeps the TV's copy:
 *
 * - **Pairing** ([pair]): this TV's Keystore key ([StationKeys]) goes to the station, and
 *   the code it shows is worked out from the station's CA and that key
 *   ([StationApi.pairingCode]). Approved, the TV keeps the certificate the station issued
 *   and trusts only the station's CA from then on.
 * - **The copy** ([refresh]): `GET /v1/guide` (ETag) into `guide.db` ([GuideDatabase]) and
 *   what it can't hold into [GuideCopy], so [StationGuideSource] serves the card, the Live
 *   row, the full guide and the player from it, offline too (N5). [keepUp] holds a
 *   `changes` call open while the home is in front, and copies again on each change, every
 *   5 minutes and on wake; the guide worker and each Plex sync copy too.
 * - **Edits** ([edit], [StationGuideEditor]) go to the station; there's no offline queue.
 * - **The house's spot** ([houseSpot], [StationSpots]).
 * - **Favorite channels** (Phase 13, [favorites], [saveFavorites]): each profile's, by its key
 *   ([com.phonoodlesramen.actioncenter.guide.station.ProfileKeys]). [changes] is the `changes`
 *   version heard, which [com.phonoodlesramen.actioncenter.favorites.ChannelFavorites] follows.
 * - **The NFL** (Phase 13, [NflStation]): the house's copy, its own `nfl/changes`, what's asked
 *   for on demand, logos and the house's settings, for [com.phonoodlesramen.actioncenter.nfl.NflSource].
 * - **Leaving** ([leave]): the station is told, and the copy in `guide.db` becomes this
 *   TV's own lineup again: the engine schedules on from where it ends.
 *
 * Nothing here throws to its callers but [edit] (as [EditRejected]): a failure is a
 * [StationReach] and a sentence on the page.
 *
 * @param keys made only when pairing or paired, so an unpaired TV never touches the Keystore.
 * @param linkedServer the Plex server this TV is linked to, so leaving keeps the lineup (G19).
 */
class GuideStation(
    private val store: StationStore,
    keys: () -> StationKeys,
    private val http: OkHttpClient,
    private val guideDb: GuideDatabase,
    private val lineupServer: LineupServer,
    private val linkedServer: suspend () -> String?,
    /** This TV's model, as the station lists it. */
    private val model: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timing: Timing = Timing(),
    /** One line per change (logcat, [LOG_TAG]). Never a title, a key or a certificate. */
    private val log: (String) -> Unit = {},
) : GuideStationController, FavoritesStation, NflStation {
    /** How often things happen; tests shorten them. */
    data class Timing(
        /** Between checks on a pairing waiting for approval. */
        val pollMillis: Long = 2_000,
        /** The copy is checked at least this often while the home is in front. */
        val refreshEveryMillis: Long = 5 * 60_000L,
        /** After a failure, the first wait before trying again; it doubles up to [retryMaxMillis]. */
        val retryMillis: Long = 15_000,
        val retryMaxMillis: Long = 5 * 60_000L,
        /** How long the station holds a `changes` call. */
        val changesWaitMillis: Long = StationApi.CHANGES_WAIT_MILLIS,
        /** While Settings › Guide station is open: how often it reads the station and its devices. */
        val followMillis: Long = 5_000,
    )

    private val keys by lazy(keys)

    /** What happened since the app started; [StationSaved] is what's kept. */
    private data class Runtime(
        val reach: StationReach = StationReach.CONNECTED,
        val starting: Boolean = false,
        val problem: String? = null,
        /** A pairing that ended without approval, shown until it's put away. */
        val ended: StationPhase.Pairing? = null,
        val info: StationInfo? = null,
        val devices: List<StationDevice>? = null,
        val plexCode: String? = null,
        val leaving: Boolean = false,
        /** The last favorites call was answered 404: a 1.1 station (Phase 13). */
        val favoritesNotKept: Boolean = false,
    )

    private val runtime = MutableStateFlow(Runtime())
    private val refreshing = Mutex()
    private var pairingJob: Job? = null

    /** [known], for favorites to follow. */
    private val heard = MutableStateFlow(0L)

    /** The newest version this TV has heard of: `changes` waits for one after it. */
    private var known: Long
        get() = heard.value
        set(value) {
            heard.value = value
        }

    /** The station's client for [StationSaved]'s address and certificates, kept while they stay the same. */
    @Volatile
    private var cached: Pair<ClientKey, StationClient>? = null

    private data class ClientKey(val host: String, val port: Int, val certificate: String?, val ca: String?)

    /** Whether this TV's guide is the station's. */
    override val paired: Flow<Boolean> = store.saved.map { it.paired }.distinctUntilChanged()

    override val changes: StateFlow<Long> = heard.asStateFlow()

    /** The copy's extras for [StationGuideSource]; its check time left out, so a `304` doesn't re-read the card. */
    val guideCopy: Flow<GuideCopy?> = store.saved.map { it.guide?.copy(checkedAtMillis = 0) }.distinctUntilChanged()

    /** The house's spot (N6), for [StationSpots]: one saved on another TV names its room. */
    val houseSpot: Flow<SavedSpot?> = store.saved.map { saved -> saved.spot.spot?.let { spotOf(it, saved) } }.distinctUntilChanged()

    override val view: StateFlow<StationView> = combine(store.saved, runtime, ::viewOf).stateIn(scope, SharingStarted.Eagerly, StationView())

    init {
        // A pairing that was waiting when the app stopped carries on, while its code holds.
        scope.launch {
            val saved = store.saved.first()
            if (saved.pairing != null) pairingJob = scope.launch { awaitApproval() }
        }
    }

    suspend fun isPaired(): Boolean = store.saved.first().paired

    // Pairing.

    override fun pair(address: String, room: String) {
        val (host, port) = parseAddress(address) ?: return problem("Type the NAS's address, like 192.168.1.20.")
        val name = room.trim().takeIf { it.isNotEmpty() } ?: return problem("Pick the room this TV is in.")
        pairingJob?.cancel()
        pairingJob = scope.launch { startPairing(host, port, name) }
    }

    override fun cancelPairing() {
        pairingJob?.cancel()
        runtime.update { it.copy(ended = null, starting = false) }
        scope.launch { store.update { it.copy(pairing = null) } }
    }

    private fun problem(text: String) = runtime.update { it.copy(problem = text) }

    private suspend fun startPairing(host: String, port: Int, room: String) {
        runtime.update { it.copy(starting = true, problem = null, ended = null) }
        try {
            store.update { it.copy(host = host, port = port, room = room, pairing = null) }
            val spki = withContext(io) { keys.publicKey().encoded }
            val (started, chain) = pairingClient(host, port).pair(PairRequest(room, model, BASE64.encodeToString(spki)))
            val ca = StationTls.caOf(chain) ?: throw StationException.Incompatible("The guide station didn't show its certificate.")
            val code = StationApi.pairingCode(ca.encoded, spki)
            store.update { it.copy(pairing = SavedPairing(started.requestId, started.expiresAtMillis, code, BASE64.encodeToString(ca.encoded))) }
            log("station: pairing, waiting for approval")
            runtime.update { it.copy(starting = false) }
            awaitApproval()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StationException.Unreachable) {
            runtime.update { it.copy(problem = "Can't reach a guide station at ${StationSaved(host = host, port = port).address}. Check the address, and that the station is running.") }
        } catch (e: StationException) {
            runtime.update { it.copy(problem = e.message) }
        } catch (e: Exception) {
            log("station: pairing didn't start: ${e.summary()}")
            runtime.update { it.copy(problem = "Pairing didn't start. Try again.") }
        } finally {
            runtime.update { it.copy(starting = false) }
        }
    }

    /** Asks the station about the waiting pairing until it's approved, refused or expired. */
    private suspend fun awaitApproval() {
        while (true) {
            delay(timing.pollMillis)
            val saved = store.saved.first()
            val pairing = saved.pairing ?: return
            val host = saved.host ?: return
            val (status, chain) = try {
                pairingClient(host, saved.port).pairStatus(pairing.requestId)
            } catch (e: StationException.Unreachable) {
                // The station may restart; the code still holds until it expires.
                if (clock() > pairing.expiresAtMillis + EXPIRY_GRACE_MILLIS) return ended(pairing, PairState.EXPIRED)
                continue
            } catch (e: StationException.Refused) {
                // Pending requests live in the station's memory: one it doesn't know has gone.
                return ended(pairing, PairState.EXPIRED)
            } catch (e: StationException) {
                return ended(pairing, PairState.EXPIRED, e.message)
            }
            // The station it's talking to must still be the one whose code is showing.
            val ca = StationTls.caOf(chain)
            if (ca == null || !ca.encoded.contentEquals(BASE64_DECODER.decode(pairing.caDer))) {
                return ended(pairing, PairState.REFUSED, CHANGED)
            }
            when (status.state) {
                PairState.PENDING -> continue
                PairState.APPROVED -> return approved(pairing, status.certificate, status.caCertificate)
                PairState.REFUSED, PairState.EXPIRED -> return ended(pairing, status.state)
            }
        }
    }

    private suspend fun ended(pairing: SavedPairing, state: PairState, problem: String? = null) {
        store.update { it.copy(pairing = null) }
        runtime.update { it.copy(ended = StationPhase.Pairing(pairing.code, pairing.expiresAtMillis, state), problem = problem) }
        log("station: pairing ${state.name.lowercase()}")
    }

    /**
     * Approved: the certificate must be for this TV's key and signed by the very CA whose
     * code was compared, or nothing is kept. Then the guide is the station's.
     */
    private suspend fun approved(pairing: SavedPairing, certificate: String?, caCertificate: String?) {
        val accepted = try {
            val chain = StationTls.certificates(certificate ?: "")
            val ca = StationTls.certificates(caCertificate ?: "").single()
            val leaf = chain.first()
            check(ca.encoded.contentEquals(BASE64_DECODER.decode(pairing.caDer)))
            check(leaf.publicKey.encoded.contentEquals(withContext(io) { keys.publicKey().encoded }))
            leaf.verify(ca.publicKey)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!accepted) return ended(pairing, PairState.REFUSED, CHANGED)
        store.update {
            it.copy(pairing = null, certificate = certificate, caCertificate = caCertificate, unpaired = false, deviceId = null, spot = SpotCopy())
        }
        runtime.update { it.copy(ended = null, problem = null, reach = StationReach.CONNECTED, info = null, devices = null) }
        log("station: paired")
        refresh()
    }

    // The copy.

    /**
     * Copies the station's guide and spot, if it's changed (ETag), and sends a change to the
     * spot it hasn't heard. Returns whether the station answered. Never throws.
     */
    suspend fun refresh(): Boolean = try {
        refreshing.withLock { refreshOnce() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("station: not copied: ${e.summary()}")
        false
    }

    private suspend fun refreshOnce(): Boolean {
        val saved = store.saved.first()
        if (!saved.paired || saved.unpaired) return false
        return try {
            val client = client(saved)
            sendSpot(client)
            val answer = client.guide(saved.guide?.etag)
            if (answer == null) {
                store.update { it.copy(guide = it.guide?.copy(checkedAtMillis = clock())) }
            } else {
                copyGuide(client, answer.first, answer.second, saved)
            }
            saved.guide?.version?.let { version -> known = maxOf(known, version) }
            val spot = client.spot().spot
            // A change made here meanwhile wins: it's sent first next time.
            store.update { if (it.spot.unsent == null) it.copy(spot = it.spot.copy(spot = spot)) else it }
            reached()
            if (saved.deviceId == null) learnDeviceId(client)
            true
        } catch (e: StationException) {
            failed(e)
            false
        }
    }

    /** The station's guide into `guide.db`, and what it can't hold into [GuideCopy]. */
    private suspend fun copyGuide(client: StationClient, guide: StationGuide, etag: String?, saved: StationSaved) {
        val now = clock()
        val windowStart = now - StationApi.BEHIND_MILLIS
        val channels = guide.channels.map { channel ->
            val rule = channel.rule.toJson()
            ChannelEntity(
                id = channel.id,
                number = channel.number,
                name = channel.name,
                rule = rule,
                // The station schedules; this matters only once the TV leaves and schedules on itself.
                seed = rule.hashCode().toLong(),
                alignmentMinutes = channel.alignmentMinutes,
                cursor = FRESH_CURSOR,
                scheduledUntil = channel.slots.maxOfOrNull { it.endMillis },
                extendedAt = guide.updatedAtMillis,
                enabled = channel.enabled,
                filler = channel.filler,
            )
        }
        val slots = guide.channels.associate { channel ->
            val cut = minOf(channel.slots.minOfOrNull { it.startMillis } ?: windowStart, windowStart)
            channel.id to (cut to channel.slots.map { it.toEntity(channel.id) })
        }
        withContext(io) { guideDb.dao().copyLineup(channels, slots, pruneBefore = now - GuideEngine.KEEP_PAST_MILLIS) }
        // With no channels, the card says why: the station's library hasn't synced, or nothing fills one.
        val synced = if (guide.channels.isNotEmpty()) {
            true
        } else {
            try {
                client.station().library.lastSyncMillis != null
            } catch (e: StationException) {
                saved.guide?.synced ?: false
            }
        }
        val extras = guide.channels.associate { channel ->
            channel.id to ChannelExtras(
                logo = channel.logo,
                contentRatings = channel.contentRatings,
                unrated = channel.unrated,
                offAir = channel.slots.mapNotNull { slot -> slot.offAir?.let { slot.startMillis to it } }.toMap(),
            )
        }
        store.update {
            it.copy(guide = GuideCopy(guide.version, etag, guide.updatedAtMillis, checkedAtMillis = now, guide.timeZone, extras, synced))
        }
        known = maxOf(known, guide.version)
        log("station: copied version ${guide.version}, ${channels.size} channels, ${guide.channels.sumOf { it.slots.size }} slots")
    }

    /**
     * While the home is in front: copies now, then holds a `changes` call open and copies
     * again on each change, and at least every 5 minutes. After a failure it tries again,
     * waiting longer each time. Stops when this TV isn't paired, or the station unpaired it.
     * Never throws but its own cancellation.
     */
    suspend fun keepUp() {
        store.saved.map { it.paired && !it.unpaired }.distinctUntilChanged().collectLatest { on ->
            if (on) followChanges()
        }
    }

    private suspend fun followChanges() {
        var retry = timing.retryMillis
        while (true) {
            if (refresh() && waitForChange()) {
                retry = timing.retryMillis
                continue
            }
            if (store.saved.first().unpaired) return
            delay(retry)
            retry = (retry * 2).coerceAtMost(timing.retryMaxMillis)
        }
    }

    /** True on a change, or when it's time to look anyway; false when the station couldn't be asked. */
    private suspend fun waitForChange(): Boolean {
        val until = clock() + timing.refreshEveryMillis
        while (clock() < until) {
            val changed = try {
                client(store.saved.first()).changes(known)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StationException) {
                failed(e)
                return false
            }
            if (changed != null) {
                known = maxOf(known, changed)
                return true
            }
        }
        return true
    }

    // Editing.

    /**
     * Runs one of the editor's calls. The station's refusal, or not reaching it, is an
     * [EditRejected] with the sentence to show.
     */
    internal suspend fun <T> edit(call: suspend StationClient.() -> T): T {
        val saved = store.saved.first()
        if (!saved.paired) throw EditRejected(StationException.UNPAIRED)
        if (saved.unpaired) throw EditRejected(StationException.UNPAIRED)
        return try {
            client(saved).call().also { reached() }
        } catch (e: StationException) {
            failed(e)
            throw EditRejected(e.message ?: StationException.CANT_REACH)
        }
    }

    /** An edit landed as [version]: the copy follows at once, not at the next `changes`. */
    internal suspend fun changed(version: Long) {
        known = maxOf(known, version)
        refresh()
    }

    // The house's spot (N6).

    internal suspend fun saveSpot(spot: SavedSpot) {
        val body = SpotBody(spot.channelId, spot.slotStartMillis, spot.itemKey, spot.positionMs, spot.savedAtMillis)
        store.update {
            val own = StationSpot(spot.channelId, spot.slotStartMillis, spot.itemKey, spot.positionMs, spot.savedAtMillis, it.deviceId ?: THIS_TV, it.room.orEmpty())
            it.copy(spot = SpotCopy(spot = own, unsent = UnsentSpot(put = body)))
        }
        sendSpotNow()
    }

    internal suspend fun forgetSpot(only: SavedSpot?) {
        val key = only?.let { SpotKey(it.channelId, it.slotStartMillis, it.itemKey) }
        store.update {
            val current = it.spot.spot
            val forget = key == null || (current != null && SpotKey(current.channelId, current.slotStartMillis, current.itemKey) == key)
            it.copy(spot = SpotCopy(spot = if (forget) null else current, unsent = UnsentSpot(forget = SpotForget(key))))
        }
        sendSpotNow()
    }

    /** A tune-in here: a spot this TV saved, on another channel, is forgotten; another TV's stays. */
    internal suspend fun channelPlayed(channelId: String) {
        var tell = false
        store.update { saved ->
            val current = saved.spot.spot
            if (current != null && current.channelId != channelId && isOwn(current.device, saved)) {
                tell = true
                saved.copy(spot = SpotCopy(spot = null, unsent = UnsentSpot(played = ChannelPlayed(channelId))))
            } else {
                saved
            }
        }
        if (tell) sendSpotNow()
    }

    private suspend fun sendSpotNow() {
        val saved = store.saved.first()
        if (!saved.paired || saved.unpaired) return
        try {
            sendSpot(client(saved))
            reached()
        } catch (e: StationException) {
            failed(e)
        }
    }

    /** The change to the spot the station hasn't heard, if any; it's kept until it's sent. */
    private suspend fun sendSpot(client: StationClient) {
        val unsent = store.saved.first().spot.unsent ?: return
        val version = when {
            unsent.put != null -> client.saveSpot(unsent.put)
            unsent.forget != null -> client.forgetSpot(unsent.forget)
            unsent.played != null -> client.played(unsent.played)
            else -> null
        }
        store.update { if (it.spot.unsent == unsent) it.copy(spot = it.spot.copy(unsent = null)) else it }
        version?.let { known = maxOf(known, it.version) }
    }

    // Each profile's favorite channels (Phase 13, C1).

    override suspend fun favorites(key: String): StationFavorites = favoritesCall { favorites(key) }

    override suspend fun saveFavorites(key: String, channels: List<String>): StationFavorites =
        favoritesCall { Favorites(channels, saveFavorites(key, channels)) }

    /**
     * One favorites call. A `404` is a 1.1 station, which keeps none yet: Settings › Plex Guide ›
     * Guide station says it needs an update. Not reaching it, or a refusal, is the sentence editing
     * shows (N5).
     */
    private suspend fun favoritesCall(call: suspend StationClient.() -> Favorites): StationFavorites {
        val saved = store.saved.first()
        if (!saved.paired || saved.unpaired) return StationFavorites.Refused(StationException.UNPAIRED)
        return try {
            val favorites = client(saved).call()
            reached()
            runtime.update { it.copy(favoritesNotKept = false) }
            StationFavorites.Kept(favorites)
        } catch (e: StationException) {
            failed(e)
            if (e is StationException.Refused && e.status == NOT_FOUND) {
                if (!runtime.value.favoritesNotKept) log("station: it keeps no favorites (an older station)")
                runtime.update { it.copy(favoritesNotKept = true) }
                StationFavorites.NotKept
            } else {
                StationFavorites.Refused(e.message ?: StationException.CANT_REACH)
            }
        }
    }

    // The NFL (Phase 13, F11): the house's copy and what's asked for on demand.

    override suspend fun nfl(etag: String?): NflReply<Pair<NflState, String?>?> = nflCall(OUTDATED_ON_404) { nfl(etag) }

    override suspend fun nflChanges(after: Long): NflReply<Long?> = nflCall(OUTDATED_ON_404) { nflChanges(after) }

    override suspend fun nflWeek(seasonType: Int, week: Int, seasonYear: Int?): NflReply<NflWeek> = nflCall { nflWeek(seasonType, week, seasonYear) }

    override suspend fun nflSchedule(abbr: String, seasonYear: Int?): NflReply<NflTeamSchedule> = nflCall { nflSchedule(abbr, seasonYear) }

    override suspend fun nflGame(id: String): NflReply<NflGameDetail> = nflCall { nflGame(id) }

    override suspend fun nflLogo(abbr: String, size: String): NflReply<ByteArray> = nflCall { nflLogo(abbr, size) }

    override suspend fun saveNflSettings(settings: NflSettings): NflReply<Long> = nflCall(OUTDATED_ON_404) { saveNflSettings(settings) }

    /**
     * One NFL call. A `404` where a 1.2 station always answers ([outdatedOn404]) is a 1.1 station:
     * Settings › NFL says it needs an update. Any answer, a refusal too, means the station is
     * there: a 503 while it waits on ESPN mustn't grey editing out.
     */
    private suspend fun <T> nflCall(outdatedOn404: Boolean = false, call: suspend StationClient.() -> T): NflReply<T> {
        val saved = store.saved.first()
        if (!saved.paired || saved.unpaired) return NflReply.Failed(StationException.UNPAIRED, reached = false)
        return try {
            NflReply.Answered(client(saved).call()).also { reached() }
        } catch (e: StationException) {
            if (e is StationException.Refused) reached() else failed(e)
            when {
                e is StationException.Refused && e.status == NOT_FOUND && outdatedOn404 -> NflReply.Outdated
                e is StationException.Refused -> NflReply.Failed(e.message ?: StationException.CANT_REACH)
                else -> NflReply.Failed(e.message ?: StationException.CANT_REACH, reached = false)
            }
        }
    }

    private fun spotOf(spot: StationSpot, saved: StationSaved) = SavedSpot(
        channelId = spot.channelId,
        slotStartMillis = spot.slotStartMillis,
        itemKey = spot.itemKey,
        positionMs = spot.positionMs,
        savedAtMillis = spot.savedAtMillis,
        room = if (isOwn(spot.device, saved)) null else spot.room.takeIf { it.isNotBlank() },
    )

    // Devices, the station's Plex, and leaving.

    override suspend fun follow() {
        while (true) {
            val saved = store.saved.first()
            if (saved.paired && !saved.unpaired) readStation(saved)
            delay(if (runtime.value.plexCode != null) timing.pollMillis else timing.followMillis)
        }
    }

    private suspend fun readStation(saved: StationSaved) {
        try {
            val client = client(saved)
            // The code this read checks. linkPlex can show a new one while the read is out, so the
            // read only ever clears the code it checked (a stale read once blanked a fresh code).
            val checked = runtime.value.plexCode
            var done = false
            var problem: String? = null
            if (checked != null) {
                when (client.plexLink().state) {
                    PlexLinkState.WAITING -> Unit
                    PlexLinkState.LINKED -> done = true
                    PlexLinkState.EXPIRED -> {
                        done = true
                        problem = "The code expired before the station was signed in. Get a new one."
                    }
                }
            }
            val info = client.station()
            val devices = client.devices()
            runtime.update {
                val same = it.plexCode == checked
                it.copy(
                    info = info,
                    devices = devices,
                    plexCode = if (done && same) null else it.plexCode,
                    problem = if (same) problem ?: it.problem else it.problem,
                )
            }
            reached()
        } catch (e: StationException) {
            failed(e)
        }
    }

    override fun linkPlex() {
        scope.launch {
            act {
                val started = it.startPlexLink()
                runtime.update { r -> r.copy(plexCode = started.code, problem = null) }
            }
        }
    }

    override fun approve(device: StationDevice) {
        scope.launch {
            act {
                it.approve(device.id)
                log("station: a TV approved")
                runtime.update { r -> r.copy(devices = it.devices(), problem = null) }
            }
        }
    }

    override fun unpair(device: StationDevice) {
        scope.launch {
            val saved = store.saved.first()
            if (isOwn(device.id, saved)) return@launch leaving()
            act {
                it.unpair(device.id)
                log("station: a TV unpaired")
                runtime.update { r -> r.copy(devices = it.devices(), problem = null) }
            }
        }
    }

    /** One thing asked of the station from the page: a refusal is a sentence there. */
    private suspend fun act(block: suspend (StationClient) -> Unit) {
        val saved = store.saved.first()
        if (!saved.paired) return
        try {
            block(client(saved))
            reached()
        } catch (e: StationException) {
            failed(e)
            runtime.update { it.copy(problem = e.message) }
        }
    }

    override fun leave() {
        scope.launch { leaving() }
    }

    /**
     * Unpairs this TV on the station (if it can be reached) and goes back to its own guide.
     * The copy in `guide.db` is already a lineup as this TV keeps one, each channel scheduled
     * to where the copy ends, so the engine carries on from there with no remake: the lineup
     * is marked as the linked server's, with its Family channel had (G19, K8).
     */
    private suspend fun leaving() {
        pairingJob?.cancel()
        runtime.update { it.copy(leaving = true) }
        val saved = store.saved.first()
        var told = !saved.paired || saved.unpaired
        if (!told) {
            told = try {
                val client = client(saved)
                val id = saved.deviceId ?: learnDeviceId(client)
                if (id != null) client.unpair(id)
                id != null
            } catch (e: StationException) {
                false
            }
        }
        try {
            linkedServer()?.let { lineupServer.set(it) }
            lineupServer.setFamilyAdded(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("station: lineup's server not kept: ${e.summary()}")
        }
        try {
            withContext(io) { keys.delete() }
        } catch (e: Exception) {
            log("station: key not deleted: ${e.summary()}")
        }
        cached = null
        // Last: from here the guide is this TV's own again.
        store.update { StationSaved(host = it.host, port = it.port, room = it.room) }
        known = 0
        runtime.value = Runtime(problem = if (told) null else NOT_TOLD)
        log("station: left")
    }

    /** This TV's id on the station: by its certificate's serial, or else the paired TV with its room and model. */
    private suspend fun learnDeviceId(client: StationClient): String? {
        val saved = store.saved.first()
        val devices = try {
            client.devices()
        } catch (e: StationException) {
            return null
        }
        val serials = serialIds(saved.certificate)
        val id = devices.firstOrNull { it.id in serials }?.id
            ?: devices.filter { !it.pending && it.name == saved.room && it.model == model }.maxByOrNull { it.pairedAtMillis ?: 0 }?.id
            ?: return null
        store.update { it.copy(deviceId = id) }
        runtime.update { it.copy(devices = devices) }
        return id
    }

    private fun isOwn(device: String, saved: StationSaved): Boolean =
        device == THIS_TV || device == saved.deviceId || (saved.deviceId == null && device in serialIds(saved.certificate))

    // How.

    private fun reached() = runtime.update { if (it.reach == StationReach.CONNECTED) it else it.copy(reach = StationReach.CONNECTED) }

    private suspend fun failed(e: StationException) {
        when (e) {
            is StationException.Unpaired -> {
                store.update { it.copy(unpaired = true) }
                runtime.update { it.copy(reach = StationReach.UNPAIRED) }
                log("station: this TV was unpaired")
            }
            // It answered, and said no (a rejected edit): that's no reason to grey editing out.
            is StationException.Refused -> if (e.status < SERVER_ERROR) reached() else unreachable(e)
            else -> unreachable(e)
        }
    }

    private fun unreachable(e: StationException) {
        if (runtime.value.reach != StationReach.UNREACHABLE) log("station: can't reach it: ${e.summary()}")
        runtime.update { it.copy(reach = StationReach.UNREACHABLE) }
    }

    private fun pairingClient(host: String, port: Int) = StationClient(host, port, StationTls.pairingClient(http), paired = null)

    /** The paired client's open side, which it never calls: pairing has its own ([pairingClient]), to read its own chain. */
    private val pairing by lazy { StationTls.pairingClient(http) }

    private fun client(saved: StationSaved): StationClient {
        val host = saved.host ?: throw StationException.Unreachable()
        val key = ClientKey(host, saved.port, saved.certificate, saved.caCertificate)
        cached?.let { (k, client) -> if (k == key) return client }
        val paired = if (saved.certificate != null && saved.caCertificate != null) {
            try {
                StationTls.pairedClient(http, keys, StationTls.certificates(saved.certificate), StationTls.certificates(saved.caCertificate).single())
            } catch (e: Exception) {
                throw StationException.Unreachable(e)
            }
        } else {
            null
        }
        return StationClient(host, saved.port, pairing, paired, timing.changesWaitMillis).also { cached = key to it }
    }

    private fun viewOf(saved: StationSaved, rt: Runtime): StationView {
        val pairing = saved.pairing
        val phase = when {
            pairing != null -> StationPhase.Pairing(pairing.code, pairing.expiresAtMillis)
            rt.ended != null -> rt.ended
            saved.paired -> StationPhase.Paired(
                station = rt.info,
                devices = rt.devices,
                thisDevice = saved.deviceId,
                plexCode = rt.plexCode,
                leaving = rt.leaving,
            )
            else -> StationPhase.Unpaired
        }
        return StationView(
            address = saved.address,
            room = saved.room.orEmpty(),
            paired = saved.paired,
            reach = if (saved.unpaired) StationReach.UNPAIRED else rt.reach,
            checkedAtMillis = saved.guide?.checkedAtMillis,
            starting = rt.starting,
            problem = rt.problem,
            phase = phase,
            favoritesNeedUpdate = saved.paired && rt.favoritesNotKept,
        )
    }

    companion object {
        const val LOG_TAG = "ActionCenterStation"

        /** A pairing the station can't be asked about is given this long past its expiry. */
        private const val EXPIRY_GRACE_MILLIS = 30_000L

        /** A refusal at or above this is the station failing, not saying no. */
        private const val SERVER_ERROR = 500

        /** What a 1.1 station answers a call it doesn't have. */
        private const val NOT_FOUND = 404

        /** For the NFL calls a 1.2 station always has: a `404` is a 1.1 station. */
        private const val OUTDATED_ON_404 = true

        /** A spot saved here before this TV knows its id on the station. */
        private const val THIS_TV = ""

        /** A channel's cursor with nothing drawn yet ([ChannelEntity.cursor]). */
        private const val FRESH_CURSOR = "{}"

        private const val CHANGED = "The station's certificate isn't the one whose code was showing. Try pairing again."
        private const val NOT_TOLD =
            "This TV left the guide station, but couldn't tell it. Unpair it in Devices on another TV."

        private val BASE64 = Base64.getEncoder()
        private val BASE64_DECODER = Base64.getDecoder()

        /** The forms a TV's id may take from its certificate's serial: hex and decimal. */
        internal fun serialIds(certificate: String?): Set<String> {
            val serial = runCatching { StationTls.certificates(certificate ?: return emptySet()).firstOrNull()?.serialNumber }.getOrNull()
                ?: return emptySet()
            return setOf(serial.toString(16), serial.toString(16).uppercase(), serial.toString())
        }

        /** "192.168.1.20" or "192.168.1.20:9443" (or an `https://` address) as a host and port; null when it isn't one. */
        fun parseAddress(text: String): Pair<String, Int>? {
            val trimmed = text.trim().removePrefix("https://").removeSuffix("/")
            if (trimmed.isEmpty()) return null
            val (host, port) = when {
                trimmed.startsWith("[") -> {
                    val end = trimmed.indexOf(']')
                    if (end < 0) return null
                    val rest = trimmed.substring(end + 1)
                    trimmed.substring(1, end) to if (rest.isEmpty()) StationApi.DEFAULT_PORT else rest.removePrefix(":").toIntOrNull()
                }
                trimmed.count { it == ':' } == 1 -> trimmed.substringBefore(':') to trimmed.substringAfter(':').toIntOrNull()
                else -> trimmed to StationApi.DEFAULT_PORT
            }
            if (port == null || port !in 1..65535 || host.isEmpty()) return null
            if (!host.contains(':') && !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
            return try {
                okhttp3.HttpUrl.Builder().scheme("https").host(host).port(port).build().host to port
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** A slot from the station as `guide.db` keeps it: filler with a key is a trailer, without one an Up next card. */
internal fun StationSlot.toEntity(channelId: String) = SlotEntity(
    channelId = channelId,
    startMillis = startMillis,
    endMillis = endMillis,
    kind = when {
        !filler -> SlotEntity.PROGRAM
        itemKey != null -> SlotEntity.TRAILER
        else -> SlotEntity.UP_NEXT
    },
    itemKey = itemKey,
    title = title,
    episode = episode,
    showKey = showKey,
    art = art,
    contentRating = contentRating,
    summary = summary,
)

