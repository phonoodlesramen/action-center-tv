package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.nfl.NflController
import com.phonoodlesramen.actioncenter.nfl.NflFrom
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.NflSource
import com.phonoodlesramen.actioncenter.nfl.NflTeam
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.nfl.NflRules
import com.phonoodlesramen.actioncenter.station.GuideStationController
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.nfl.TeamLogo
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/** Which list the team grid picks for. */
internal enum class TeamPick(val title: String) {
    YOURS("Your teams"),
    AREA("Your area's teams"),
}

/**
 * Settings › NFL (Phase 13, F5, F6, F10; ui-spec "Settings › NFL"), after Plex Guide, hidden on a
 * kid's profile:
 *
 * - **Show the NFL** first (O6), with where the data comes from under it. Off (a new
 *   house's default) is all the section shows, and nothing asks ESPN;
 * - the status line: the week, where the NFL comes from and when it was updated, or what's wrong;
 * - **Your teams** and **Your area's teams**, each opening a grid of the 32 teams where OK stars a
 *   team and Back saves;
 * - **Sunday Ticket** and **Hide scores**;
 * - whose they are: the house's while paired (B), kept by the station, this TV's otherwise. While
 *   the station can't be reached they dim, with the editing sentence (N5), and a press repeats it.
 */
@Composable
fun ColumnScope.NflSection(nfl: NflController, nowMillis: Long, station: GuideStationController? = null) {
    val view by remember(nfl) { nfl.view }.collectAsState()
    val stationView = station?.let { remember(it) { it.view }.collectAsState().value }
    val locked = stationView?.editingLocked?.takeIf { view.from == NflFrom.STATION }
    var picking by remember { mutableStateOf<TeamPick?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Back from the grid lands on the row that opened it.
    var backTo by remember { mutableStateOf<TeamPick?>(null) }
    val rows = remember { TeamPick.entries.associateWith { FocusRequester() } }
    val settings = view.settings

    fun save(next: NflSettings, done: (Boolean) -> Unit = {}) {
        if (locked != null) {
            problem = "$locked. $LOCKED"
            done(false)
            return
        }
        nfl.saveSettings(next) { refused ->
            problem = refused
            done(refused == null)
        }
    }

    val open = picking
    if (open != null) {
        TeamGrid(view, open, initial = if (open == TeamPick.YOURS) settings.yourTeams else settings.areaTeams, problem = problem) { picked ->
            val next = if (open == TeamPick.YOURS) settings.copy(yourTeams = picked) else settings.copy(areaTeams = picked)
            if (next == settings) {
                picking = null
                backTo = open
            } else {
                save(next) { kept ->
                    if (kept) {
                        picking = null
                        backTo = open
                    }
                }
            }
        }
        return
    }
    LaunchedEffect(Unit) {
        backTo?.let { runCatching { rows.getValue(it).requestFocus() } }
        backTo = null
    }

    ToggleRow(
        title = "Show the NFL",
        detail = SOURCE_NOTE,
        on = settings.showing,
        onClick = { save(settings.copy(show = !settings.showing)) },
        modifier = if (locked != null) Modifier.alpha(LOCKED_ALPHA) else Modifier,
    )
    if (!settings.showing) {
        if (locked != null) Problem("$locked. $LOCKED")
        problem?.takeIf { locked == null }?.let { Problem(it) }
        return
    }
    StatusLine(view, nowMillis)
    Text(
        if (view.from == NflFrom.STATION) "These are the house's: every TV shares them." else "These are this TV's own, until it's paired with a guide station.",
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.TextDim,
    )
    if (locked != null) Problem("$locked. $LOCKED")
    problem?.takeIf { locked == null }?.let { Problem(it) }
    val greyed = if (locked != null) Modifier.alpha(LOCKED_ALPHA) else Modifier
    Column(greyed.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamsRow(
            title = TeamPick.YOURS.title,
            detail = names(view, settings.yourTeams) + ". Their games lead the game-day card and the NFL row, with a star.",
            modifier = Modifier.focusRequester(rows.getValue(TeamPick.YOURS)),
        ) { if (locked != null) problem = "$locked. $LOCKED" else picking = TeamPick.YOURS }
        TeamsRow(
            title = TeamPick.AREA.title,
            detail = names(view, settings.areaTeams) + ". Their Sunday afternoon games are on your CBS and FOX.",
            modifier = Modifier.focusRequester(rows.getValue(TeamPick.AREA)),
        ) { if (locked != null) problem = "$locked. $LOCKED" else picking = TeamPick.AREA }
        ToggleRow(
            title = "Sunday Ticket",
            detail = "Sunday afternoon games outside your area's teams open Sunday Ticket in YouTube TV.",
            on = settings.sundayTicket,
            onClick = { save(settings.copy(sundayTicket = !settings.sundayTicket)) },
        )
        ToggleRow(
            title = "Hide scores",
            detail = "Today's scores stay hidden until you hold OK on a game › Show score. Standings stay as they were this morning.",
            on = settings.hideScores,
            onClick = { save(settings.copy(hideScores = !settings.hideScores)) },
        )
    }
}

private const val LOCKED = "The NFL's settings can't be changed until it's back."

/** Under Show the NFL (O6): where the data comes from, on whose terms, and that off means off. */
internal const val SOURCE_NOTE = "Game-day cards, scores, standings and team logos from ESPN's public site, for personal use. " +
    "ESPN, the NFL and its teams own them. While this is off, nothing is fetched."
private const val LOCKED_ALPHA = 0.45f

/** "Eagles, Steelers", or "None". */
private fun names(view: NflView, teams: List<String>): String =
    teams.joinToString(", ") { abbr -> view.team(abbr)?.name ?: abbr }.ifEmpty { "None" }

/** "Week 8 · from the guide station · updated 10:36 PM", a 1.1 station's sentence, or the problem in the caution colour. */
@Composable
private fun StatusLine(view: NflView, nowMillis: Long) {
    val state = view.state
    val problem = state?.problem ?: view.problem
    when {
        view.outdated -> Text(NflSource.OUTDATED, style = MaterialTheme.typography.titleSmall, color = Palette.Caution)
        state == null -> {
            Text("Waiting for ${if (view.from == NflFrom.STATION) "the guide station" else "ESPN"}", style = MaterialTheme.typography.titleSmall)
            problem?.let { Problem(it) }
        }
        else -> {
            val week = state.week?.label ?: "The off-season"
            val updated = view.asOfMillis?.let { at ->
                val same = NflRules.localDate(at, view.zone) == NflRules.localDate(nowMillis, view.zone)
                "updated " + (if (same) "" else "${NflText.date(at, view.zone)} at ") + NflText.time(at, view.zone)
            }
            Text(listOfNotNull(week, view.from.label, updated).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
            problem?.let { Problem(it) }
        }
    }
}

/** A row that opens a team grid: its title, who's picked and what they do. */
@Composable
private fun TeamsRow(title: String, detail: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            Spacer(Modifier.width(16.dp))
            Icon(AppIcons.Star, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The 32 teams for [pick]: eight across, AFC above NFC, each conference's divisions in order;
 * 56 dp logos with the abbreviation. OK stars a team or takes its star away; Back saves ([onDone]).
 */
@Composable
private fun ColumnScope.TeamGrid(view: NflView, pick: TeamPick, initial: List<String>, problem: String?, onDone: (List<String>) -> Unit) {
    var picked by remember(pick) { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    BackHandler { onDone(picked) }
    LaunchedEffect(pick) { runCatching { first.requestFocus() } }
    Text(pick.title, style = MaterialTheme.typography.titleSmall)
    Text("OK stars a team, or takes its star away. Back saves.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
    problem?.let { Problem(it) }
    val teams = view.state?.teams.orEmpty()
    if (teams.isEmpty()) {
        Text("The teams appear once the NFL has been fetched.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        return
    }
    var firstSet = false
    Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("AFC", "NFC").forEach { conference ->
            Eyebrow(conference, Modifier.padding(top = 4.dp))
            val ordered = DIVISIONS.flatMap { division -> teams.filter { it.conference == conference && it.division == division }.sortedBy { it.name } }
            ordered.chunked(ACROSS).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { team ->
                        val starred = team.abbr in picked
                        val modifier = if (!firstSet) Modifier.focusRequester(first).also { firstSet = true } else Modifier
                        TeamCell(team, starred, modifier.weight(1f)) {
                            picked = if (starred) picked - team.abbr else picked + team.abbr
                        }
                    }
                    repeat(ACROSS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val DIVISIONS = listOf("East", "North", "South", "West")
private const val ACROSS = 8

/** One team in the grid: its logo (56 dp, or the cell's width), its abbreviation, and a star once it's picked. */
@Composable
private fun TeamCell(team: NflTeam, starred: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = team.fullName
            selected = starred
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (starred) LocalAccent.current.copy(alpha = 0.14f) else Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            val logo = min(LOGO, maxWidth - 8.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    TeamLogo(team.abbr, team, logo)
                    if (starred) {
                        Icon(
                            AppIcons.Star,
                            contentDescription = "Starred",
                            tint = LocalAccent.current,
                            modifier = Modifier.align(Alignment.TopEnd).size(16.dp),
                        )
                    }
                }
                Text(team.abbr, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (starred) FontWeight.Bold else FontWeight.Medium), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private val LOGO = 56.dp

/** Why something couldn't change, in the caution colour. */
@Composable
private fun Problem(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(AppIcons.Warning, contentDescription = null, tint = Palette.Caution, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
    }
}
