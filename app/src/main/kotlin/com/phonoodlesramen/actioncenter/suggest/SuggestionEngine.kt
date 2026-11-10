package com.phonoodlesramen.actioncenter.suggest

import android.util.Log
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensMoveOn
import com.phonoodlesramen.actioncenter.plex.cache.SuggestLibraryDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The suggestion engine (Phase 3): one read of the Plex cache and your feedback, then every
 * card's picks into `suggestions.db` at once. It runs after each Plex sync, after feedback,
 * and when the home sees a new day; never on the UI path. A run that fails keeps the last
 * picks and logs why (under `ActionCenterSuggest`), so the home never sees half of one.
 *
 * Each kid has their own run (Phase 5, K2-K4): the same library, seen through
 * [KidLibrary] (their ceiling, their plays and Continue Watching), and their feedback. It
 * fills every lens, as yours does (Phase 6, R1): Settings › Lenses picks which the card
 * shows. Their picks go under their id; yours never change for it.
 */
class SuggestionEngine(
    private val library: SuggestLibraryDao,
    private val dao: SuggestionDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
    /** The kids' ceilings, plays and Continue Watching (Phase 5): their runs read these. */
    private val kids: KidSource = KidSource.None,
    /** Settings › Lenses › Move on from a title (L5): days on the lens card before a title rests; null never. */
    private val moveOnDays: suspend () -> Int? = { LensMoveOn.THREE_DAYS.days },
) {
    private val running = Mutex()

    /** One run at a time: a sync and a Not tonight can ask together. [profile] is you, or a kid. */
    suspend fun run(profile: String = Profiles.OWNER) = running.withLock { runOnce(profile) }

    /**
     * Every kid's run, after yours (Phase 5): one read of the library for all of them. A
     * removed kid's picks go. Never throws.
     */
    suspend fun runKids() = running.withLock {
        try {
            val all = kids.kids(today())
            dao.keepPicksOf(listOf(Profiles.OWNER) + all.map { it.id })
            if (all.isEmpty()) return@withLock
            val shared = Library.read(library)
            all.forEach { runKid(it, shared) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("suggest: kids failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
        }
    }

    /**
     * Runs when the last run was for another day, or never: Throwback and Seasonal follow the
     * date. It checks inside the lock, so the home's start and resume asking together run it
     * once. True when it ran.
     */
    suspend fun runIfNewDay(profile: String = Profiles.OWNER): Boolean = running.withLock {
        if (dao.run(profile)?.day == day(clock())) return@withLock false
        runOnce(profile)
        true
    }

    private suspend fun runOnce(profile: String) {
        if (profile != Profiles.OWNER) return runKid(profile)
        val started = clock()
        try {
            val read = Library.read(library)
            // Nothing synced yet: the cards keep Phase 2's rules until there is.
            if (read.titles.isEmpty() && read.continueWatching.isEmpty()) return
            rest(profile, read, started)
            val feedback = dao.feedback(profile)
            val rows = withContext(compute) { picks(read, feedback, started, zone(), profile) }
            dao.replacePicks(profile, rows)
            val took = clock() - started
            dao.saveRun(RunEntity(profile, started, day(started), took))
            val bySurface = rows.groupingBy { it.surface.substringBefore(':') }.eachCount()
            log(
                "suggest: ran in $took ms: ${bySurface[Surfaces.HERO] ?: 0} picks, ${bySurface["lens"] ?: 0} on lenses, " +
                    "${bySurface[Surfaces.QUICK_WATCH] ?: 0} for Quick Watch, ${bySurface[Surfaces.UP_NEXT] ?: 0} in Up Next, " +
                    "${bySurface["more"] ?: 0} more like; ${read.titles.size} titles, ${feedback.size} feedback",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("suggest: failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            runCatching { dao.saveRun(RunEntity(profile, started, day(started), clock() - started, e.javaClass.simpleName)) }
        }
    }

    /** [profile]'s run, while they're a kid: a removed kid has none. */
    private suspend fun runKid(profile: String) {
        val all = try {
            kids.kids(today())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return log("suggest: a kid's run failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
        }
        runKid(all.firstOrNull { it.id == profile } ?: return, shared = null)
    }

    /**
     * A kid's run (Phase 5): the library [shared] (read now when null) as [KidLibrary] sees
     * it for them, and their feedback, filling every lens (R1). Like yours, a run that fails
     * keeps their last picks. The log line has counts only, never who.
     */
    private suspend fun runKid(kid: KidView, shared: Library?) {
        val started = clock()
        try {
            val read = shared ?: Library.read(library)
            if (read.titles.isEmpty() && kid.continueWatching.isEmpty()) return
            val theirs = KidLibrary.of(read, kid)
            rest(kid.id, theirs, started)
            val feedback = dao.feedback(kid.id)
            val rows = withContext(compute) { picks(theirs, feedback, started, zone(), kid.id) }
            dao.replacePicks(kid.id, rows)
            val took = clock() - started
            dao.saveRun(RunEntity(kid.id, started, day(started), took))
            val bySurface = rows.groupingBy { it.surface.substringBefore(':') }.eachCount()
            log(
                "suggest: a kid's run in $took ms: ${bySurface[Surfaces.HERO] ?: 0} picks, ${bySurface["lens"] ?: 0} on lenses, " +
                    "${bySurface[Surfaces.UP_NEXT] ?: 0} in Up Next; ${theirs.titles.size} of ${read.titles.size} titles within " +
                    "their ceiling, ${theirs.plays.size} plays, ${feedback.size} feedback",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("suggest: a kid's run failed: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            runCatching { dao.saveRun(RunEntity(kid.id, started, day(started), clock() - started, e.javaClass.simpleName)) }
        }
    }

    /** L5: titles [profile] has passed over on the lens card start resting, before the picks are made from [library] (their plays). */
    private suspend fun rest(profile: String, library: Library, now: Long) {
        val rests = LensFreshness.rests(dao.shown(profile), moveOnDays(), library, profile, now, zone())
        if (rests.isEmpty()) return
        dao.rest(profile, rests)
        log("suggest: ${rests.size} passed over on the lens card now rest")
    }

    private fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate().toString()

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()

    companion object {
        const val LOG_TAG = "ActionCenterSuggest"

        /** Every card's picks for one run, in order, filling [lenses] (every lens, for you and for a kid). Pure. */
        fun picks(
            library: Library,
            feedback: List<FeedbackEntity>,
            now: Long,
            zone: ZoneId,
            profile: String,
            lenses: Collection<Lens> = Lens.entries,
        ): List<PickEntity> {
            val hides = FeedbackRules.hides(feedback, now) { library.lastViewed(it) }
            val signals = Signals.from(library, feedback, now)
            val taste = Taste.from(library, signals, now)
            val base = RunInput(library, signals, hides, taste, now, zone)
            val input = RunInput(library, signals, hides, taste, now, zone, reason = { Reasons.line(it, taste, base) })
            val hero = TonightsPick.pick(input, taste)
            val surfaces = buildMap<String, List<Picked>> {
                put(Surfaces.HERO, hero)
                LensRules.picks(input, lenses).forEach { (lens, picks) -> put(Surfaces.lens(lens), picks) }
                put(Surfaces.QUICK_WATCH, QuickWatch.candidates(input))
                put(Surfaces.UP_NEXT, UpNextRank.rank(input))
                MoreLikeThis.forPicks(hero, input, taste).forEach { (key, picks) -> put(Surfaces.moreLike(key), picks) }
            }
            return surfaces.flatMap { (surface, picks) ->
                picks.mapIndexed { rank, picked ->
                    PickEntity(
                        profile, surface, rank, playable(picked.ratingKey, library), picked.reason, picked.evidence.joinToString("|"), now,
                        episodesLeft = picked.episodesLeft,
                    )
                }
            }
        }

        /**
         * What Play hands off for a pick: a show becomes its next episode (after the furthest
         * you've watched, else its first), so Plezy plays something rather than opening a page.
         */
        fun playable(ratingKey: String, library: Library): String {
            if (library.byKey[ratingKey]?.type != "show") return ratingKey
            val episodes = library.episodesOf(ratingKey)
                .filter { (it.season ?: 1) != 0 }
                .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
            val furthest = episodes.indexOfLast { it.viewCount > 0 }
            return (episodes.drop(furthest + 1).firstOrNull { it.viewCount == 0 } ?: episodes.firstOrNull())?.ratingKey ?: ratingKey
        }
    }
}

/** What the home tells the engine (S1, S10), so the view model needs no database. Defaults do nothing. */
interface SuggestionFeedback {
    /** Feedback on a title (a movie, or a show), then a run; returns how to take it back. */
    suspend fun record(titleKey: String, kind: FeedbackKind, scope: String = ""): FeedbackUndo? = null

    suspend fun undo(undo: FeedbackUndo) {}

    /** The home is back on screen: a new day runs the engine again. */
    suspend fun refresh() {}

    /** [titleKey] was on the lens card for a few seconds today (L5). No run: the days count from tomorrow's. */
    suspend fun lensShown(titleKey: String) {}

    companion object None : SuggestionFeedback
}

/**
 * [SuggestionFeedback] for the app: [FeedbackStore], then the engine runs with it. Feedback
 * is the [profile] on now's (Phase 5): a kid's Not tonight changes their picks, not yours.
 */
class EngineFeedback(
    private val store: FeedbackStore,
    private val engine: SuggestionEngine,
    private val profile: suspend () -> String = { Profiles.OWNER },
) : SuggestionFeedback {
    override suspend fun record(titleKey: String, kind: FeedbackKind, scope: String): FeedbackUndo {
        val who = profile()
        return store.record(titleKey, kind, scope, who).also { engine.run(who) }
    }

    override suspend fun undo(undo: FeedbackUndo) {
        store.undo(undo)
        engine.run(undo.profile)
    }

    override suspend fun lensShown(titleKey: String) = store.shown(titleKey, profile())

    /** Yours on a new day, as ever; then a kid's when one is on, so their "Remember when" follows the date too. */
    override suspend fun refresh() {
        if (engine.runIfNewDay()) store.prune()
        profile().takeIf { it != Profiles.OWNER }?.let { engine.runIfNewDay(it) }
    }
}
