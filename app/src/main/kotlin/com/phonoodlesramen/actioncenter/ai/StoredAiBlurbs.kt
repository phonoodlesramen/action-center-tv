package com.phonoodlesramen.actioncenter.ai

import android.util.Log
import com.phonoodlesramen.actioncenter.settings.Settings
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Where the key page puts the key from your phone (M10). */
interface AiKeyDrop {
    /** The stored key's last 4 characters, or null. */
    suspend fun keyEnding(): String?

    /** Stores [key], already checked with [AiKeys.problem]. */
    suspend fun saveKey(key: String)
}

/**
 * Settings › AI blurbs (M7-M11) over the stored key, the last run and the schedule.
 *
 * - **Status:** the key's last 4 characters and the last run's line ([BlurbRunLine]).
 * - **The schedule follows the switch:** on, the nightly run is kept scheduled; off, it's
 *   cancelled and the blurbs are cleared, so the picks show their own reasons at once.
 * - **A first run** as soon as it's on with a key, in either order (turned on with a key
 *   stored, or a key added while it's on), and on [writeNow]. Never at start-up.
 * - **Never throws:** WorkManager or the database failing is logged by class and left.
 *
 * @param clearBlurbs deletes every stored blurb.
 */
class StoredAiBlurbs(
    private val settings: Flow<Settings>,
    private val vault: AiKeyVault,
    runs: BlurbRuns,
    private val schedule: BlurbSchedule,
    private val clearBlurbs: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> ZonedDateTime = ZonedDateTime::now,
    private val log: (String) -> Unit = { Log.w(BlurbWriter.LOG_TAG, it) },
) : AiBlurbsController, AiKeyDrop {
    override val status: Flow<AiBlurbsStatus> = combine(vault.ending, runs.last) { ending, run ->
        AiBlurbsStatus(keyEnding = ending, lastRun = run?.let { BlurbRunLine.of(it, clock()) }, failed = run?.error != null)
    }

    init {
        scope.launch { followTheSwitch() }
    }

    override fun writeNow() = quietly { schedule.runNow() }

    override fun forgetKey() {
        scope.launch { quietly { vault.forget() } }
    }

    override suspend fun keyEnding(): String? = vault.ending.first()

    override suspend fun saveKey(key: String) = vault.save(key)

    private suspend fun followTheSwitch() {
        var ready: Boolean? = null
        combine(settings.map { it.aiBlurbs }.distinctUntilChanged(), vault.ending.map { it != null }.distinctUntilChanged()) { on, key -> on to key }
            .distinctUntilChanged()
            .collect { (on, key) ->
                // Each on its own: WorkManager failing never keeps the blurbs from clearing.
                if (on) {
                    quietly { schedule.nightly() }
                } else {
                    quietly { schedule.cancel() }
                    quietly { clearBlurbs() }
                }
                // Only a change: opening Settings with it already on doesn't send anything.
                if (on && key && ready == false) quietly { schedule.runNow() }
                ready = on && key
            }
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("ai: ${e.javaClass.simpleName}")
        }
    }
}
