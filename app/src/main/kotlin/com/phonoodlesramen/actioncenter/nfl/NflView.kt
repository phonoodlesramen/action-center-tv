package com.phonoodlesramen.actioncenter.nfl

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where this TV's NFL comes from (F11): the guide station while paired, else ESPN itself. */
enum class NflFrom(val label: String) {
    STATION("from the guide station"),
    ESPN("from ESPN"),
}

/**
 * The NFL as the home, the NFL screen and Settings › NFL draw it (Phase 13): this TV's copy and
 * what it knows about it. Never waits on the network.
 */
@Immutable
data class NflView(
    /** The copy; null until the station (or ESPN) has answered once. */
    val state: NflState? = null,
    val from: NflFrom = NflFrom.ESPN,
    /** When the scores were last known to be current: the copy's last check, or the station's last good round while ESPN fails it. */
    val asOfMillis: Long? = null,
    /** A 1.1 station: the NFL waits for its update. */
    val outdated: Boolean = false,
    /** Why the copy couldn't be checked just now ("Can't reach the guide station."); the station's own is [NflState.problem]. */
    val problem: String? = null,
    /** Games whose score Show score showed (C), on [shownDay]: until tomorrow. */
    val shown: Set<String> = emptySet(),
    val shownDay: LocalDate? = null,
    /** The day Not today put the game-day card away (Quick Watch is back until tomorrow). */
    val notTodayDay: LocalDate? = null,
    /** The house's zone: kickoffs, game days and "today" are its. */
    val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * Settings known apart from the copy: an unpaired TV's own, or, paired, the house's as
     * this TV last saved them while the station had no copy yet. Without them a just-turned-on
     * Show the NFL would read as off until ESPN first answers.
     */
    val ownSettings: NflSettings? = null,
) {
    val settings: NflSettings get() = ownSettings ?: state?.settings ?: NflSettings()

    /** Settings › NFL › Show the NFL is on ([NflSettings.showing]): the home shows the NFL, and ESPN is asked. */
    val showing: Boolean get() = settings.showing

    /** The scores Show score showed today. */
    fun shownOn(today: LocalDate): Set<String> = if (shownDay == today) shown else emptySet()

    fun today(nowMillis: Long): LocalDate = NflRules.localDate(nowMillis, zone)

    /** [game]'s score is hidden right now (F10, C). */
    fun hidden(game: NflGame, nowMillis: Long): Boolean {
        val today = today(nowMillis)
        return NflRules.scoreHidden(game, settings, shownOn(today), today, zone)
    }

    /** The standings to draw: this morning's while any of today's games are hidden (C). */
    fun standings(nowMillis: Long): List<NflStanding> {
        val state = state ?: return emptyList()
        val today = today(nowMillis)
        val hiding = NflRules.anyScoreHidden(state.week, settings, shownOn(today), today, zone)
        return if (hiding && state.morningStandings.isNotEmpty()) state.morningStandings else state.standings
    }

    /** [abbr]'s team, if the copy knows it. */
    fun team(abbr: String): NflTeam? = state?.teams?.firstOrNull { it.abbr == abbr }

    companion object {
        /** No copy yet: the row is left out and the NFL screen says what it waits on. */
        val None = NflView()
    }
}

/** Something the NFL screen asks for on demand: another week, a team's season, a game's details. */
sealed interface NflLoad<out T> {
    data object Loading : NflLoad<Nothing>

    data class Loaded<T>(val value: T) : NflLoad<T>

    /** The station's (or ESPN's) sentence. */
    data class Failed(val sentence: String) : NflLoad<Nothing>
}

/** What the home, the NFL screen and Settings › NFL ask of the NFL. The defaults do nothing (previews, screenshots, tests). */
interface NflController {
    val view: StateFlow<NflView>

    /** Settings › NFL: the house's (paired) or this TV's; [done] hears null, or why it couldn't be kept. */
    fun saveSettings(settings: NflSettings, done: (String?) -> Unit = {}) = done(null)

    /** Hold OK › Show score (C): that game's score, on this TV, until tomorrow. */
    fun showScore(gameId: String) {}

    /** The game-day card's Not today (F1): Quick Watch until tomorrow, on this TV. */
    fun notToday() {}

    suspend fun week(seasonType: Int, week: Int, seasonYear: Int): NflLoad<NflWeek> = NflLoad.Failed(NOT_HERE)

    suspend fun schedule(abbr: String, seasonYear: Int): NflLoad<NflTeamSchedule> = NflLoad.Failed(NOT_HERE)

    suspend fun game(id: String): NflLoad<NflGameDetail> = NflLoad.Failed(NOT_HERE)

    /** Shows [view] and fetches nothing: previews and screenshots. */
    open class Fixed(view: NflView = NflView.None) : NflController {
        override val view: StateFlow<NflView> = MutableStateFlow(view)
    }

    companion object {
        const val NOT_HERE = "Not available here."
    }
}
