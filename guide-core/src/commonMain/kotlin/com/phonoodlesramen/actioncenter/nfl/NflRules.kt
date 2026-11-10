package com.phonoodlesramen.actioncenter.nfl

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** One group of the Games tab: "Sunday afternoon · 4:05 and 4:25", live games first. */
data class NflSlot(val label: String, val games: List<NflGame>)

/**
 * Two teams that meet in the playoffs, or will. [high] is the better seed, at home; in the Super
 * Bowl, the AFC's champion, [low] the NFC's. [game] is ESPN's once it has the game.
 */
data class NflMatchup(val high: String, val low: String, val game: NflGame? = null) {
    val winner: String? get() = game?.winner
}

/** One conference's playoff picture: "If the season ended today" from the seeds, and the real games as they're played. */
data class NflConferenceBracket(
    /** "AFC" or "NFC". */
    val conference: String,
    /** Seeds 1-7: the four division leaders, then the three wild cards. */
    val seeds: List<NflStanding>,
    /** Seed 1, who sits out the wild-card round. */
    val bye: String?,
    /** 2 v 7, 3 v 6, 4 v 5. */
    val wildCard: List<NflMatchup>,
    /** Once the wild-card round is decided: seed 1 v the lowest seed left, and the other two. */
    val divisional: List<NflMatchup>,
    /** Once the divisional round is decided. */
    val championship: NflMatchup?,
    /** Seeds 8-10: in the hunt. */
    val hunt: List<NflStanding>,
) {
    val champion: String? get() = championship?.winner
}

data class NflBracket(val afc: NflConferenceBracket, val nfc: NflConferenceBracket, val superBowl: NflMatchup?)

/**
 * The NFL's rules for the home and the NFL screen (Phase 13): pure functions of the copy and the
 * clock, in the house's zone (the station's `TZ`), so the station, a TV and the tests agree.
 */
object NflRules {
    /** The game-day card goes up this long before a kickoff (F1). */
    const val CARD_LEAD_MILLIS = 30 * 60_000L

    /** On a game day the NFL row goes first this long before the first kickoff (F1). */
    const val ROW_LEAD_MILLIS = 60 * 60_000L

    /** A day's games are given up on this long after its last kickoff, should ESPN never say they're final. */
    const val GAME_DAY_LIMIT_MILLIS = 6 * 60 * 60_000L

    private const val PRESEASON = 1
    private const val REGULAR = 2
    private const val POSTSEASON = 3

    /**
     * Where the season is (F8). ESPN's calendar sets the preseason, regular season and playoffs.
     * [SeasonPhase.OFF] runs from the day after the Super Bowl (its kickoff when [week] is the
     * Super Bowl's, else the calendar's end) to the day of the first preseason game (the
     * calendar's start). ESPN's `leagues[0].season.type` isn't used: on a `dates=` request it
     * describes today rather than the week asked for.
     */
    fun phase(calendar: List<NflWeekRef>, week: NflWeek?, now: Long, zone: ZoneId): SeasonPhase {
        val weeks = calendar.sortedBy { it.startMillis }
        val first = weeks.firstOrNull() ?: return SeasonPhase.OFF
        val superBowl = weeks.lastOrNull { it.seasonType == POSTSEASON }
        val superBowlKickoff = week?.takeIf { superBowl != null && it.seasonType == POSTSEASON && it.week == superBowl.week }
            ?.games?.minOfOrNull { it.kickoffMillis }
        val opens = startOfDay(first.startMillis, zone)
        val closes = superBowlKickoff?.let { startOfDay(it, zone, plusDays = 1) } ?: (superBowl ?: weeks.last()).endMillis
        if (now < opens || now >= closes) return SeasonPhase.OFF
        return when (weeks.lastOrNull { it.startMillis <= now }?.seasonType ?: first.seasonType) {
            PRESEASON -> SeasonPhase.PRESEASON
            POSTSEASON -> SeasonPhase.POSTSEASON
            else -> SeasonPhase.REGULAR
        }
    }

    /** Whose standings the Standings and Playoffs tabs show: last season's until Week 1 (D). */
    fun standingsSeason(calendar: List<NflWeekRef>, seasonYear: Int, now: Long): Int {
        val weekOne = calendar.filter { it.seasonType == REGULAR }.minOfOrNull { it.startMillis }
        return if (weekOne == null || now < weekOne) seasonYear - 1 else seasonYear
    }

    /** On, or past its kickoff and not final: ESPN takes a minute to say a game has started. */
    fun underway(game: NflGame, now: Long): Boolean =
        game.state == GameState.LIVE || (game.state == GameState.PRE && game.kickoffKnown && game.kickoffMillis <= now)

    /**
     * The game-day card's game (F2): your team's while it's on; else the closest live game (the
     * smallest margin, the later quarter first); else the next kickoff, yours first among those
     * at the same time. Null once every game is final.
     */
    fun featured(week: NflWeek?, yourTeams: Collection<String>, now: Long): NflGame? {
        val games = week?.games.orEmpty()
        games.filter { it.isYours(yourTeams) && underway(it, now) }.sortedWith(CLOSEST).firstOrNull()?.let { return it }
        games.filter { it.state == GameState.LIVE }.sortedWith(CLOSEST).firstOrNull()?.let { return it }
        return games.filter { it.state == GameState.PRE }
            .sortedWith(compareBy<NflGame>({ !it.kickoffKnown }, { it.kickoffMillis }, { !it.isYours(yourTeams) }))
            .firstOrNull()
    }

    /** The game-day card is up (F1): from 30 minutes before a kickoff until every game that day is final. */
    fun cardUp(week: NflWeek?, now: Long, zone: ZoneId): Boolean = gameDays(week, zone).any { (_, games) ->
        val first = games.minOf { it.kickoffMillis }
        val last = games.maxOf { it.kickoffMillis }
        now >= first - CARD_LEAD_MILLIS && now < last + GAME_DAY_LIMIT_MILLIS && games.any { it.state != GameState.FINAL }
    }

    /** The NFL row goes first below the dashboard (F1): on a game day, from an hour before its first kickoff to the day's end. */
    fun rowFirst(week: NflWeek?, now: Long, zone: ZoneId): Boolean = gameDays(week, zone).any { (day, games) ->
        now >= games.minOf { it.kickoffMillis } - ROW_LEAD_MILLIS && now < day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The NFL row's games: live (yours first, then the closest), the next kickoffs by time, then finals, newest first. */
    fun rowOrder(week: NflWeek?, yourTeams: Collection<String>): List<NflGame> {
        val games = week?.games.orEmpty()
        val live = games.filter { it.state == GameState.LIVE }.sortedWith(compareBy<NflGame> { !it.isYours(yourTeams) }.then(CLOSEST))
        val next = games.filter { it.state == GameState.PRE }.sortedWith(compareBy({ !it.kickoffKnown }, { it.kickoffMillis }))
        val finals = games.filter { it.state == GameState.FINAL }.sortedByDescending { it.kickoffMillis }
        return live + next + finals
    }

    /**
     * The Games tab's time slots, in kickoff order, live games first within each. In the house's zone:
     * - Sunday: "Sunday morning · 9:30" (London), "Sunday · Nov 1 · 1:00", "Sunday afternoon · 4:05 and 4:25", "Sunday night";
     * - from 6 PM on other days: "Thursday night · Oct 29", "Monday night", "Saturday night · Dec 26";
     * - otherwise each time its own: "Saturday · Dec 26 · 4:30", "Friday · Dec 25 · 1:00";
     * - in the postseason, the round's headline ("AFC Wild Card Playoffs");
     * - kickoffs the league hasn't set, last: "Times not set yet".
     */
    fun slots(week: NflWeek?, zone: ZoneId): List<NflSlot> {
        val postseason = week?.seasonType == POSTSEASON
        val groups = LinkedHashMap<String, Pair<SlotName, MutableList<NflGame>>>()
        for (game in week?.games.orEmpty().sortedBy { it.kickoffMillis }) {
            val name = slotName(game, postseason, zone)
            groups.getOrPut(name.key) { name to mutableListOf() }.second += game
        }
        return groups.values
            .sortedWith(compareBy({ it.first.key == UNSET }, { (_, games) -> games.minOf { it.kickoffMillis } }))
            .map { (name, games) ->
                val times = games.map { time(it.kickoffMillis, zone) }.distinct().let(::joined)
                NflSlot(if (name.withTimes) "${name.prefix} · $times" else name.prefix, games.sortedBy { it.state != GameState.LIVE })
            }
    }

    /**
     * The playoff picture from the seeds ([standings] and [teams] for each team's conference),
     * with [postseasonGames] matched in where ESPN has them: a later round is paired once the one
     * before it is decided, the top seed meeting the lowest left.
     */
    fun bracket(standings: List<NflStanding>, teams: List<NflTeam>, postseasonGames: List<NflGame>): NflBracket {
        val conferenceOf = teams.associate { it.abbr to it.conference }
        val playoffs = postseasonGames.filter { it.seasonType == POSTSEASON }
        fun gameOf(a: String, b: String) = playoffs.firstOrNull { it.involves(a) && it.involves(b) }

        fun conference(name: String): NflConferenceBracket {
            val seeded = standings.filter { conferenceOf[it.abbr] == name && it.seed != null }.sortedBy { it.seed }
            val seedOf = seeded.associate { it.abbr to it.seed!! }
            val bySeed = seeded.associateBy { it.seed!! }
            fun meet(a: String, b: String): NflMatchup {
                val (high, low) = if (seedOf.getValue(a) <= seedOf.getValue(b)) a to b else b to a
                return NflMatchup(high, low, gameOf(a, b))
            }
            val wildCard = listOf(2 to 7, 3 to 6, 4 to 5).mapNotNull { (high, low) ->
                val a = bySeed[high]?.abbr ?: return@mapNotNull null
                val b = bySeed[low]?.abbr ?: return@mapNotNull null
                meet(a, b)
            }
            val top = bySeed[1]?.abbr
            val wildCardWinners = wildCard.mapNotNull { it.winner }
            val divisional = if (top != null && wildCard.size == 3 && wildCardWinners.size == 3) {
                val left = (wildCardWinners + top).sortedBy { seedOf.getValue(it) }
                listOf(meet(left[0], left[3]), meet(left[1], left[2]))
            } else {
                emptyList()
            }
            val divisionalWinners = divisional.mapNotNull { it.winner }
            val championship = if (divisionalWinners.size == 2) meet(divisionalWinners[0], divisionalWinners[1]) else null
            return NflConferenceBracket(
                conference = name,
                seeds = seeded.filter { it.seed!! <= 7 },
                bye = top,
                wildCard = wildCard,
                divisional = divisional,
                championship = championship,
                hunt = seeded.filter { it.seed!! in 8..10 },
            )
        }

        val afc = conference("AFC")
        val nfc = conference("NFC")
        val afcChampion = afc.champion
        val nfcChampion = nfc.champion
        val superBowl = if (afcChampion != null && nfcChampion != null) {
            NflMatchup(afcChampion, nfcChampion, gameOf(afcChampion, nfcChampion))
        } else {
            // Without the seeds to follow (a station that missed the standings), ESPN's own game, once both sides are known.
            playoffs.firstOrNull { it.headline.orEmpty().contains("Super Bowl") && it.away != TBD && it.home != TBD }?.let { game ->
                val first = listOf(game.away, game.home).firstOrNull { conferenceOf[it] == "AFC" } ?: game.away
                NflMatchup(first, if (first == game.away) game.home else game.away, game)
            }
        }
        return NflBracket(afc, nfc, superBowl)
    }

    /**
     * Proposal E: a game Sunday Ticket carries, so Watch opens YouTube TV's home first. All of:
     * the house has Sunday Ticket and its area's teams are set; a regular-season Sunday game
     * kicking off from noon to 6 PM house time on CBS or FOX; neither team one of the area's
     * (those are on the local channel, and Sunday Ticket blacks them out).
     */
    fun sundayTicketGame(game: NflGame, settings: NflSettings, zone: ZoneId): Boolean {
        if (!settings.sundayTicket || settings.areaTeams.isEmpty()) return false
        if (game.seasonType != REGULAR || !game.kickoffKnown) return false
        val local = Instant.ofEpochMilli(game.kickoffMillis).atZone(zone)
        if (local.dayOfWeek != DayOfWeek.SUNDAY || local.hour !in 12 until 18) return false
        if (game.tv.none { it.equals("CBS", ignoreCase = true) || it.equals("Fox", ignoreCase = true) }) return false
        return game.away !in settings.areaTeams && game.home !in settings.areaTeams
    }

    /**
     * Proposal C: while Hide scores is on, today's games show "Score hidden" until one is shown
     * ([shownIds], hold OK › Show score, until tomorrow). A game still on counts as today's
     * after midnight; one not started has no score to hide.
     */
    fun scoreHidden(game: NflGame, settings: NflSettings, shownIds: Set<String>, today: LocalDate, zone: ZoneId): Boolean =
        settings.hideScores && game.id !in shownIds && game.state != GameState.PRE &&
            (game.state == GameState.LIVE || localDate(game.kickoffMillis, zone) == today)

    /** Any of [week]'s scores hidden: then the standings shown are the 4 AM ones, [NflState.morningStandings] (C). */
    fun anyScoreHidden(week: NflWeek?, settings: NflSettings, shownIds: Set<String>, today: LocalDate, zone: ZoneId): Boolean =
        week?.games.orEmpty().any { scoreHidden(it, settings, shownIds, today, zone) }

    /** The house's date at [millis]. */
    fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** The closest game first: the smallest margin, then the later quarter, then the earlier kickoff. */
    private val CLOSEST = compareBy<NflGame>(
        { kotlin.math.abs((it.homeScore ?: 0) - (it.awayScore ?: 0)) },
        { -(it.period ?: 0) },
        { it.kickoffMillis },
    )

    private fun NflGame.isYours(yourTeams: Collection<String>) = away in yourTeams || home in yourTeams

    /** [week]'s games with a set kickoff, by the house's date they kick off on. */
    private fun gameDays(week: NflWeek?, zone: ZoneId): Map<LocalDate, List<NflGame>> =
        week?.games.orEmpty().filter { it.kickoffKnown }.groupBy { localDate(it.kickoffMillis, zone) }

    private fun startOfDay(millis: Long, zone: ZoneId, plusDays: Long = 0): Long =
        localDate(millis, zone).plusDays(plusDays).atStartOfDay(zone).toInstant().toEpochMilli()

    /** A slot's grouping [key], and its label: [prefix], then the kickoff times when [withTimes]. */
    private class SlotName(val key: String, val prefix: String, val withTimes: Boolean)

    private const val UNSET = "unset"
    private const val TBD = "TBD"

    private fun slotName(game: NflGame, postseason: Boolean, zone: ZoneId): SlotName {
        if (!game.kickoffKnown) return SlotName(UNSET, "Times not set yet", withTimes = false)
        val headline = game.headline
        if (postseason && !headline.isNullOrBlank()) return SlotName("round $headline", headline, withTimes = false)
        val local: ZonedDateTime = Instant.ofEpochMilli(game.kickoffMillis).atZone(zone)
        val day = local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        val date = "${local.month.getDisplayName(TextStyle.SHORT, Locale.US)} ${local.dayOfMonth}"
        val hour = local.hour
        return when {
            local.dayOfWeek == DayOfWeek.SUNDAY -> when {
                hour < 12 -> SlotName("$date morning", "Sunday morning", withTimes = true)
                hour < 16 -> SlotName("$date early", "Sunday · $date", withTimes = true)
                hour < 18 -> SlotName("$date afternoon", "Sunday afternoon", withTimes = true)
                else -> SlotName("$date night", "Sunday night", withTimes = false)
            }
            hour >= 18 && local.dayOfWeek == DayOfWeek.MONDAY -> SlotName("$date night", "Monday night", withTimes = false)
            hour >= 18 -> SlotName("$date night", "$day night · $date", withTimes = false)
            else -> SlotName("$date ${time(game.kickoffMillis, zone)}", "$day · $date", withTimes = true)
        }
    }

    /** "1:00", "4:25", "9:30": the clock on the wall, as the TVs show kickoffs. */
    private fun time(millis: Long, zone: ZoneId): String {
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        return "${(local.hour + 11) % 12 + 1}:${local.minute.toString().padStart(2, '0')}"
    }

    /** "4:05", "4:05 and 4:25", "1:00, 4:05 and 4:25". */
    private fun joined(parts: List<String>): String =
        if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
}
