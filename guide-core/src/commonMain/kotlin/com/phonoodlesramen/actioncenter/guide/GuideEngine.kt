package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.SyncStateEntity
import com.phonoodlesramen.actioncenter.safety.summary
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Plex Guide in the launcher (G0): channels and a rolling 24 hours of programming in
 * `guide.db`, made from the Plex cache. It replaces [GuideSource.None].
 *
 * The schedule is extended (the starter lineup made first, once the library has synced
 * in full) after each library sync, by a background job every few hours, and while the
 * home reads [card]. Extending never blocks the home, and nothing here throws: an
 * uncaught exception would put the launcher into safe mode.
 *
 * **The lineup's server (G19).** The starter lineup remembers the Plex server it was made
 * from ([lineupServer]). Once the library has synced in full from a different one, the
 * lineup is remade from it; re-linking the same server keeps everything. Linking another
 * server means signing out first, which empties the cache and its sync state, so a full
 * sync after that is the new server's. Once a lineup is made, an empty one (every channel
 * deleted) stays empty until it's remade by hand.
 *
 * **Kids (K8, R2).** Kids see the channels that play only titles inside their ceiling
 * ([KidGuide]), so each channel carries the ratings of what it can play ([ratingsOf]). A
 * starter lineup has a Family channel; one made before Phase 5 gets it appended once
 * ([addFamilyOnce]).
 */
class GuideEngine(
    private val guideDb: GuideDatabase,
    private val plexDb: PlexDatabase,
    internal val clock: () -> Long = System::currentTimeMillis,
    internal val zone: () -> ZoneId = ZoneId::systemDefault,
    internal val io: CoroutineDispatcher = Dispatchers.IO,
    /** The soonest [card] looks again at whether the schedule needs extending, and the longest. */
    private val checkEveryMillis: Long = CHECK_EVERY_MILLIS,
    private val maxWaitMillis: Long = MAX_WAIT_MILLIS,
    /** One line per change (the TVs log it under [LOG_TAG] in logcat). Counts and channel numbers, never a title. */
    private val log: (String) -> Unit,
    /** The linked Plex server's client identifier; null while none is linked. */
    private val linkedServer: suspend () -> String? = { null },
    private val lineupServer: LineupServer = LineupServer.InMemory(),
    /** How far ahead every channel is kept scheduled: a day on a TV, two on the station (N5). */
    private val horizonMillis: Long = HORIZON_MILLIS,
) : GuideSource {
    private val dao = guideDb.dao()
    internal val library = GuideLibrary(plexDb.guideLibrary())
    internal val scheduler = GuideScheduler(library)
    private val looks = ChannelLooks(plexDb)

    /** One change at a time: the worker, a sync, the home and the editor can ask together. */
    private val running = Mutex()

    /**
     * Whether a starter lineup is being made (the first time or a remake), and the sync it
     * was last tried after, so an unchanged library isn't asked again. [card] reads both.
     */
    private data class Starter(val building: Boolean = false, val triedAfter: Long? = null)

    private val starter = MutableStateFlow(Starter())

    /** The sync the Family channel was last looked for after (K8), so an unchanged library isn't asked again. */
    @Volatile
    private var familyTriedAfter: Long? = null

    /**
     * Each channel that's on, with its schedule from [SHOW_PAST_MILLIS] ago to its end, in
     * number order: re-read when the schedule, the library or its sync changes. While it's
     * read, the schedule is extended once it's less than [EXTEND_UNDER_MILLIS] ahead:
     * checked when reading starts, then when that's next due (about hourly), not on a fast
     * tick. Without channels to show, it says why ([GuideCardState]).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val card: Flow<GuideCardState> = channelFlow {
        launch {
            while (true) delay(extendIfDue())
        }
        combine(
            guideDb.invalidationTracker.createFlow(*GuideDatabase.TABLES, emitInitialState = true),
            plexDb.invalidationTracker.createFlow(ITEMS, SYNC_STATE, emitInitialState = true),
            starter,
        ) { _, _, _ -> }
            .conflate()
            .mapLatest { read() }
            .collect { if (it != null) send(it) }
    }
        .distinctUntilChanged()
        .flowOn(io)
        .catch { log("guide: lineup not read: ${it.summary()}") }

    /**
     * Makes the starter lineup if there's none yet (or remakes it for another server),
     * then schedules every channel until the next 24 hours are covered and prunes old
     * slots. Never throws.
     */
    suspend fun extend() {
        try {
            withContext(io) { running.withLock { extendOnce() } }
        } catch (e: Exception) {
            failed("not extended", e)
        }
    }

    /**
     * Runs one of the editor's changes (2.7): off the main thread, and never while the
     * schedule is being extended, since an extension saves whole channel rows. Throws
     * whatever [block] throws; the editor turns it into [EditRejected].
     */
    internal suspend fun <T> edit(block: suspend Editing.() -> T): T = withContext(io) { running.withLock { Editing().block() } }

    /** [channel]'s next 24 hours from now, scheduled in memory only: the editor's preview. Writes nothing. */
    internal suspend fun preview(channel: ChannelEntity): List<GuideSlot> = withContext(io) {
        val now = clock()
        val extension = scheduler.extend(channel, lastSlotEnd = null, now, now + horizonMillis, zone())
            ?: return@withContext emptyList()
        val slots = extension.slots.filter { it.endMillis > now }
        val playable = library.playable(slots.filter { it.kind == SlotEntity.PROGRAM }.mapNotNull { it.itemKey })
        slots.map { it.toGuideSlot(playable) }
    }

    /** What a change can do while it holds the lock. Its clock is read once, when it starts. */
    internal inner class Editing {
        val now = clock()
        val zone = zone()

        /** Saves [channel], new, with its first 24 hours from the current start time, together. */
        suspend fun add(channel: ChannelEntity) {
            val extension = scheduler.extend(channel, lastSlotEnd = null, now, now + horizonMillis, zone)
            dao.addChannel(extension?.channel ?: channel, extension?.slots.orEmpty())
        }

        /**
         * Rewrites [channel]'s schedule with its new rule, start times or filler (G16): the
         * slot on now finishes, and from its end (or from now, in a gap) the channel plays
         * from a fresh cursor. The slot on now, the earlier ones and every other channel
         * stay as they are. In a gap the new schedule starts at the current start time, as
         * it does after days away, so something is on at once.
         */
        suspend fun rewrite(channel: ChannelEntity) {
            val onNow = dao.slotAt(channel.id, now)
            val from = onNow?.endMillis ?: now
            val keptEnd = dao.lastEndBefore(channel.id, from)
            val fresh = channel.copy(cursor = ChannelCursor().toJson(), scheduledUntil = if (onNow != null) from else keptEnd)
            val extension = scheduler.extend(fresh, keptEnd, now, now + horizonMillis, zone)
            dao.rewriteFrom(extension?.channel ?: fresh, from, extension?.slots.orEmpty())
            log("guide: channel ${channel.number} rewritten with ${extension?.slots?.size ?: 0} slots")
        }

        /**
         * Replaces every channel with a new starter lineup and schedules it (G18). Rejected,
         * and the lineup kept, before the library has synced or when nothing fills a day.
         */
        suspend fun remake() {
            if (library.syncState()?.lastFullAt == null) {
                throw EditRejected("Plex hasn't synced your library yet, so there's nothing to make a lineup from.")
            }
            val channels = StarterLineup(library).make(year(now, zone))
            if (channels.isEmpty()) throw EditRejected("Nothing in your library fills a day of a channel yet, so the lineup stays as it is.")
            try {
                replaceLineup(channels, linked())
                extendAll(now, zone)
            } finally {
                starter.update { it.copy(building = false) }
            }
        }
    }

    /** Extends the schedule if it's due; returns how long to wait before looking again. */
    private suspend fun extendIfDue(): Long {
        val earliest = try {
            dao.earliestEnd()
        } catch (e: Exception) {
            failed("schedule not read", e)
            return checkEveryMillis
        }
        val due = earliest?.minus(EXTEND_UNDER_MILLIS)
        if (due == null || due <= clock()) {
            extend()
            return checkEveryMillis
        }
        return (due - clock()).coerceIn(checkEveryMillis, maxWaitMillis)
    }

    /**
     * Logs [e]. A cancellation is rethrown when it's this coroutine's own; otherwise it's
     * Room's, from a closed database, and is an error like any other.
     */
    private suspend fun failed(what: String, e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        log("guide: $what: ${e.summary()}")
    }

    private suspend fun extendOnce() {
        val now = clock()
        val zone = zone()
        val sync = library.syncState()
        val server = linked()
        val remembered = lineupServer.get()
        try {
            when {
                // G19: the library has synced in full from another server, so the lineup's titles are gone.
                server != null && remembered != null && server != remembered && sync?.lastFullAt != null -> {
                    log("guide: the library is another Plex server's; remaking the lineup")
                    replaceLineup(StarterLineup(library).make(year(now, zone)), server)
                    starter.update { it.copy(triedAfter = sync.lastSuccessAt ?: sync.lastFullAt) }
                }
                dao.channelCount() == 0 -> if (remembered == null) makeStarterLineup(sync, server, now, zone)
                // A lineup made before 2.7 remembered no server: it's the one linked now.
                remembered == null && server != null -> lineupServer.set(server)
            }
            addFamilyOnce(sync, now, zone)
            val (channels, slots) = extendAll(now, zone)
            val pruned = dao.prune(now - KEEP_PAST_MILLIS)
            if (channels > 0 || pruned > 0) log("guide: extended $channels channels with $slots slots; pruned $pruned")
        } finally {
            starter.update { it.copy(building = false) }
        }
    }

    /**
     * Schedules every channel, off ones too, until the next 24 hours are covered. Returns how
     * many channels and slots it added.
     */
    private suspend fun extendAll(now: Long, zone: ZoneId): Pair<Int, Int> {
        val until = now + horizonMillis
        var channels = 0
        var slots = 0
        for (saved in dao.channels()) {
            // One channel's problem (a bad rule) never stops the others.
            try {
                val channel = relaid(saved, now, zone) ?: saved
                if ((channel.scheduledUntil ?: Long.MIN_VALUE) >= until) continue
                val extension = scheduler.extend(channel, dao.lastEnd(channel.id), now, until, zone) ?: continue
                dao.saveExtension(extension.channel, extension.slots)
                channels++
                slots += extension.slots.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("guide: channel ${saved.number} not extended: ${e.summary()}")
            }
        }
        return channels to slots
    }

    /**
     * A schedule written before G21 still has Up next cards to come: it's laid again from the
     * end of the program on now (or from the card on now), the same programs in the same
     * order with trailers in the gaps, and the cursor carries on as it was. Null when there
     * are no cards to come, so it runs once per channel.
     */
    private suspend fun relaid(channel: ChannelEntity, now: Long, zone: ZoneId): ChannelEntity? {
        val ahead = dao.slots(channel.id).filter { it.endMillis > now }
        if (ahead.none { it.kind == SlotEntity.UP_NEXT }) return null
        val onNow = ahead.firstOrNull { it.startMillis <= now }
        val from = when {
            onNow == null -> ahead.first().startMillis
            onNow.kind == SlotEntity.UP_NEXT -> onNow.startMillis
            else -> onNow.endMillis
        }
        val programs = ahead.filter { it.kind == SlotEntity.PROGRAM && it.startMillis >= from }.mapNotNull { slot ->
            val key = slot.itemKey ?: return@mapNotNull null
            Program(
                key, slot.endMillis - slot.startMillis, slot.title, slot.episode, slot.showKey, slot.art, slot.contentRating, slot.summary,
                trailerKey = if (slot.showKey == null) library.item(key)?.trailerKey else null,
            )
        }
        val extension = scheduler.relay(channel, programs, from, now, zone)
        dao.rewriteFrom(extension.channel, from, extension.slots)
        log("guide: channel ${channel.number} laid again with trailers, ${programs.size} programs")
        return extension.channel
    }

    /** The first lineup (G2). Waits for a full sync, and asks again only after a newer one. */
    private suspend fun makeStarterLineup(sync: SyncStateEntity?, server: String?, now: Long, zone: ZoneId) {
        val fullAt = sync?.lastFullAt ?: return
        val syncedAt = sync.lastSuccessAt ?: fullAt
        if (syncedAt == starter.value.triedAfter) return
        val channels = StarterLineup(library).make(year(now, zone))
        if (channels.isEmpty()) {
            log("guide: nothing in the library fills a channel yet")
        } else {
            starter.update { it.copy(building = true) }
            dao.insertChannels(channels)
            lineupServer.set(server)
            lineupServer.setFamilyAdded(true)
            log("guide: starter lineup of ${channels.size} channels")
        }
        starter.update { it.copy(triedAfter = syncedAt) }
    }

    /**
     * K8: a lineup made before Phase 5 gets the Family channel once, numbered after the last
     * ([StarterLineup.family]). One that has it already (named and rated so) is just marked.
     * Without a day of family titles it waits, and looks again after a newer sync. Never
     * twice: once added, a Family channel deleted by hand stays deleted. No lineup yet, or an
     * emptied one, gets nothing: the starter lineup makes its own.
     */
    private suspend fun addFamilyOnce(sync: SyncStateEntity?, now: Long, zone: ZoneId) {
        if (lineupServer.familyAdded()) return
        val channels = dao.channels()
        if (channels.isEmpty()) return
        if (channels.any(StarterLineup::isFamily)) return lineupServer.setFamilyAdded(true)
        val syncedAt = sync?.lastSuccessAt ?: sync?.lastFullAt ?: return
        if (syncedAt == familyTriedAfter) return
        familyTriedAfter = syncedAt
        val ids = channels.mapTo(HashSet()) { it.id }
        var id = StarterLineup.FAMILY_ID
        var n = 2
        while (id in ids) id = "${StarterLineup.FAMILY_ID}-${n++}"
        val family = StarterLineup(library).family(year(now, zone), number = channels.maxOf { it.number } + 1, id = id) ?: return
        dao.insertChannels(listOf(family))
        lineupServer.setFamilyAdded(true)
        log("guide: Family channel added as ${family.number}")
    }

    /**
     * Every channel and slot replaced by [channels] in one transaction, the card building
     * until the caller clears it. A lineup that made nothing remembers no server, so the
     * next sync tries again.
     */
    private suspend fun replaceLineup(channels: List<ChannelEntity>, server: String?) {
        starter.update { it.copy(building = true) }
        dao.replaceLineup(channels)
        lineupServer.set(server.takeIf { channels.isNotEmpty() })
        if (channels.isNotEmpty()) lineupServer.setFamilyAdded(true)
        log("guide: starter lineup of ${channels.size} channels")
    }

    private suspend fun read(): GuideCardState? = try {
        if (starter.value.building) {
            GuideCardState.Building
        } else {
            val now = clock()
            val all = dao.channels()
            val channels = all.filter { it.enabled }
            val on = channels.mapTo(HashSet()) { it.id }
            val slots = if (on.isEmpty()) emptyMap() else dao.slotsFrom(now - SHOW_PAST_MILLIS).filter { it.channelId in on }.groupBy { it.channelId }
            val programs = slots.values.asSequence().flatten().filter { it.kind == SlotEntity.PROGRAM }.mapNotNull { it.itemKey }.toSet()
            val playable = library.playable(programs)
            val sync = library.syncState()
            val year = year(now, zone())
            val lineup = channels.mapNotNull { channel ->
                val own = slots[channel.id].orEmpty()
                if (own.isEmpty()) {
                    null
                } else {
                    val rated = ratingsOf(channel, own, now, year, sync)
                    val look = looks.of(channel.name, channel.rule, channel.icon)
                    GuideChannel(
                        channel.id, channel.number, channel.name, logo = look.logo, slots = own.map { it.toGuideSlot(playable) },
                        contentRatings = rated.ratings, unrated = rated.unrated, glyph = look.glyph,
                    )
                }
            }
            when {
                lineup.isNotEmpty() -> GuideCardState.Ready(lineup, channels.mapNotNull { it.extendedAt }.maxOrNull())
                all.isNotEmpty() && channels.isEmpty() -> GuideCardState.NoneOn
                else -> nothingToShow(all.isEmpty())
            }
        }
    } catch (e: Exception) {
        failed("lineup not read", e)
        null
    }

    /**
     * What [channel] can play is rated (R2, K8): every title its rule can play, remembered
     * until the library syncs again ([GuideLibrary.channelRatings]), and each program in
     * [slots] still to finish. Those cover the program on now after a rule edit (G16) and
     * anything scheduled since the last sync, so a kid never sees a channel while it's on
     * something outside their ceiling. Nothing at all when the rule matches no title.
     */
    private suspend fun ratingsOf(channel: ChannelEntity, slots: List<SlotEntity>, now: Long, year: Int, sync: SyncStateEntity?): PoolRatings {
        val pool = library.channelRatings(channel.rule, year, sync)
        if (pool.nothing) return pool
        val ahead = slots.filter { it.kind == SlotEntity.PROGRAM && it.endMillis > now }
        return PoolRatings(
            ratings = pool.ratings + ahead.mapNotNull { slot -> slot.contentRating?.takeIf { it.isNotBlank() } },
            unrated = pool.unrated || ahead.any { it.contentRating.isNullOrBlank() },
        )
    }

    /**
     * Why no channel shows: the library hasn't synced; its starter lineup is next (it's
     * made as soon as the sync or the card's own check gets to it); a lineup made for this
     * server was emptied by hand; or nothing fills a channel, or none has a schedule.
     */
    private suspend fun nothingToShow(noChannels: Boolean): GuideCardState {
        val sync = library.syncState()
        val fullAt = sync?.lastFullAt ?: return GuideCardState.NotSetUp
        val syncedAt = sync.lastSuccessAt ?: fullAt
        val made = lineupServer.get() != null
        return when {
            noChannels && made -> GuideCardState.NoneOn
            noChannels && starter.value.triedAfter != syncedAt -> GuideCardState.Building
            else -> GuideCardState.NothingToFill
        }
    }

    /** The linked server, or null when it can't be read: the schedule still extends. */
    private suspend fun linked(): String? = try {
        linkedServer()
    } catch (e: Exception) {
        failed("linked server not read", e)
        null
    }

    private fun year(now: Long, zone: ZoneId) = Instant.ofEpochMilli(now).atZone(zone).year

    companion object {
        const val LOG_TAG = "ActionCenterGuide"

        /** The schedule always covers the next 24 hours (the operator's choice, not the design's 7 days). */
        const val HORIZON_MILLIS = 24 * 60 * 60_000L

        /** While the home reads the lineup, it's extended once less than this is scheduled. */
        const val EXTEND_UNDER_MILLIS = 23 * 60 * 60_000L

        /** How far back [card] reaches, so the full guide can step back a little. */
        const val SHOW_PAST_MILLIS = 3 * 60 * 60_000L

        /** Slots that ended longer ago than this are pruned. */
        const val KEEP_PAST_MILLIS = 6 * 60 * 60_000L

        /** While the lineup is read: the soonest it looks again (no channels yet, or one that can't be extended). */
        const val CHECK_EVERY_MILLIS = 5 * 60_000L

        /** And the longest, so a changed clock is noticed. */
        const val MAX_WAIT_MILLIS = 60 * 60_000L

        const val GONE = "This title is no longer in your library"
        const val NO_FILE = "There's no file to play for this title"

        private const val ITEMS = "items"
        private const val SYNC_STATE = "sync_state"
    }
}

/**
 * The ratings [ChannelEntity.rule] names (how the Family channel is known); none for a rule
 * that won't read. Kids go by what a channel can play instead ([GuideEngine.ratingsOf]).
 */
internal fun ChannelEntity.contentRatings(): Set<String> =
    try {
        ChannelRule.fromJson(rule).contentRatings.toSet()
    } catch (e: IllegalArgumentException) {
        emptySet()
    }

/** A slot as the home shows it. A program whose title left the cache, or lost its file, is off air. */
internal fun SlotEntity.toGuideSlot(playable: Map<String, Boolean>) = GuideSlot(
    startMillis = startMillis,
    endMillis = endMillis,
    title = title,
    episode = episode,
    itemKey = itemKey,
    showKey = showKey,
    art = art,
    contentRating = contentRating,
    filler = kind != SlotEntity.PROGRAM,
    offAir = if (kind != SlotEntity.PROGRAM) {
        null
    } else {
        when (playable[itemKey]) {
            true -> null
            false -> GuideEngine.NO_FILE
            null -> GuideEngine.GONE
        }
    },
    summary = summary,
)
