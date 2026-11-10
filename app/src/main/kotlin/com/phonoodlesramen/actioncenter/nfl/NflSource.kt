package com.phonoodlesramen.actioncenter.nfl

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.safety.summary
import com.phonoodlesramen.actioncenter.station.NflReply
import com.phonoodlesramen.actioncenter.station.NflStation
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * What this TV keeps of the NFL (Phase 13), drawn at once: the station's state as last copied
 * (and its ETag, and when it was last known current), this TV's own copy of ESPN and settings for
 * while it's unpaired, and the two things only this TV remembers: the scores Show score showed
 * today, and Not today.
 */
@Serializable
data class NflSaved(
    val station: NflState? = null,
    val etag: String? = null,
    /** When [station] was copied; later checks that it's still current are kept in memory only. */
    val checkedAtMillis: Long = 0,
    /** Unpaired: ESPN's part of the state, as this TV made it ([NflHouse]). */
    val espn: NflCopy = NflCopy(),
    /** When this TV last asked ESPN, so a failing ESPN is asked again on the gentle pace, not at once. */
    val triedAtMillis: Long = 0,
    /** Unpaired: this TV's own NFL settings. */
    val settings: NflSettings = NflSettings(),
    /** Paired: the house's settings as this TV last saved them, until the station's copy carries them. */
    val houseSettings: NflSettings? = null,
    /** The house's date [shown] is for, "2026-11-01". */
    val shownDay: String? = null,
    val shown: Set<String> = emptySet(),
    /** The house's date Not today was chosen on. */
    val notToday: String? = null,
)

/** Where [NflSaved] lives. */
interface NflStore {
    val saved: Flow<NflSaved>

    /** Changes it atomically. */
    suspend fun update(change: (NflSaved) -> NflSaved)
}

/** [NflStore] as one JSON value in its own Preferences DataStore file. A file that can't be read reads as none. */
class DataStoreNfl(private val store: DataStore<Preferences>) : NflStore {
    override val saved: Flow<NflSaved> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { read(it[KEY]) }
        .distinctUntilChanged()

    override suspend fun update(change: (NflSaved) -> NflSaved) {
        store.edit { prefs -> prefs[KEY] = JSON.encodeToString(NflSaved.serializer(), change(read(prefs[KEY]))) }
    }

    private fun read(text: String?): NflSaved = try {
        text?.let { JSON.decodeFromString(NflSaved.serializer(), it) } ?: NflSaved()
    } catch (e: SerializationException) {
        NflSaved()
    } catch (e: IllegalArgumentException) {
        NflSaved()
    }

    private companion object {
        val KEY = stringPreferencesKey("nfl")
        val JSON = StationApi.json
    }
}

/** [NflStore] in memory: tests and previews. */
class MemoryNfl(initial: NflSaved = NflSaved()) : NflStore {
    private val value = MutableStateFlow(initial)
    override val saved: Flow<NflSaved> = value

    val current: NflSaved get() = value.value

    override suspend fun update(change: (NflSaved) -> NflSaved) = value.update(change)
}

/**
 * The NFL on this TV (Phase 13, F11), drawn at once from its copy ([view]).
 *
 * - **Paired:** the station's state. [keepUp] copies it (`GET /v1/nfl` with its ETag) and holds
 *   `nfl/changes` open while the home, or an NFL screen in it, is in front, so a score shows within
 *   a second of the station seeing it. Other weeks, schedules, game details and logos are the
 *   station's, and the settings are the house's (B).
 * - **A 1.1 station** answers 404: the NFL says the station needs an update.
 * - **Unpaired:** this TV makes the state itself from ESPN ([NflHouse]), gently: every 30 s while a
 *   game is on and the NFL is showing, every 15 minutes on game days, hourly otherwise. The
 *   settings are this TV's.
 * - **This TV's own:** Show score's games (until tomorrow) and Not today.
 *
 * Nothing here throws but cancellation.
 */
class NflSource(
    private val store: NflStore,
    private val station: NflStation,
    private val espn: EspnClient,
    private val zone: ZoneId,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timing: Timing = Timing(),
    private val log: (String) -> Unit = {},
) : NflController {
    /** How often things happen; tests shorten them. */
    data class Timing(
        /** The station's copy is checked at least this often while it's followed. */
        val refreshEveryMillis: Long = 5 * 60_000L,
        /** After a failure, the first wait before trying the station again; it doubles up to [retryMaxMillis]. */
        val retryMillis: Long = 15_000,
        val retryMaxMillis: Long = 5 * 60_000L,
        /** A 1.1 station is asked again this often, to notice its update. */
        val outdatedMillis: Long = 30 * 60_000L,
    )

    /**
     * What happened since the app started. [checkedAtMillis] is when the station last said the
     * copy is current (a `304`, or a `nfl/changes` wait that ran out): kept here, not written to
     * the file every minute.
     */
    private data class Runtime(val outdated: Boolean = false, val problem: String? = null, val checkedAtMillis: Long = 0)

    private val runtime = MutableStateFlow(Runtime())
    private val house = NflHouse(espn, zone)

    override val view: StateFlow<NflView> = combine(store.saved, station.paired, runtime) { saved, paired, rt -> viewOf(saved, paired, rt) }
        .stateIn(scope, SharingStarted.Eagerly, NflView(zone = zone))

    private fun viewOf(saved: NflSaved, paired: Boolean, rt: Runtime): NflView {
        val shownDay = saved.shownDay?.let(::dayOf)
        val notToday = saved.notToday?.let(::dayOf)
        return if (paired) {
            val state = saved.station
            NflView(
                state = state,
                from = NflFrom.STATION,
                asOfMillis = state?.let { if (it.problem != null) it.updatedAtMillis else maxOf(saved.checkedAtMillis, rt.checkedAtMillis) },
                outdated = rt.outdated,
                problem = rt.problem,
                shown = saved.shown, shownDay = shownDay, notTodayDay = notToday, zone = zone,
                ownSettings = saved.houseSettings.takeIf { state == null },
            )
        } else {
            val state = saved.espn.state(saved.settings, version = 0)
            NflView(
                state = state,
                from = NflFrom.ESPN,
                asOfMillis = state?.updatedAtMillis,
                problem = saved.espn.problem,
                shown = saved.shown, shownDay = shownDay, notTodayDay = notToday, zone = zone,
                // This TV's own settings, whether or not ESPN has answered: Show the NFL is one of them.
                ownSettings = saved.settings,
            )
        }
    }

    /**
     * While the home is in front: paired, the station's copy now and then its `nfl/changes`, only
     * while the NFL is [showing] (not on a kid's home), since each call keeps the station's poller
     * fast. That's whatever Show the NFL says, so a TV hears when another turns it on; the station
     * itself asks ESPN nothing while it's off. Unpaired, ESPN on the gentle pace, and only while
     * this TV's Show the NFL is on: off, nothing asks ESPN. Never throws but its own
     * cancellation.
     */
    suspend fun keepUp(showing: Flow<Boolean>) {
        val on = store.saved.map { it.settings.showing }.distinctUntilChanged()
        combine(station.paired, showing.distinctUntilChanged(), on, ::Triple).distinctUntilChanged().collectLatest { (paired, shown, nflOn) ->
            when {
                paired && shown -> followStation()
                paired -> Unit
                nflOn -> followEspn(watching = shown)
                else -> Unit
            }
        }
    }

    // The station.

    private suspend fun followStation() {
        var retry = timing.retryMillis
        while (true) {
            if (copyFromStation() && waitForChange()) {
                retry = timing.retryMillis
                continue
            }
            if (runtime.value.outdated) {
                delay(timing.outdatedMillis)
                continue
            }
            delay(retry)
            retry = (retry * 2).coerceAtMost(timing.retryMaxMillis)
        }
    }

    /** Copies the station's state if it's changed (ETag). True when the station answered with it. */
    suspend fun copyFromStation(): Boolean = try {
        val saved = store.saved.first()
        when (val reply = station.nfl(saved.etag.takeIf { saved.station != null })) {
            is NflReply.Answered -> {
                val now = clock()
                val answer = reply.value
                // A new copy is kept; a 304 only says the one kept is current.
                if (answer != null) {
                    store.update { it.copy(station = answer.first, etag = answer.second, checkedAtMillis = now) }
                    log("nfl: copied version ${answer.first.version}")
                }
                runtime.value = Runtime(checkedAtMillis = now)
                true
            }
            NflReply.Outdated -> {
                if (!runtime.value.outdated) log("nfl: the station has no NFL (an older station)")
                runtime.update { Runtime(outdated = true, checkedAtMillis = it.checkedAtMillis) }
                false
            }
            is NflReply.Failed -> {
                runtime.update { Runtime(problem = reply.sentence, checkedAtMillis = it.checkedAtMillis) }
                false
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("nfl: not copied: ${e.summary()}")
        false
    }

    /** True on a change, or when it's time to look anyway; false when the station couldn't be asked. */
    private suspend fun waitForChange(): Boolean {
        val until = clock() + timing.refreshEveryMillis
        while (clock() < until) {
            val known = store.saved.first().station?.version ?: 0L
            when (val reply = station.nflChanges(known)) {
                is NflReply.Answered -> {
                    if (reply.value != null && reply.value != known) return true
                    // The wait ran out with no change: the copy is current as of now.
                    val now = clock()
                    runtime.update { it.copy(checkedAtMillis = now) }
                }
                NflReply.Outdated -> {
                    runtime.update { Runtime(outdated = true, checkedAtMillis = it.checkedAtMillis) }
                    return false
                }
                is NflReply.Failed -> {
                    runtime.update { Runtime(problem = reply.sentence, checkedAtMillis = it.checkedAtMillis) }
                    return false
                }
            }
        }
        return true
    }

    // ESPN, unpaired.

    private suspend fun followEspn(watching: Boolean) {
        while (true) {
            val saved = store.saved.first()
            val now = clock()
            val due = dueAt(saved, now, watching)
            if (now >= due) roundOfEspn() else delay(due - now)
        }
    }

    /** When the next round is due: [NflHouse]'s pace from the last one, and at once once a game it knew of kicks off. */
    private fun dueAt(saved: NflSaved, now: Long, watching: Boolean): Long {
        val tried = saved.triedAtMillis
        val paced = tried + house.nextMillis(saved.espn, tried, watching)
        val kickedOff = watching && house.underway(saved.espn, now) && !house.underway(saved.espn, tried)
        return if (kickedOff) minOf(paced, tried + NflHouse.LIVE_MILLIS) else paced
    }

    /** One round from ESPN into this TV's copy. */
    suspend fun roundOfEspn() {
        try {
            val now = clock()
            val before = store.saved.first().espn
            val next = house.refresh(before, now)
            store.update { it.copy(espn = next, triedAtMillis = now) }
            if (next.problem != before.problem) log(if (next.problem == null) "nfl: ESPN answers again" else "nfl: ${next.problem}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("nfl: ESPN not asked: ${e.summary()}")
            val now = clock()
            store.update { it.copy(triedAtMillis = now) }
        }
    }

    // What the screens ask for.

    override fun saveSettings(settings: NflSettings, done: (String?) -> Unit) {
        scope.launch { done(save(settings)) }
    }

    /** The house's settings (paired, B) or this TV's: null once kept, else the sentence to show. */
    suspend fun save(settings: NflSettings): String? = try {
        if (station.paired.first()) {
            when (val reply = station.saveNflSettings(settings)) {
                is NflReply.Answered -> {
                    // Shown at once; the station's copy follows with the new version.
                    store.update { it.copy(station = it.station?.copy(settings = settings), houseSettings = settings) }
                    copyFromStation()
                    null
                }
                NflReply.Outdated -> {
                    runtime.update { Runtime(outdated = true, checkedAtMillis = it.checkedAtMillis) }
                    OUTDATED
                }
                is NflReply.Failed -> reply.sentence
            }
        } else {
            store.update { it.copy(settings = settings) }
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("nfl: settings not kept: ${e.summary()}")
        CANT
    }

    override fun showScore(gameId: String) {
        scope.launch { show(gameId) }
    }

    suspend fun show(gameId: String) {
        val today = today()
        store.update { saved ->
            val shown = if (saved.shownDay == today) saved.shown else emptySet()
            saved.copy(shownDay = today, shown = shown + gameId)
        }
    }

    override fun notToday() {
        scope.launch { putAwayToday() }
    }

    suspend fun putAwayToday() {
        val today = today()
        store.update { it.copy(notToday = today) }
    }

    override suspend fun week(seasonType: Int, week: Int, seasonYear: Int): NflLoad<NflWeek> = load(
        paired = { station.nflWeek(seasonType, week, seasonYear) },
        espn = { espn.scoreboard(seasonYear, seasonType, week).week?.takeIf { it.games.isNotEmpty() } ?: throw EspnException(NO_WEEK) },
    )

    override suspend fun schedule(abbr: String, seasonYear: Int): NflLoad<NflTeamSchedule> = load(
        paired = { station.nflSchedule(abbr, seasonYear) },
        espn = { espn.schedule(abbr, seasonYear, REGULAR) },
    )

    override suspend fun game(id: String): NflLoad<NflGameDetail> = load(paired = { station.nflGame(id) }, espn = { espn.summary(id) })

    private suspend fun <T> load(paired: suspend () -> NflReply<T>, espn: suspend () -> T): NflLoad<T> = try {
        if (station.paired.first()) {
            when (val reply = paired()) {
                is NflReply.Answered -> NflLoad.Loaded(reply.value)
                NflReply.Outdated -> NflLoad.Failed(OUTDATED)
                is NflReply.Failed -> NflLoad.Failed(reply.sentence)
            }
        } else {
            NflLoad.Loaded(espn())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: EspnException) {
        NflLoad.Failed(e.message ?: CANT_REACH_ESPN)
    } catch (e: Exception) {
        log("nfl: not loaded: ${e.summary()}")
        NflLoad.Failed(CANT)
    }

    private fun today(): String = NflRules.localDate(clock(), zone).toString()

    private fun dayOf(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    companion object {
        /** A 1.1 station: Settings › NFL and the NFL screen say so. */
        const val OUTDATED = "The guide station needs an update for the NFL"
        private const val CANT = "That didn't work. Try again."
        private const val CANT_REACH_ESPN = "Can't reach ESPN."
        private const val NO_WEEK = "ESPN has no games that week."
        private const val REGULAR = 2
    }
}
