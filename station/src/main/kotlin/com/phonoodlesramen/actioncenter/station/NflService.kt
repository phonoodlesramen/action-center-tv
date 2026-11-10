package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.nfl.EspnClient
import com.phonoodlesramen.actioncenter.nfl.EspnException
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflCopy
import com.phonoodlesramen.actioncenter.nfl.NflGameDetail
import com.phonoodlesramen.actioncenter.nfl.NflHouse
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.NflState
import com.phonoodlesramen.actioncenter.nfl.NflTeamSchedule
import com.phonoodlesramen.actioncenter.nfl.NflWeek
import com.phonoodlesramen.actioncenter.safety.summary
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer

/**
 * The NFL for the house (Phase 13, F11): ESPN's scoreboard and standings, fetched by the station
 * on a gentle schedule (proposal F) and kept in the volume (`nfl.json`), so a TV gets what's known
 * at once, however slow or broken ESPN is.
 *
 * - **Off**: while the house's NFL is off ([NflSettings.showing]), the poller doesn't ask
 *   ESPN at all; turning it on wakes it at once.
 * - **The poller** ([run]): [NflHouse]'s rounds and pace, which an unpaired TV shares. Every 30 s
 *   while a game is on (or past its kickoff and not final) and a TV called `/v1/nfl…` in the last 3 minutes; every 15 minutes on game days otherwise; hourly
 *   on other days; once a day at 4 AM in the off-season, to notice the preseason. A failed poll is
 *   tried again within 5 minutes.
 * - **Standings** after a game ends and at 4 AM house time; the first fetch from 4 AM is the
 *   morning's ([NflState.morningStandings], C). In the preseason, last season's (D).
 * - **A failing ESPN** keeps the copy and says why ([NflState.problem]).
 * - **Its own version** (`nfl-version`): `GET /v1/nfl`'s `ETag` and what `GET /v1/nfl/changes`
 *   waits on. It moves only when the copy or the settings change, never for the clock alone, so
 *   a TV that hears nothing new knows its copy is current.
 * - **On demand, cached:** another week (a finished one for good, else 15 minutes), a team's
 *   schedule (15 minutes), a game's details (30 s while it's on, for good once final), and logos
 *   (once per team and size, in `nfl-logos/`, again after a month).
 */
class NflService(
    private val espn: EspnClient,
    stateDir: Path,
    private val logoDir: Path,
    private val versions: Versions,
    private val clock: () -> Long,
    private val zone: ZoneId,
    private val log: (String) -> Unit,
) {
    /**
     * The copy (what `nfl.json` keeps: ESPN's part of [NflState], and when each piece was fetched),
     * the house's settings and the version that covers both, swapped together under [swap].
     */
    private data class Snapshot(val copy: NflCopy, val settings: NflSettings, val version: Long)

    private val copyFile = stateDir.resolve("nfl.json")
    private val settingsFile = stateDir.resolve("nfl-settings.json")
    private val swap = Any()
    private var snapshot = Snapshot(read(copyFile, NflCopy.serializer()) ?: NflCopy(), read(settingsFile, NflSettings.serializer()) ?: NflSettings(), versions.current)
    private val house = NflHouse(espn, zone)

    private val polling = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var askedAtMillis: Long? = null

    private class Cached<T>(val value: T, val untilMillis: Long)

    private val weeks = ConcurrentHashMap<String, Cached<NflWeek>>()
    private val schedules = ConcurrentHashMap<String, Cached<NflTeamSchedule>>()
    private val games = ConcurrentHashMap<String, Cached<NflGameDetail>>()
    private val logoLocks = ConcurrentHashMap<String, Mutex>()

    /** `GET /v1/nfl`; null until ESPN has answered once. */
    fun state(): NflState? = synchronized(swap) { snapshot.copy.state(snapshot.settings, snapshot.version) }

    /** Why the last poll failed, while it keeps failing. */
    val problem: String? get() = current().problem

    fun settings(): NflSettings = synchronized(swap) { snapshot.settings }

    /** The house's new settings (B). Every team must be one ESPN lists. Returns the version. */
    fun putSettings(settings: NflSettings): Long {
        val known = current().teams.map { it.abbr }.toSet()
        fun teams(list: List<String>): List<String> {
            val abbrs = list.map { it.trim().uppercase() }.distinct()
            if (abbrs.size > TEAM_COUNT) throw ApiException(400, "That's more teams than the NFL has.")
            abbrs.firstOrNull { !TEAM.matches(it) || (known.isNotEmpty() && it !in known) }?.let {
                throw ApiException(400, "\"${it.take(10)}\" isn't an NFL team.")
            }
            return abbrs
        }
        val next = settings.copy(yourTeams = teams(settings.yourTeams), areaTeams = teams(settings.areaTeams))
        val version = synchronized(swap) {
            if (next == snapshot.settings) return snapshot.version
            SecretFiles.writeJson(settingsFile, NflSettings.serializer(), next)
            snapshot = snapshot.copy(settings = next, version = versions.bump())
            snapshot.version
        }
        // Turned on: the poller, waiting while it was off, asks ESPN now.
        if (next.showing) wake.trySend(Unit)
        return version
    }

    /** Whether the poller may ask ESPN: the house's NFL is on. */
    fun polling(): Boolean = settings().showing

    /** A TV called `/v1/nfl…`. While a game is on, the poller goes fast, at once if it was waiting longer. */
    fun asked() {
        val now = clock()
        val watching = askedAtMillis?.let { now - it < WATCHING_MILLIS } == true
        askedAtMillis = now
        if (!watching && house.underway(current(), now)) wake.trySend(Unit)
    }

    /**
     * Polls until cancelled (the station's scope). A failure is logged, and the schedule goes on.
     * The pace runs from each poll's start, so a TV asking while ESPN is still answering doesn't
     * put the next poll back.
     */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            if (!polling()) {
                // The house's NFL is off: nothing asks ESPN until it's turned on (putSettings wakes it).
                wake.receive()
                continue
            }
            val polledAt = clock()
            try {
                poll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("nfl: ${e.summary()}")
            }
            while (true) {
                val wait = polledAt + nextPollMillis() - clock()
                if (wait <= 0) break
                // A TV asking during a game wakes it early; the wait is worked out again.
                withTimeoutOrNull(wait) { wake.receive() } ?: break
            }
        }
    }

    /** One round ([NflHouse.refresh]): the scoreboard, then the standings and the teams when they're due. ESPN's failures end up in [problem]. */
    suspend fun poll(): Unit = polling.withLock {
        val before = current()
        commit(house.refresh(before, clock()), before)
    }

    /** How long after a poll the next is due, as of [now] (proposal F): fast only while a TV asked in the last 3 minutes. */
    fun nextPollMillis(now: Long = clock()): Long {
        val asked = askedAtMillis
        return house.nextMillis(current(), now, watching = asked != null && now - asked < WATCHING_MILLIS)
    }

    /** `GET /v1/nfl/week/{type}/{week}`: this season's, or [seasonYear]'s. */
    suspend fun week(seasonType: Int, week: Int, seasonYear: Int?): NflWeek {
        if (seasonType !in 1..3 || week !in 1..MAX_WEEK) throw ApiException(404, NO_WEEK)
        val copy = current()
        val year = seasonYear ?: copy.seasonYear.takeIf { it > 0 } ?: throw ApiException(503, NOT_YET)
        if (year !in MIN_YEAR..MAX_YEAR) throw ApiException(404, NO_WEEK)
        copy.week?.takeIf { it.seasonYear == year && it.seasonType == seasonType && it.week == week }?.let { return it }
        return cached(weeks, "$year/$seasonType/$week", NO_WEEK) {
            val found = espn.scoreboard(year, seasonType, week).week?.takeIf { it.games.isNotEmpty() } ?: throw ApiException(404, NO_WEEK)
            val over = found.games.all { it.state == GameState.FINAL }
            Cached(found, if (over) FOR_GOOD else clock() + WEEK_MILLIS)
        }
    }

    /** `GET /v1/nfl/teams/{abbr}/schedule`: this season's regular season, or another. */
    suspend fun schedule(abbr: String, seasonYear: Int?, seasonType: Int?): NflTeamSchedule {
        val team = team(abbr)
        val year = seasonYear ?: current().seasonYear.takeIf { it > 0 } ?: throw ApiException(503, NOT_YET)
        val type = seasonType ?: REGULAR
        if (year !in MIN_YEAR..MAX_YEAR || type !in 1..3) throw ApiException(404, "ESPN has no schedule for $team then.")
        return cached(schedules, "$team/$year/$type", "ESPN has no schedule for $team then.") {
            Cached(espn.schedule(team, year, type), clock() + SCHEDULE_MILLIS)
        }
    }

    /** `GET /v1/nfl/games/{id}`. */
    suspend fun game(id: String): NflGameDetail {
        if (!GAME_ID.matches(id)) throw ApiException(404, NO_GAME)
        return cached(games, id, NO_GAME) {
            val detail = espn.summary(id)
            val until = when (detail.game.state) {
                GameState.FINAL -> FOR_GOOD
                GameState.LIVE -> clock() + GAME_LIVE_MILLIS
                GameState.PRE -> clock() + GAME_PRE_MILLIS
            }
            Cached(detail, until)
        }
    }

    /** `GET /v1/nfl/logos/{abbr}?size=s|l`: a PNG, from `nfl-logos/`, fetched once a month at most. */
    suspend fun logo(abbr: String, size: String): ByteArray {
        val team = team(abbr)
        val px = LOGO_SIZES[size] ?: throw ApiException(400, "A logo's size is s or l.")
        val file = logoDir.resolve("$team-$size.png")
        return logoLocks.computeIfAbsent(file.toString()) { Mutex() }.withLock {
            val saved = SecretFiles.read(file)
            if (saved != null && clock() - Files.getLastModifiedTime(file).toMillis() < LOGO_MILLIS) return@withLock saved
            try {
                espn.logo(team, px).also { SecretFiles.write(file, it) }
            } catch (e: EspnException) {
                saved ?: throw ApiException(502, e.message ?: CANT_REACH)
            }
        }
    }

    private fun current(): NflCopy = synchronized(swap) { snapshot.copy }

    /** Keeps [next], with a new version only if something in it differs (not just when it was fetched). */
    private fun commit(next: NflCopy, before: NflCopy) {
        synchronized(swap) {
            val changed = next.copy(updatedAtMillis = 0) != snapshot.copy.copy(updatedAtMillis = 0)
            snapshot = snapshot.copy(copy = next, version = if (changed) versions.bump() else snapshot.version)
            if (changed) SecretFiles.writeJson(copyFile, NflCopy.serializer(), next)
        }
        if (next.problem != before.problem) log(if (next.problem == null) "nfl: ESPN answers again" else "nfl: ${next.problem}")
    }

    /** [abbr] as ESPN writes it, if it's a team (one ESPN lists, once the teams are in). */
    private fun team(abbr: String): String {
        val team = abbr.uppercase()
        val known = current().teams
        if (!TEAM.matches(team) || (known.isNotEmpty() && known.none { it.abbr == team })) throw ApiException(404, "No NFL team goes by \"${abbr.take(10)}\".")
        return team
    }

    /** The cached answer while it's good; else ESPN's, or the old one when ESPN fails, or an error the TV shows. */
    private suspend fun <T> cached(map: ConcurrentHashMap<String, Cached<T>>, key: String, missing: String, fetch: suspend () -> Cached<T>): T {
        val held = map[key]
        if (held != null && clock() < held.untilMillis) return held.value
        return try {
            fetch().also { map[key] = it }.value
        } catch (e: EspnException) {
            held?.value ?: throw if (e.code == 400 || e.code == 404) ApiException(404, missing) else ApiException(502, e.message ?: CANT_REACH)
        }
    }

    /** A file that won't read starts over, rather than stopping the station. */
    private fun <T> read(file: Path, serializer: KSerializer<T>): T? = try {
        SecretFiles.readJson(file, serializer)
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        const val LIVE_MILLIS = NflHouse.LIVE_MILLIS
        const val GAME_DAY_MILLIS = NflHouse.GAME_DAY_MILLIS
        const val QUIET_MILLIS = NflHouse.QUIET_MILLIS

        /** A TV that asked within this long keeps the poller fast while a game is on. */
        const val WATCHING_MILLIS = 3 * 60_000L

        /** After a failed poll (or before the first good one), ESPN is tried again this soon. */
        const val RETRY_MILLIS = NflHouse.RETRY_MILLIS

        const val WEEK_MILLIS = 15 * 60_000L
        const val SCHEDULE_MILLIS = 15 * 60_000L
        const val GAME_LIVE_MILLIS = 30_000L
        const val GAME_PRE_MILLIS = 15 * 60_000L
        const val LOGO_MILLIS = 30 * 24 * 60 * 60_000L

        /** `s` for the row and the guide, `l` for a game's details and a team's page. */
        val LOGO_SIZES = mapOf("s" to 64, "l" to 192)

        const val NOT_YET = "The guide station hasn't heard from ESPN yet. Try again in a moment."
        private const val NO_WEEK = "ESPN has no games that week."
        private const val NO_GAME = "ESPN has no game with that id."
        private const val CANT_REACH = "Can't reach ESPN."
        private const val FOR_GOOD = Long.MAX_VALUE
        private const val REGULAR = 2
        private const val MAX_WEEK = 25
        private const val MIN_YEAR = 2000
        private const val MAX_YEAR = 2100
        private const val TEAM_COUNT = 32
        private val TEAM = Regex("[A-Z]{2,3}")
        private val GAME_ID = Regex("[0-9]{1,15}")
    }
}
