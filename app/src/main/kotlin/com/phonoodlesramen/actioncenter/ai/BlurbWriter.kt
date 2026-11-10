package com.phonoodlesramen.actioncenter.ai

import android.util.Log
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexDao
import com.phonoodlesramen.actioncenter.settings.Settings
import com.phonoodlesramen.actioncenter.suggest.BlurbEntity
import com.phonoodlesramen.actioncenter.suggest.PickEntity
import com.phonoodlesramen.actioncenter.suggest.Profiles
import com.phonoodlesramen.actioncenter.suggest.SuggestionDao
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** What a night's run did, for [BlurbWorker]. */
enum class BlurbOutcome {
    /** Off, or no key: nothing was sent. */
    SKIPPED,
    WRITTEN,

    /** Recorded for Settings; trying again won't help (a refused key, a refusal). */
    FAILED,

    /** Recorded for Settings; WorkManager tries again later (busy, no credit, offline). */
    RETRY,
}

/**
 * The night's AI blurbs (M7-M11), for [BlurbWorker]: the picks to write for, one request,
 * and the blurbs saved against the reasons they were written for (M9).
 *
 * - **Nothing is sent** unless Settings › AI blurbs is on and a key is stored (M7).
 * - **What's sent** is [BlurbPicks]' (M8): Tonight's Pick's and the first three of each
 *   lens that's on, with the title's details and why it was picked.
 * - **Each blurb** is saved with its pick's reason; the home shows it only while the pick
 *   still has that reason (M9). Blurbs over [KEEP_DAYS] old are pruned at each run.
 * - **Never throws** into the app: every failure is recorded for Settings' last-run line,
 *   and logged by its class only, so the key can't reach a log.
 */
class BlurbWriter(
    private val settings: Flow<Settings>,
    private val vault: AiKeyVault,
    private val author: BlurbAuthor,
    private val runs: BlurbRuns,
    private val suggestions: SuggestionDao,
    private val plex: PlexDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) {
    suspend fun write(): BlurbOutcome = try {
        writeOrThrow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The class only: an exception's message could quote a header, and the key is one.
        log("ai: failed: ${e.javaClass.simpleName}")
        record(BlurbRun(clock(), error = SOMETHING_WENT_WRONG))
        BlurbOutcome.FAILED
    }

    private suspend fun writeOrThrow(): BlurbOutcome {
        val now = clock()
        val current = settings.first()
        if (!current.aiBlurbs) return BlurbOutcome.SKIPPED
        suggestions.pruneBlurbs(now - KEEP_DAYS * DAY_MILLIS)
        val key = vault.key() ?: return BlurbOutcome.SKIPPED
        val picks = suggestions.picks(Profiles.OWNER).first()
        val targets = BlurbPicks.choose(picks, current.enabledLenses, titlesFor(picks))
        if (targets.isEmpty()) {
            record(BlurbRun(now, error = NO_PICKS))
            return BlurbOutcome.FAILED
        }
        return when (val answer = author.write(key, targets.map { it.sent })) {
            is BlurbAnswer.Written -> {
                val byId = targets.associateBy { it.sent.id }
                val rows = answer.blurbs.mapNotNull { (id, text) ->
                    byId[id]?.let { BlurbEntity(Profiles.OWNER, it.ratingKey, it.reason, text, now) }
                }
                suggestions.saveBlurbs(rows)
                record(BlurbRun(now, rows.size, answer.usage))
                log("ai: wrote ${rows.size} of ${targets.size} blurbs, ${answer.usage.input} tokens in, ${answer.usage.output} out")
                BlurbOutcome.WRITTEN
            }
            is BlurbAnswer.Failed -> {
                record(BlurbRun(now, error = answer.reason))
                log("ai: ${answer.reason}")
                if (answer.retry) BlurbOutcome.RETRY else BlurbOutcome.FAILED
            }
        }
    }

    /** Settings' line is a nice-to-have: failing to save it never fails the run. */
    private suspend fun record(run: BlurbRun) {
        try {
            runs.save(run)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("ai: couldn't record the run: ${e.javaClass.simpleName}")
        }
    }

    /** The titles [picks] name, plus the shows of those that are episodes. */
    private suspend fun titlesFor(picks: List<PickEntity>): Map<String, ItemEntity> {
        val named = picks.map { it.ratingKey }.distinct().chunked(MAX_KEYS).flatMap { plex.items(it) }
        val shows = (named.mapNotNull { it.showKey }.toSet() - named.map { it.ratingKey }.toSet()).toList()
            .chunked(MAX_KEYS).flatMap { plex.items(it) }
        return (named + shows).associateBy { it.ratingKey }
    }

    companion object {
        const val LOG_TAG = "ActionCenterAi"

        /** A blurb is kept this long, in case its pick comes back with the same reason (M9). */
        const val KEEP_DAYS = 7L
        private const val DAY_MILLIS = 24 * 60 * 60_000L

        /** SQLite on Android 11 allows 999 arguments in a query. */
        private const val MAX_KEYS = 500

        const val NO_PICKS = "No picks to write for yet. They come once Plex has synced."
        const val SOMETHING_WENT_WRONG = "Something went wrong writing them. The picks keep their own reasons."
    }
}
