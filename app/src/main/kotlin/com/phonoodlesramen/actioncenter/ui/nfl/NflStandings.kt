package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.nfl.NflStanding
import com.phonoodlesramen.actioncenter.nfl.NflTeam
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * Standings (F4): Division (the eight divisions at once, AFC above NFC), Conference (seeds 1-16,
 * the playoff line after the 7th) and League (all 32 by win percentage), with clinch marks once
 * ESPN sets them. OK on a team opens its page. While any of today's scores are hidden, they're
 * this morning's (C); in the preseason, last season's final (D).
 */
@Composable
internal fun StandingsTab(ctx: NflContext, page: NflPage.Tab, focusKey: String?, modifier: Modifier = Modifier) {
    val standings = ctx.view.standings(ctx.nowMillis)
    val teams = ctx.state.teams
    LaunchedEffect(page.standings, focusKey) { ctx.focus.focus(focusKey, viewKey(page.standings)) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StandingsView.entries.forEach { view -> ViewPill(ctx, page, view) }
            }
            Spacer(Modifier.weight(1f))
            val morning = standings !== ctx.state.standings && standings == ctx.state.morningStandings
            Legend(
                listOfNotNull(
                    "your team",
                    "the dashed line is the playoff line".takeIf { page.standings == StandingsView.CONFERENCE },
                    "as of this morning, while scores are hidden".takeIf { morning },
                    "clinch marks (x, y, z) appear as teams clinch",
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        val scroll = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        when (page.standings) {
            StandingsView.DIVISION -> Divisions(ctx, standings, teams, scroll)
            StandingsView.CONFERENCE -> Conferences(ctx, standings, teams, scroll)
            StandingsView.LEAGUE -> League(ctx, standings, teams, scroll)
        }
    }
}

fun viewKey(view: StandingsView) = "view:${view.name}"

private fun teamKey(abbr: String) = "team:$abbr"

/** Division, Conference, League: small pills; the open one tinted. */
@Composable
private fun ViewPill(ctx: NflContext, page: NflPage.Tab, view: StandingsView) {
    val open = page.standings == view
    val key = viewKey(view)
    PillButton(
        text = view.label,
        small = true,
        onClick = { if (!open) ctx.nav.replace(page.copy(standings = view), key) },
        colors = pillButtonColors(container = if (open) LocalAccent.current.copy(alpha = 0.14f) else Palette.Text.copy(alpha = 0.08f)),
        modifier = Modifier.focusRequester(ctx.focus.requester(key)).onFocused { ctx.focus.focused(key) },
    )
}

/** "★ your team · clinch marks (x, y, z) appear as teams clinch". */
@Composable
private fun Legend(parts: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(AppIcons.Star, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(12.dp))
        WholeOrNothing {
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1)
        }
    }
}

/** The eight divisions, four across, AFC above NFC: each a box of four rows, the leader's tinted. */
@Composable
private fun Divisions(ctx: NflContext, standings: List<NflStanding>, teams: List<NflTeam>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("AFC", "NFC").forEach { conference ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("East", "North", "South", "West").forEach { division ->
                    val members = teams.filter { it.conference == conference && it.division == division }.map { it.abbr }.toSet()
                    val rows = standings.filter { it.abbr in members }.sortedBy { it.divisionRank }
                    DivisionBox(ctx, "$conference $division", rows, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DivisionBox(ctx: NflContext, name: String, rows: List<NflStanding>, modifier: Modifier) {
    Column(modifier.background(Palette.Surface, RoundedCornerShape(10.dp)).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
        Text(
            name.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
            color = if (name.startsWith("AFC")) NflColors.Afc else NflColors.Nfc,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            maxLines = 1,
        )
        rows.forEachIndexed { index, standing ->
            StandingRow(ctx, standing.abbr, Modifier.height(36.dp), leader = index == 0) {
                TeamLogo(standing.abbr, ctx.view.team(standing.abbr), 22.dp)
                TeamName(ctx, standing, ctx.view.team(standing.abbr)?.name ?: standing.abbr, Modifier.weight(1f))
                Text(standing.record, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                Text(
                    standing.gamesBehind?.takeIf { it != "-" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextFaint,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(26.dp),
                )
            }
        }
    }
}

/** The team's name with a star for yours and its clinch mark; a long name ("Commanders") a size smaller where it must. */
@Composable
private fun TeamName(ctx: NflContext, standing: NflStanding, name: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleSmall) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FitText(name, style = style, smaller = style.copy(fontSize = 12.sp, lineHeight = 16.sp), modifier = Modifier.weight(1f, fill = false))
        if (standing.abbr in ctx.view.settings.yourTeams) Icon(AppIcons.Star, contentDescription = "Your team", tint = LocalAccent.current, modifier = Modifier.size(12.dp))
        standing.clincher?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1) }
    }
}

/**
 * One row of a standings table: focusable, `surface3` when focused (§3's list rows); [leader]
 * tints it. OK opens the team's page.
 */
@Composable
private fun StandingRow(ctx: NflContext, abbr: String, modifier: Modifier = Modifier, leader: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val key = teamKey(abbr)
    Surface(
        onClick = { ctx.team(abbr, key) },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(ctx.focus.requester(key))
            .onFocused { ctx.focus.focused(key) }
            .semantics { contentDescription = ctx.view.team(abbr)?.fullName ?: abbr },
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (leader) NflColors.Leader else Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** AFC and NFC side by side, seeds 1-16, a dashed accent line after the 7th: the playoff line. */
@Composable
private fun Conferences(ctx: NflContext, standings: List<NflStanding>, teams: List<NflTeam>, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("AFC", "NFC").forEach { conference ->
            val members = teams.filter { it.conference == conference }.map { it.abbr }.toSet()
            val seeded = standings.filter { it.abbr in members }.sortedWith(compareBy(nullsLast()) { it.seed })
            Column(Modifier.weight(1f)) {
                TableHeader(conference, CONFERENCE_COLUMNS)
                seeded.forEachIndexed { index, standing ->
                    if (index == PLAYOFF_SEEDS) PlayoffLine()
                    val team = ctx.view.team(standing.abbr)
                    StandingRow(ctx, standing.abbr, Modifier.height(ROW)) {
                        Text((standing.seed ?: index + 1).toString(), style = TABLE, color = if (index < PLAYOFF_SEEDS) Palette.Text else Palette.TextFaint, modifier = Modifier.width(22.dp))
                        TeamLogo(standing.abbr, team, 16.dp)
                        TeamName(ctx, standing, team?.fullName ?: standing.abbr, Modifier.weight(1f), TABLE.copy(fontWeight = FontWeight.Medium))
                        if (index < DIVISION_SEEDS) Text("· div", style = TABLE, color = Palette.TextFaint, maxLines = 1)
                        Cells(CONFERENCE_COLUMNS, listOf(standing.record, standing.winPercent, standing.divisionRecord.orEmpty(), standing.streak.orEmpty()))
                    }
                }
            }
        }
    }
}

/** All 32 by win percentage, then point difference, in two columns of 16. */
@Composable
private fun League(ctx: NflContext, standings: List<NflStanding>, teams: List<NflTeam>, modifier: Modifier) {
    val ranked = standings.sortedWith(compareByDescending<NflStanding> { it.winPercent.toDoubleOrNull() ?: 0.0 }.thenByDescending { it.differential })
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ranked.chunked((ranked.size + 1) / 2).forEachIndexed { half, list ->
            Column(Modifier.weight(1f)) {
                TableHeader("", LEAGUE_COLUMNS)
                list.forEachIndexed { index, standing ->
                    val team = teams.firstOrNull { it.abbr == standing.abbr }
                    StandingRow(ctx, standing.abbr, Modifier.height(ROW)) {
                        Text((half * ((ranked.size + 1) / 2) + index + 1).toString(), style = TABLE, color = Palette.TextFaint, modifier = Modifier.width(22.dp))
                        TeamLogo(standing.abbr, team, 16.dp)
                        TeamName(ctx, standing, team?.name ?: standing.abbr, Modifier.weight(1f), TABLE.copy(fontWeight = FontWeight.Medium))
                        val diff = standing.differential
                        Cells(LEAGUE_COLUMNS.dropLast(2), listOf(standing.record, standing.winPercent, standing.pointsFor.toString(), standing.pointsAgainst.toString()))
                        Text(
                            if (diff > 0) "+$diff" else diff.toString(),
                            style = TABLE,
                            color = when {
                                diff > 0 -> Palette.Good
                                diff < 0 -> Palette.Bad
                                else -> Palette.TextDim
                            },
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(LEAGUE_COLUMNS[4].second),
                        )
                        Cells(LEAGUE_COLUMNS.takeLast(1), listOf(standing.streak.orEmpty()))
                    }
                }
            }
        }
    }
}

/** A table's numbers, each in its column's width, right-aligned. */
@Composable
private fun RowScope.Cells(columns: List<Pair<String, Dp>>, values: List<String>) {
    columns.zip(values).forEach { (column, value) ->
        Text(value, style = TABLE, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(column.second))
    }
}

/** A table's column heads: the conference over the seeds, then each column's name. */
@Composable
private fun TableHeader(first: String, columns: List<Pair<String, Dp>>) {
    Row(Modifier.fillMaxWidth().height(ROW).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(first, style = HEAD, color = Palette.TextFaint, modifier = Modifier.weight(1f))
        columns.forEach { (name, width) -> Text(name, style = HEAD, color = Palette.TextFaint, textAlign = TextAlign.End, modifier = Modifier.width(width)) }
    }
}

/** The dashed accent line after the 7th seed. */
@Composable
private fun PlayoffLine() {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxWidth().height(7.dp).drawBehind {
            val y = size.height / 2
            drawLine(accent.copy(alpha = 0.8f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
        },
    )
}

/** Seeds 1-7 make the playoffs; 1-4 are the division leaders. */
private const val PLAYOFF_SEEDS = 7
private const val DIVISION_SEEDS = 4

/** The tables' 22 dp rows. */
private val ROW = 22.dp

private val TABLE @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
private val HEAD @Composable get() = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp)

private val CONFERENCE_COLUMNS = listOf("W-L" to 40.dp, "PCT" to 40.dp, "DIV" to 40.dp, "STRK" to 36.dp)
private val LEAGUE_COLUMNS = listOf("W-L" to 40.dp, "PCT" to 40.dp, "PF" to 32.dp, "PA" to 32.dp, "DIFF" to 36.dp, "STRK" to 36.dp)
