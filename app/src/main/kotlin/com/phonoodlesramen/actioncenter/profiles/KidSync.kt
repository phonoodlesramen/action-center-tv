package com.phonoodlesramen.actioncenter.profiles

import android.util.Log
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexItem
import com.phonoodlesramen.actioncenter.plex.PlexPlay
import com.phonoodlesramen.actioncenter.plex.PlexServerAccount
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.cache.Finished
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexSync
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Each linked kid's own Plex (K2), after each Plex sync, into `profiles.db`:
 * - their Continue Watching, with their own token, so Plex's restrictions for them apply;
 * - their plays from the server's history, 400 days like yours.
 *
 * The library itself is shared: one sync, not one per kid. A kid with no Plex Home user or
 * no token is skipped and their rows go, so their Up Next is empty, never yours.
 *
 * Their plays: the history endpoint with their token first. When the server refuses it
 * (PMS keeps history to its owner), your token asks for their account's. Either way the
 * request names their `accountID`: `/accounts`, read once a run with your token, lists
 * them by their plex.tv id or else by their Home user's title (research §4). A kid it can't
 * place keeps no plays rather than a guess, and never yours (account 1).
 *
 * Never throws. Logs counts under `ActionCenterPlex`, never a name, an id or a token.
 */
class KidSync(
    private val kids: suspend () -> List<KidEntity>,
    private val tokens: KidTokens,
    /** Your server: its address, and your token for `/accounts` and the history fallback. Null when nothing is linked. */
    private val access: suspend () -> ServerAccess?,
    private val server: PlexServerApi,
    private val dao: KidPlexDao,
    /** A show's cached episodes, for the one after an episode they finished in its credits ([Finished]). */
    private val episodesOf: suspend (showKey: String) -> List<ItemEntity> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i("ActionCenterPlex", it) },
) {
    /** A periodic sync and a "sync now" can finish together: one kids' sync at a time. */
    private val running = Mutex()

    suspend fun sync() = running.withLock {
        try {
            syncOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("kids: failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun syncOnce() {
        val all = kids()
        dao.keepOnly(all.map { it.id })
        if (all.isEmpty()) return
        val yours = access()
        if (yours == null) {
            // Signed out: the cache is gone, and the kids' Plex data goes with it.
            all.forEach { dao.clear(it.id) }
            return log("kids: no server linked")
        }
        val run = Run(yours, before = clock() / 1_000 - PlexSync.HISTORY_DAYS * DAY_SECONDS)
        for ((index, kid) in all.withIndex()) {
            val token = kid.plexUserId?.let { tokens.serverToken(kid.id) }
            if (token == null) {
                dao.clear(kid.id)
                run.notLinked++
                continue
            }
            try {
                run.sync(kid, ServerAccess(yours.baseUri, token))
            } catch (e: PlexException.Unreachable) {
                // Down for everyone: the kids still to go would only wait for it too, and keep what they had.
                run.failed += all.size - index
                run.unreachable = true
                break
            }
        }
        log(run.line())
    }

    /** One run over the kids, with `/accounts` read once and the counts the log line gives. */
    private inner class Run(private val yours: ServerAccess, private val before: Long) {
        var synced = 0
        var notLinked = 0
        var failed = 0
        var unreachable = false
        private var byTheirToken = 0
        private var byYours = 0
        private var unplaced = 0
        private var accounts: List<PlexServerAccount>? = null
        private var accountsRead = false

        suspend fun sync(kid: KidEntity, theirs: ServerAccess) {
            var clean = true
            val continueWatching = try {
                // What they finished in its credits makes way for the next episode, as on yours (the Up Next credits fix).
                val listed = server.continueWatching(theirs, PlexSync.CONTINUE_WATCHING)
                Finished.upNext(listed, credits(theirs, listed), episodesOf).mapIndexed { rank, item -> item.toKidContinue(kid.id, rank) }
            } catch (e: PlexException.Unreachable) {
                throw e
            } catch (e: PlexException) {
                clean = false
                null
            }
            val plays = try {
                plays(kid, theirs)
            } catch (e: PlexException.Unreachable) {
                throw e
            } catch (e: PlexException) {
                clean = false
                null
            }
            dao.save(kid.id, continueWatching, plays)
            if (clean) synced++ else failed++
        }

        /** The first credits markers of what they're part-way into; none when the server won't say ([Finished]'s 90% then). */
        private suspend fun credits(theirs: ServerAccess, items: List<PlexItem>): Map<String, Long> {
            val started = items.filter { (it.viewOffsetMs ?: 0) > 0 }.map { it.ratingKey }
            if (started.isEmpty()) return emptyMap()
            return try {
                server.creditsStart(theirs, started)
            } catch (e: PlexException.Unreachable) {
                throw e
            } catch (e: PlexException) {
                emptyMap()
            }
        }

        /** Their new plays, or none at all when the server doesn't list them; null when it couldn't say. */
        private suspend fun plays(kid: KidEntity, theirs: ServerAccess): KidPlays? {
            val listed = accounts() ?: return null
            val account = accountFor(kid, listed)
            if (account == null) {
                unplaced++
                return KidPlays(NO_ACCOUNT, emptyList(), before)
            }
            val since = maxOf(dao.latestPlay(kid.id, account)?.plus(1) ?: 0, before)
            val (plays, theirToken) = history(theirs, since, account.toInt())
            if (theirToken) byTheirToken++ else byYours++
            // Only the account asked for: a server that ignored `accountID` can't hand a kid your plays.
            return KidPlays(account, plays.map { it.toKidPlay(kid.id, account) }, before)
        }

        private suspend fun accounts(): List<PlexServerAccount>? {
            if (!accountsRead) {
                accountsRead = true
                accounts = try {
                    server.accounts(yours)
                } catch (e: PlexException.Unreachable) {
                    throw e
                } catch (e: PlexException) {
                    null
                }
            }
            return accounts
        }

        /** Their plays since [since]: with their token, else, when the server refuses it, with yours. True when theirs worked. */
        private suspend fun history(theirs: ServerAccess, since: Long, account: Int): Pair<List<PlexPlay>, Boolean> = try {
            server.history(theirs, since, account) to true
        } catch (e: PlexException) {
            if (!refused(e)) throw e
            server.history(yours, since, account) to false
        }

        fun line(): String {
            val counts = listOfNotNull(
                "$synced synced",
                "$notLinked not linked",
                failed.takeIf { it > 0 }?.let { "$it failed${if (unreachable) " (server unreachable)" else ""}" },
            )
            return "kids: ${counts.joinToString()}; history with their token $byTheirToken, with yours $byYours, not on the server $unplaced"
        }
    }

    companion object {
        private const val DAY_SECONDS = 86_400L

        /** No account: a kid the server doesn't list keeps no plays. */
        const val NO_ACCOUNT = 0L

        /** You, on your own server: never a kid's account. */
        private const val OWNER_ACCOUNT = 1L

        /**
         * [kid]'s account on the server: the one with their plex.tv id, else the only one
         * named as their Home user (research §4). Never yours, and never a guess between two.
         */
        fun accountFor(kid: KidEntity, accounts: List<PlexServerAccount>): Long? {
            val theirs = accounts.filter { it.id > OWNER_ACCOUNT && it.id <= Int.MAX_VALUE }
            theirs.firstOrNull { it.id == kid.plexUserId }?.let { return it.id }
            val title = kid.plexUserTitle?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return theirs.filter { it.name.trim().equals(title, ignoreCase = true) }.singleOrNull()?.id
        }

        /** The server said no to the token (401, 403 or another 4xx), rather than being down or broken. */
        private fun refused(e: PlexException) = e is PlexException.Unauthorized || (e is PlexException.Failed && e.code in 400..499)
    }
}
