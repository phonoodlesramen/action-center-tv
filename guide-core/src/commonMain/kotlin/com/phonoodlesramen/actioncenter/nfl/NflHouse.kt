package com.phonoodlesramen.actioncenter.nfl

import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * ESPN's part of [NflState], and when each piece was fetched: what the guide station keeps in
 * `nfl.json`, and what an unpaired TV keeps in its own copy. [seasonYear] is 0 until ESPN has
 * answered once.
 */
@Serializable
data class NflCopy(
    val updatedAtMillis: Long = 0,
    val phase: SeasonPhase = SeasonPhase.OFF,
    val seasonYear: Int = 0,
    val teams: List<NflTeam> = emptyList(),
    val teamsAtMillis: Long = 0,
    val week: NflWeek? = null,
    val calendar: List<NflWeekRef> = emptyList(),
    val standings: List<NflStanding> = emptyList(),
    val standingsSeason: Int = 0,
    val morningStandings: List<NflStanding> = emptyList(),
    /** The house's date [morningStandings] were taken on, "2026-11-01". */
    val morningDay: String? = null,
    val problem: String? = null,
) {
    /** What the TVs draw, with the house's [settings] and the [version] that covers both; null until ESPN has answered once. */
    fun state(settings: NflSettings, version: Long): NflState? = if (seasonYear == 0) {
        null
    } else {
        NflState(
            version = version, updatedAtMillis = updatedAtMillis, phase = phase, seasonYear = seasonYear, teams = teams, week = week,
            calendar = calendar, standings = standings, standingsSeason = standingsSeason, morningStandings = morningStandings,
            settings = settings, problem = problem,
        )
    }
}

/**
 * The house's NFL from ESPN (Phase 13, F11): one round of fetching and assembling ([refresh]), and
 * the gentle pace between rounds ([nextMillis], proposal F). The guide station runs it for the
 * house; an unpaired TV runs it for itself. Pure but for ESPN, on the caller's clock, in the
 * house's [zone].
 *
 * - **A round:** the scoreboard, then the standings and the teams when they're due: on the first
 *   round, after a game ends, at 4 AM house time (the morning's, [NflState.morningStandings], C),
 *   and monthly for the teams. In the preseason, last season's standings (D).
 * - **A failing ESPN** keeps what was fetched before the failure and says why ([NflCopy.problem]).
 */
class NflHouse(private val espn: EspnClient, private val zone: ZoneId) {
    /** One round from [before], at [now]. ESPN's failures end up in [NflCopy.problem]; nothing else is thrown but cancellation. */
    suspend fun refresh(before: NflCopy, now: Long): NflCopy {
        var next = before
        var problem: String? = null
        try {
            val board = espn.scoreboard()
            val phase = NflRules.phase(board.calendar, board.week, now, zone)
            next = next.copy(
                updatedAtMillis = now, phase = phase, seasonYear = board.seasonYear, calendar = board.calendar,
                week = board.week.takeIf { phase != SeasonPhase.OFF },
            )
            val season = NflRules.standingsSeason(next.calendar, next.seasonYear, now)
            val today = NflRules.localDate(now, zone)
            val morning = now >= today.atTime(MORNING_HOUR, 0).atZone(zone).toInstant().toEpochMilli() && next.morningDay != today.toString()
            val teamsDue = next.teams.isEmpty() || now - next.teamsAtMillis >= TEAMS_MILLIS
            if (next.standings.isEmpty() || next.standingsSeason != season || ended(before.week, next.week) || morning || teamsDue) {
                val standings = espn.standings(season.takeIf { it != next.seasonYear })
                next = next.copy(standings = standings.standings, standingsSeason = season)
                if (morning) {
                    // Started after today's games began, the morning it had is kept, rather than today's results.
                    val begun = next.week?.games.orEmpty().any { it.state != GameState.PRE && NflRules.localDate(it.kickoffMillis, zone) == today }
                    val keep = begun && next.morningStandings.isNotEmpty() && before.standingsSeason == season
                    next = next.copy(morningStandings = if (keep) next.morningStandings else standings.standings, morningDay = today.toString())
                } else if (next.morningStandings.isEmpty()) {
                    next = next.copy(morningStandings = standings.standings)
                }
                if (teamsDue) next = next.copy(teams = espn.teams(standings.divisions), teamsAtMillis = now)
            }
        } catch (e: EspnException) {
            problem = e.message
        }
        return next.copy(problem = problem)
    }

    /**
     * How long after a round the next is due, as of [now] (proposal F): every 30 s while a game is
     * on and someone is [watching]; every 15 minutes on game days; hourly on other days; at 4 AM in
     * the off-season, to notice the preseason. After a failure, within 5 minutes.
     */
    fun nextMillis(copy: NflCopy, now: Long, watching: Boolean): Long {
        val cadence = when {
            copy.seasonYear == 0 -> RETRY_MILLIS
            copy.phase == SeasonPhase.OFF -> untilMorning(now)
            underway(copy, now) && watching -> LIVE_MILLIS
            gameDay(copy.week, now) -> GAME_DAY_MILLIS
            else -> QUIET_MILLIS
        }
        return if (copy.problem != null) minOf(cadence, RETRY_MILLIS) else cadence
    }

    /** A game in [copy] is on, or past its kickoff and not final. */
    fun underway(copy: NflCopy, now: Long): Boolean = copy.week?.games.orEmpty().any { NflRules.underway(it, now) }

    /** A game in [after] that's final and wasn't in [before]: its standings have moved. */
    private fun ended(before: NflWeek?, after: NflWeek?): Boolean {
        val was = before?.games.orEmpty().associate { it.id to it.state }
        return after?.games.orEmpty().any { it.state == GameState.FINAL && was[it.id]?.let { state -> state != GameState.FINAL } == true }
    }

    /** A game kicks off today, or one's still on from last night. */
    private fun gameDay(week: NflWeek?, now: Long): Boolean {
        val today = NflRules.localDate(now, zone)
        return week?.games.orEmpty().any { (it.kickoffKnown && NflRules.localDate(it.kickoffMillis, zone) == today) || NflRules.underway(it, now) }
    }

    private fun untilMorning(now: Long): Long {
        val today = NflRules.localDate(now, zone)
        val morning = today.atTime(MORNING_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        return (if (now < morning) morning else today.plusDays(1).atTime(MORNING_HOUR, 0).atZone(zone).toInstant().toEpochMilli()) - now
    }

    companion object {
        const val LIVE_MILLIS = 30_000L
        const val GAME_DAY_MILLIS = 15 * 60_000L
        const val QUIET_MILLIS = 60 * 60_000L

        /** After a failed round (or before the first good one), ESPN is tried again this soon. */
        const val RETRY_MILLIS = 5 * 60_000L

        /** The house's hour for the morning standings, and the off-season's daily look. */
        const val MORNING_HOUR = 4

        const val TEAMS_MILLIS = 30 * 24 * 60 * 60_000L
    }
}
