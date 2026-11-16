package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.plex.PlexWatchApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.profiles.PlexViewers
import com.phonoodlesramen.actioncenter.safety.summary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** How a branch went: who Plezy opens as ([profile], K3), and whether Plex took its write. */
data class Branch(val profile: String?, val written: Boolean)

/**
 * The guide player's two writes to Plex (Phase 11, amending G6), each for the profile on
 * now's Plex user ([PlexViewers], K3). A kid without a Plex user sends nothing. Nothing
 * here throws, and nothing logged carries a token.
 *
 * @param counting Settings › Plex › Count guide viewing in Plex, read at each count.
 * @param scope where a write runs: it can outlive a branch that stopped waiting for it.
 */
class GuidePlexWrites(
    private val viewers: PlexViewers,
    private val api: PlexWatchApi,
    private val counting: suspend () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val log: (String) -> Unit = {},
) {
    /**
     * A program watched to its credits on a guide channel (K1, K2): marked watched, once the
     * player says so. Nothing with the setting off, or for a kid without a Plex user. True
     * when Plex took it.
     */
    suspend fun watched(ratingKey: String): Boolean {
        if (!safely(false) { counting() }) return false
        val viewer = safely(null) { viewers.current() } ?: return false
        return safely(false) {
            api.markWatched(viewer.access, ratingKey)
            log("player: $ratingKey marked watched in Plex")
            true
        }
    }

    /**
     * Left a guide program you'd seen from its start, short of its watched point (P5,
     * [PlaceRules.leftOff]): Plex's resume point, so Up Next, Plezy and every Plex app carry
     * on from there once the guide has moved on. Nothing with the setting off, or for a kid
     * without a Plex user. True when Plex took it.
     */
    suspend fun leftOff(ratingKey: String, positionMs: Long): Boolean {
        if (!safely(false) { counting() }) return false
        val viewer = safely(null) { viewers.current() } ?: return false
        return safely(false) {
            api.setPosition(viewer.access, ratingKey, positionMs)
            log("player: $ratingKey left off at ${positionMs / 1_000} s in Plex")
            true
        }
    }

    /**
     * Before a branch into Plezy or Plex (plan §2): [write] for [ratingKey], waiting
     * [timeoutMillis] at most. Writing nothing counts as written. The setting doesn't apply:
     * the branch is the viewer's own choice to carry on.
     */
    suspend fun branch(ratingKey: String, write: ResumeWrite, timeoutMillis: Long = GuideViewing.WRITE_TIMEOUT_MILLIS): Branch {
        val viewer = safely(null) { viewers.current() }
        if (write == ResumeWrite.Nothing) return Branch(viewer?.profile, written = true)
        if (viewer == null) return Branch(profile = null, written = false)
        // Plex may answer late: the branch goes ahead without it, and the write still lands.
        val sent = scope.async { safely(false) { send(viewer.access, ratingKey, write) } }
        val written = withTimeoutOrNull(timeoutMillis) { sent.await() } ?: false
        if (!written) log("player: $ratingKey: Plex didn't take the branch's write in time")
        return Branch(viewer.profile, written)
    }

    private suspend fun send(access: ServerAccess, ratingKey: String, write: ResumeWrite): Boolean {
        when (write) {
            is ResumeWrite.Position -> api.setPosition(access, ratingKey, write.positionMs)
            ResumeWrite.Watched -> api.markWatched(access, ratingKey)
            ResumeWrite.Nothing -> Unit
        }
        return true
    }

    private suspend fun <T> safely(otherwise: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("player: Plex write: ${e.summary()}")
        otherwise
    }

    companion object {
        /** Previews and tests that don't care: nobody to write for. */
        val None = GuidePlexWrites(PlexViewers.None, NoWrites, counting = { false })
    }

    private object NoWrites : PlexWatchApi {
        override suspend fun setPosition(access: ServerAccess, ratingKey: String, positionMs: Long) {}
        override suspend fun markWatched(access: ServerAccess, ratingKey: String) {}
    }
}
