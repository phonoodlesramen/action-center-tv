package com.phonoodlesramen.actioncenter.plex.cache

import com.phonoodlesramen.actioncenter.plex.LinkedServer
import com.phonoodlesramen.actioncenter.plex.PlexAccounts
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexLinkStore
import com.phonoodlesramen.actioncenter.plex.PlexSection
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.ServerReach
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncResult { UPDATED, NOT_LINKED, OFFLINE, SIGNED_OUT, FAILED }

/**
 * Copies what the cards need from the linked server into the cache: Continue Watching
 * every time, Movies and TV items (P7) since the last sync (all of them once a day, so
 * removals go too), and the account's history (P5). The UI reads only the cache.
 */
class PlexSync(
    private val store: PlexLinkStore,
    private val accounts: PlexAccounts,
    private val reach: ServerReach,
    private val server: PlexServerApi,
    private val dao: PlexDao,
    private val clock: () -> Long = System::currentTimeMillis,
    /** One line per run (the TVs log it under ActionCenterPlex in logcat). Paths only, never a token. */
    private val log: (String) -> Unit,
    /** Runs after each sync of a linked server: the Plex Guide extends its schedule. It never fails the sync. */
    private val afterSync: suspend () -> Unit = {},
) {
    private val oneAtATime = Mutex()
    private val libraryCheck = LibraryCheck(server, dao, log)

    /** Calls to [sync] under way, the one waiting for the lock included. */
    private val runs = MutableStateFlow(0)

    /**
     * True while a sync runs, the work after it included (the cards change until then): the
     * top bar's "Updating" ([com.phonoodlesramen.actioncenter.home.Updating]).
     */
    val running: Flow<Boolean> = runs.map { it > 0 }.distinctUntilChanged()

    /**
     * One sync at a time: the periodic run and a "sync now" can start together, and two
     * runs would delete each other's rows (a full sync drops rows it didn't see).
     */
    suspend fun sync(): SyncResult {
        runs.update { it + 1 }
        try {
            val result = oneAtATime.withLock { syncOnce() }
            if (result != SyncResult.NOT_LINKED) {
                try {
                    afterSync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("plex sync: after the sync: ${e.javaClass.simpleName}")
                }
            }
            return result
        } finally {
            runs.update { it - 1 }
        }
    }

    private suspend fun syncOnce(): SyncResult {
        val link = store.link.first()
        val linked = link.server
        val token = store.serverToken()
        if (!link.linked || linked == null || token == null) return SyncResult.NOT_LINKED
        val now = clock()
        val state = dao.syncState() ?: SyncStateEntity()
        // The run that was waiting for the lock has nothing left to do.
        if (state.lastSuccessAt != null && now - state.lastSuccessAt < JUST_SYNCED_MILLIS) return SyncResult.UPDATED
        dao.saveSyncState(state.copy(lastAttemptAt = now))
        return try {
            copy(ServerAccess(linked.uri, token), state, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexException) {
            // Unreachable or a rejected token: the address or the token may have changed.
            // Ask plex.tv again (P3) and retry once.
            log("plex sync: ${e.message}; asking plex.tv for the server again")
            val fresh = when (val relinked = relink(linked)) {
                is Relink.Ready -> relinked.access
                Relink.SignedOut -> return SyncResult.SIGNED_OUT.also { log("plex sync: plex.tv no longer accepts the account; signed out") }
                Relink.Unavailable -> return fail(state, now, if (e is PlexException.Unreachable) OFFLINE else e.message)
            }
            try {
                copy(fresh, state, now)
            } catch (again: PlexException) {
                fail(state, now, if (again is PlexException.Unreachable) OFFLINE else again.message)
            }
        }
    }

    /** True when a linked server's cache is over 30 minutes old (or never synced). */
    suspend fun needsSync(): Boolean = olderThan(STALE_AFTER_MILLIS)

    /** The home is back: true once a linked server's cache is [BACK_HOME_STALE_MILLIS] old. */
    suspend fun needsSyncBackHome(): Boolean = olderThan(BACK_HOME_STALE_MILLIS)

    private suspend fun olderThan(millis: Long): Boolean {
        if (!store.link.first().linked) return false
        val last = dao.syncState()?.lastAttemptAt ?: return true
        return clock() - last >= millis
    }

    /** The cache belongs to the account: signing out empties it. */
    suspend fun clear() {
        dao.replaceContinueWatching(emptyList())
        dao.clearItems()
        dao.clearPlays()
        dao.clearSyncState()
    }

    /**
     * Copies each part on its own: a server that answers one request with an error still
     * gets the others synced. Being unreachable or unauthorised stops the run (the
     * caller re-links). Only a clean run moves the "updated since" mark on, so a part
     * that failed is fetched in full next time.
     */
    private suspend fun copy(access: ServerAccess, state: SyncStateEntity, now: Long): SyncResult {
        val nowSeconds = now / 1_000
        val problems = mutableListOf<String>()
        suspend fun part(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: PlexException.Failed) {
                problems += e.message ?: "$name failed"
                log("plex sync: $name failed: ${e.message}")
            }
        }
        var continueCount = 0
        var creditsCount = 0
        var finishedCount = 0
        part("Continue Watching") {
            val listed = server.continueWatching(access, CONTINUE_WATCHING)
            val credits = creditsOf(access, listed)
            // What you finished in its credits, that Plex still lists, makes way for its show's
            // next episode in the cache (the Up Next credits fix). One added since the last sync
            // isn't cached yet: nothing takes the place until the next sync.
            val items = Finished.upNext(listed, credits, dao::episodesOf)
            dao.replaceContinueWatching(items.mapIndexed { i, item -> item.toContinue(i) })
            continueCount = items.size
            creditsCount = credits.size
            finishedCount = listed.count { Finished.at(it.viewOffsetMs, it.durationMs, credits[it.ratingKey]) }
        }

        val full = state.lastFullAt == null || now - state.lastFullAt >= FULL_SYNC_MILLIS
        // A minute's overlap covers clock differences between the Shield and the server.
        val since = if (full) null else state.itemsUpdatedSince?.minus(60)
        var sections: List<PlexSection>? = null
        part("Libraries") { sections = server.sections(access).filter { it.type in SECTION_TYPES } }
        var itemCount = 0
        var episodeCount = 0
        var trailerCount = 0
        sections?.forEach { section ->
            part("Library \"${section.title}\"") {
                // TV sections: the shows (the cards) and every episode (the Plex Guide's channels).
                for (type in SECTION_TYPES.getValue(section.type)) {
                    var start = 0
                    while (true) {
                        val page = server.items(access, section.key, type, start, PAGE_SIZE, since)
                        dao.upsertItems(keepFilledTags(page.items.map { it.toEntity(section.key, now) }))
                        trailerCount += page.items.count { it.trailerKey != null }
                        start += page.items.size
                        if (page.items.size < PAGE_SIZE || (page.totalSize != null && start >= page.totalSize)) break
                    }
                    itemCount += start
                    if (type == EPISODE) episodeCount += start
                }
                // Only a section read to the end can tell what's gone.
                if (full) dao.deleteUnseen(section.key, now)
            }
        }
        sections?.let { if (full) dao.deleteSectionsOtherThan(it.map { section -> section.key }) }

        var playCount = 0
        part("History") {
            // The server's "viewedAt>" includes the boundary: start just after the newest play we have.
            val historySince = maxOf(dao.latestPlay()?.plus(1) ?: 0, nowSeconds - HISTORY_DAYS * DAY_SECONDS)
            val plays = server.history(access, historySince)
            dao.insertPlays(plays.map { it.toEntity() })
            plays.forEach { dao.markPlayed(it.ratingKey, it.viewedAt) }
            dao.prunePlays(nowSeconds - HISTORY_DAYS * DAY_SECONDS)
            playCount = plays.size
        }

        val clean = problems.isEmpty()
        dao.saveSyncState(
            state.copy(
                lastAttemptAt = now,
                lastSuccessAt = if (clean) now else state.lastSuccessAt,
                lastFullAt = if (full && clean) now else state.lastFullAt,
                itemsUpdatedSince = if (clean) nowSeconds else state.itemsUpdatedSince,
                error = problems.firstOrNull(),
            ),
        )
        log(
            "plex sync: ${if (clean) "updated" else "partly failed"}${if (full) " (full)" else ""}: " +
                "$continueCount continue watching ($creditsCount with a credits marker, $finishedCount finished that Plex still lists), " +
                "$itemCount items ($episodeCount episodes, $trailerCount with a trailer, " +
                "${dao.playableCount()} with a file), $playCount plays",
        )
        if (clean) fillDetails(access, now)
        if (full && clean) check(access)
        return if (clean) SyncResult.UPDATED else SyncResult.FAILED
    }

    /**
     * The first credits markers of the titles you're part-way into, in one request. A
     * request the server rejects leaves Up Next to Plex's played point alone ([Finished]).
     */
    private suspend fun creditsOf(access: ServerAccess, items: List<PlexItem>): Map<String, Long> {
        val started = items.filter { (it.viewOffsetMs ?: 0) > 0 }.map { it.ratingKey }
        if (started.isEmpty()) return emptyMap()
        return try {
            server.creditsStart(access, started)
        } catch (e: PlexException.Failed) {
            log("plex sync: credits markers failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * The listing cuts a title's tags short (S11): a movie or show whose tags were filled
     * from its details keeps them while its `updatedAt` hasn't moved. A changed title takes
     * the listing's and is filled again. The studio (Phase 9) and the logo (Phase 12) are
     * the listing's when it has one, and otherwise the ones its details gave.
     */
    private suspend fun keepFilledTags(rows: List<ItemEntity>): List<ItemEntity> {
        val filled = dao.filledTags(rows.map { it.ratingKey }).associateBy { it.ratingKey }
        return rows.map { row ->
            val tags = filled[row.ratingKey]?.takeIf { it.updatedAt == row.updatedAt } ?: return@map row
            row.copy(
                genres = tags.genres, collections = tags.collections, labels = tags.labels, directors = tags.directors,
                actors = tags.actors, detailsAt = tags.detailsAt, studio = row.studio.ifEmpty { tags.studio },
                logo = row.logo ?: tags.logo,
            )
        }
    }

    /**
     * Movies' and shows' full tags from their details, [DETAILS_BATCH] at a time (S11): the
     * ones never filled, then any filled over a week ago. A batch that fails is left for the
     * next sync, and nothing here fails the sync.
     */
    private suspend fun fillDetails(access: ServerAccess, now: Long) {
        val keys = dao.needingDetails(before = now - DETAILS_REFRESH_MILLIS, limit = DETAILS_MAX)
        if (keys.isEmpty()) return
        val started = clock()
        var filled = 0
        var failed = 0
        try {
            for (batch in keys.chunked(DETAILS_BATCH)) {
                try {
                    val asked = batch.toSet()
                    val rows = server.details(access, batch).filter { it.ratingKey in asked }.map { it.toFilledTags(now) }
                    dao.fillAll(rows)
                    filled += rows.size
                } catch (e: PlexException.Failed) {
                    failed += batch.size
                    log("plex sync: details failed: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreachable or signed out: the next sync carries on where this one stopped.
            log("plex sync: details stopped: ${e.javaClass.simpleName}")
        }
        log("plex sync: details: $filled of ${keys.size} movies and shows filled in ${(clock() - started) / 1_000} s, $failed failed")
    }

    /** Phase 3's library check, once a day after the full sync. It never fails the sync. */
    private suspend fun check(access: ServerAccess) {
        try {
            libraryCheck.run(access)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("library check: ${e.javaClass.simpleName}")
        }
    }

    private sealed interface Relink {
        data class Ready(val access: ServerAccess) : Relink
        data object SignedOut : Relink
        data object Unavailable : Relink
    }

    private suspend fun relink(linked: LinkedServer): Relink {
        val userToken = store.userToken() ?: return Relink.Unavailable
        val servers = try {
            accounts.servers(userToken)
        } catch (e: PlexException.Unauthorized) {
            store.signOut()
            clear()
            return Relink.SignedOut
        } catch (e: PlexException) {
            return Relink.Unavailable
        }
        val same = servers.firstOrNull { it.clientIdentifier == linked.clientIdentifier } ?: return Relink.Unavailable
        val connection = reach.reach(same) ?: return Relink.Unavailable
        store.saveServer(same, connection)
        return Relink.Ready(ServerAccess(connection.uri, same.accessToken))
    }

    private suspend fun fail(state: SyncStateEntity, now: Long, reason: String?): SyncResult {
        log("plex sync: failed: ${reason ?: "failed"}")
        dao.saveSyncState(state.copy(lastAttemptAt = now, error = reason ?: "failed"))
        return if (reason == OFFLINE) SyncResult.OFFLINE else SyncResult.FAILED
    }

    companion object {
        const val OFFLINE = "offline"
        const val STALE_AFTER_MILLIS = 30 * 60_000L
        private const val JUST_SYNCED_MILLIS = 60_000L

        /**
         * Coming back to the home (from Plezy, say) syncs once the cache is a minute old: what
         * you just finished leaves Up Next at once, not up to 30 minutes later (the Up Next
         * credits fix: the 10/27/2026 sync had run mid-episode, 20 minutes before Home).
         */
        const val BACK_HOME_STALE_MILLIS = JUST_SYNCED_MILLIS
        const val FULL_SYNC_MILLIS = 24 * 60 * 60_000L
        const val CONTINUE_WATCHING = 12
        const val PAGE_SIZE = 200
        const val HISTORY_DAYS = 400L
        private const val DAY_SECONDS = 86_400L

        /** Titles per details request (S11). */
        const val DETAILS_BATCH = 50

        /** Filled tags are fetched again after a week, in case a collection or label changed. */
        const val DETAILS_REFRESH_MILLIS = 7 * 24 * 60 * 60_000L
        private const val DETAILS_MAX = 20_000

        /** The top-billed cast kept from a title's details. */
        const val CAST_KEPT = 10

        /** Movies and TV only (P7): Plex type 1 is a movie, 2 a show. */
        /** Plex metadata types to cache per section type: movies; shows and their episodes. */
        private val SECTION_TYPES = mapOf("movie" to listOf(1), "show" to listOf(2, EPISODE))
        private const val EPISODE = 4
    }
}
