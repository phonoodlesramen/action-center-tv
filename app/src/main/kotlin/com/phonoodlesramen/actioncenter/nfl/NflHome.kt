package com.phonoodlesramen.actioncenter.nfl

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The NFL row (F1, F3): its label, then All games, the games in [NflRules.rowOrder] up to the
 * finals, your division, then the finals. [first] puts it first below the dashboard (game days,
 * from an hour before the first kickoff), else it follows Live.
 */
@Immutable
data class NflRowModel(
    /** "NFL · Week 8 · 1 live", "NFL · Preseason Week 2", "NFL · Wild Card". */
    val label: String,
    val week: String,
    val live: Int,
    /** Live games (yours first) and the next kickoffs. */
    val games: List<NflGame>,
    /** Your first team's division, leader first; null with no team set. */
    val division: NflDivisionCard?,
    /** The week's finals, newest first. */
    val finals: List<NflGame>,
    val first: Boolean,
)

/** The row's division card: "NFC East" and its four teams with their records, leader first. */
@Immutable
data class NflDivisionCard(val name: String, val teams: List<NflStanding>)

/** What the home shows of the NFL (Phase 13, F1, F2, F8, F9). Pure. */
object NflHome {
    /**
     * The game-day card's game (F1, F2): from 30 minutes before a kickoff until the day's last game
     * ends ([NflRules.cardUp]), [NflRules.featured]. None on a kid's home (F9), while Show the NFL
     * is off, in the off-season (F8), or after Not today until tomorrow.
     */
    fun card(view: NflView, nowMillis: Long, kid: Boolean): NflGame? {
        val state = view.state ?: return null
        if (kid || !view.showing || state.phase == SeasonPhase.OFF) return null
        if (view.notTodayDay == view.today(nowMillis)) return null
        if (!NflRules.cardUp(state.week, nowMillis, view.zone)) return null
        return NflRules.featured(state.week, state.settings.yourTeams, nowMillis)
    }

    /** The NFL row, or null: nothing yet, Show the NFL off, the off-season (F8), no games this week, or a kid's home (F9). */
    fun row(view: NflView, nowMillis: Long, kid: Boolean): NflRowModel? {
        val state = view.state ?: return null
        val week = state.week ?: return null
        if (kid || !view.showing || state.phase == SeasonPhase.OFF || week.games.isEmpty()) return null
        val ordered = NflRules.rowOrder(week, state.settings.yourTeams)
        val live = ordered.count { it.state == GameState.LIVE }
        val label = listOfNotNull("NFL", week.label, if (live > 0) "$live live" else null).joinToString(" · ")
        return NflRowModel(
            label = label,
            week = week.label,
            live = live,
            games = ordered.filter { it.state != GameState.FINAL },
            division = division(view, state, nowMillis),
            finals = ordered.filter { it.state == GameState.FINAL },
            first = NflRules.rowFirst(week, nowMillis, view.zone),
        )
    }

    /** Your first team's division, from the standings drawn now (this morning's while scores are hidden). */
    private fun division(view: NflView, state: NflState, nowMillis: Long): NflDivisionCard? {
        val mine = state.settings.yourTeams.firstNotNullOfOrNull { abbr -> state.teams.firstOrNull { it.abbr == abbr } } ?: return null
        val standings = view.standings(nowMillis)
        val members = state.teams.filter { it.divisionName == mine.divisionName }.map { it.abbr }.toSet()
        val teams = standings.filter { it.abbr in members }.sortedBy { it.divisionRank }
        return if (teams.isEmpty()) null else NflDivisionCard(mine.divisionName, teams)
    }
}

/** How the NFL reads on the TV (Phase 13). Pure; times are the house's [ZoneId]. */
object NflText {
    /** "1st", "2nd", "3rd", "4th", "OT". */
    fun quarter(period: Int?): String = when (period) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        4 -> "4th"
        null, 0 -> ""
        else -> "OT"
    }

    /** A live game's clock: "1:45 3rd", "Halftime", or ESPN's own words. */
    fun clock(game: NflGame): String {
        if (game.detail.contains("half", ignoreCase = true)) return "Halftime"
        val quarter = quarter(game.period)
        val clock = game.clock?.takeIf { it.isNotBlank() }
        return if (clock != null && quarter.isNotEmpty()) "$clock $quarter" else game.detail.ifBlank { "Live" }
    }

    /** "8:15 PM". */
    fun time(millis: Long, zone: ZoneId): String {
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        val hour = (local.hour + 11) % 12 + 1
        return "$hour:${local.minute.toString().padStart(2, '0')} ${if (local.hour < 12) "AM" else "PM"}"
    }

    /** "Mon". */
    fun day(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)

    /** "Nov 1". */
    fun date(millis: Long, zone: ZoneId): String {
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        return "${local.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${local.dayOfMonth}"
    }

    /** A kickoff: "Mon 8:15 PM", or "Sun · time TBD" while the league hasn't set it. */
    fun kickoff(game: NflGame, zone: ZoneId): String =
        if (game.kickoffKnown) "${day(game.kickoffMillis, zone)} ${time(game.kickoffMillis, zone)}" else "${day(game.kickoffMillis, zone)} · time TBD"

    /** A final: "Final", "Final/OT". */
    fun final(game: NflGame): String = game.detail.takeIf { it.startsWith("Final", ignoreCase = true) } ?: "Final"

    /** The game's status, short: "1:45 3rd", "Final", "Mon 8:15 PM". */
    fun status(game: NflGame, zone: ZoneId): String = when (game.state) {
        GameState.LIVE -> clock(game)
        GameState.FINAL -> final(game)
        GameState.PRE -> kickoff(game, zone)
    }

    /** "Kickoff in 25 min", "Kickoff in 1 hr 5 min", or "Kickoff at 8:15 PM" further off. */
    fun kickoffIn(game: NflGame, nowMillis: Long, zone: ZoneId): String {
        val minutes = ((game.kickoffMillis - nowMillis) / 60_000).coerceAtLeast(0)
        return when {
            !game.kickoffKnown -> "Kickoff time not set yet"
            minutes < 1 -> "Kickoff any moment"
            minutes < 60 -> "Kickoff in $minutes min"
            minutes < 3 * 60 -> "Kickoff in ${minutes / 60} hr" + if (minutes % 60 > 0) " ${minutes % 60} min" else ""
            else -> "Kickoff at ${time(game.kickoffMillis, zone)}"
        }
    }

    /** The first network, as the TVs name it: "NBC", "FOX", "NFL Network", "Prime Video"; "TBD" before one's announced. */
    fun network(game: NflGame): String = (game.tv + game.streaming).firstOrNull()?.let(WatchLinks::network) ?: "TBD"

    /** A network on a badge, short: "NFL NET", "PRIME". */
    fun badge(network: String): String = when (network) {
        "NFL Network" -> "NFL NET"
        "Prime Video" -> "PRIME"
        else -> network.uppercase()
    }

    /** "1st", "2nd", "15th". */
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /** "Oct 29 – Nov 2": the week's first and last game days. */
    fun dates(week: NflWeek?, zone: ZoneId): String {
        val kickoffs = week?.games.orEmpty().filter { it.kickoffKnown }.map { it.kickoffMillis }
        val first = kickoffs.minOrNull() ?: return ""
        val last = kickoffs.max()
        val from = date(first, zone)
        val to = date(last, zone)
        return if (from == to) from else "$from – $to"
    }

    /** "Scores from 2:41 PM" while a game is on and the copy is more than 2 minutes old (F11). */
    fun staleScores(view: NflView, nowMillis: Long): String? {
        val asOf = view.asOfMillis ?: return null
        val live = view.state?.week?.games.orEmpty().any { it.state == GameState.LIVE }
        return if (live && nowMillis - asOf > STALE_MILLIS) "Scores from ${time(asOf, view.zone)}" else null
    }

    /** A copy older than this, while a game is on, says how old (F11). */
    const val STALE_MILLIS = 2 * 60_000L

    /** What a hidden score says (F10). */
    const val HIDDEN = "Score hidden"
}
