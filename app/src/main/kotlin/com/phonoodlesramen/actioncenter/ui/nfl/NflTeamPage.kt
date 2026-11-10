package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflLoad
import com.phonoodlesramen.actioncenter.nfl.NflTeamSchedule
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * A team's page (`Screen.NFL_TEAM` in the spec; a page of [NflScreen]): a header on the team's
 * colour (the 72 dp logo, the division, the full name, "2-5 · 3rd in the NFC East · 15th in the
 * NFC · L5"), **★ Your team** or **☆ Make it your team** (F5) and its division's standings; then
 * the 18 weeks in two columns: results, LIVE with the score, or the time and network to come, and
 * the bye. OK on a week opens that game's details, or watches it while it's on. The season loads
 * from the station (or ESPN).
 */
@Composable
internal fun TeamPage(ctx: NflContext, abbr: String, focusKey: String?, modifier: Modifier = Modifier) {
    val view = ctx.view
    val state = ctx.state
    val team = view.team(abbr)
    val load by produceState<NflLoad<NflTeamSchedule>>(NflLoad.Loading, abbr, state.seasonYear) { value = ctx.controller.schedule(abbr, state.seasonYear) }
    LaunchedEffect(focusKey) { ctx.focus.focus(focusKey, STAR) }
    val standing = view.standings(ctx.nowMillis).firstOrNull { it.abbr == abbr }
    val conferenceRank = standing?.seed
    val yours = abbr in view.settings.yourTeams
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(Palette.Surface, RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(0f to NflColors.wash(team).copy(alpha = 0.44f), 0.6f to Color.Transparent), RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TeamLogo(abbr, team, 72.dp)
            Column(Modifier.weight(1f)) {
                Eyebrow(team?.divisionName ?: "")
                FitText(
                    team?.fullName ?: abbr,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
                    smaller = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    listOfNotNull(
                        standing?.record,
                        standing?.let { "${NflText.ordinal(it.divisionRank)} in the ${team?.divisionName}" },
                        conferenceRank?.let { "${NflText.ordinal(it)} in the ${team?.conference}" },
                        standing?.streak,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    text = if (yours) "Your team" else "Make it your team",
                    icon = if (yours) AppIcons.Star else AppIcons.StarOutline,
                    onClick = { star(ctx, abbr, !yours) },
                    modifier = Modifier.focusRequester(ctx.focus.requester(STAR)).onFocused { ctx.focus.focused(STAR) },
                )
                PillButton(
                    text = team?.divisionName ?: "Standings",
                    onClick = { ctx.nav.push(NflPage.Tab(NflTab.STANDINGS), DIVISION) },
                    colors = pillButtonColors(container = Palette.Surface2),
                    modifier = Modifier.focusRequester(ctx.focus.requester(DIVISION)).onFocused { ctx.focus.focused(DIVISION) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when (val loaded = load) {
            NflLoad.Loading -> Quietly("Loading…", Modifier.fillMaxWidth())
            is NflLoad.Failed -> Quietly(loaded.sentence, Modifier.fillMaxWidth(), failed = true)
            is NflLoad.Loaded -> Season(ctx, abbr, loaded.value)
        }
    }
}

private const val STAR = "star"
private const val DIVISION = "division"

/** ★ Make it your team (F5): the house's teams (B), or this TV's; a toast says what changed, or why it couldn't. */
private fun star(ctx: NflContext, abbr: String, yours: Boolean) {
    val settings = ctx.view.settings
    val teams = if (yours) (settings.yourTeams + abbr).distinct() else settings.yourTeams - abbr
    val name = ctx.view.team(abbr)?.name ?: abbr
    ctx.controller.saveSettings(settings.copy(yourTeams = teams)) { refused ->
        ctx.actions.say(refused ?: if (yours) "The $name are your team: their games lead the NFL row and the game-day card" else "The $name are no longer your team")
    }
}

/** The 18 weeks in two columns of nine (36 dp rows), weeks 1-9 on the left; the bye a faint row. */
@Composable
private fun Season(ctx: NflContext, abbr: String, schedule: NflTeamSchedule) {
    val own = ctx.state.week?.games.orEmpty()
    val byWeek = schedule.games.filter { it.seasonType == REGULAR }.associateBy { it.week }
    val weeks = (1..maxOf(WEEKS, byWeek.keys.maxOrNull() ?: 0)).map { week ->
        week to byWeek[week]?.let { game -> own.firstOrNull { it.id == game.id } ?: game }
    }
    Row(Modifier.fillMaxWidth().focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        weeks.chunked((weeks.size + 1) / 2).forEach { column ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                column.forEach { (week, game) -> WeekRow(ctx, abbr, week, game) }
            }
        }
    }
}

private const val REGULAR = 2
private const val WEEKS = 18

@Composable
private fun WeekRow(ctx: NflContext, abbr: String, week: Int, game: NflGame?) {
    val view = ctx.view
    val key = "week:$week"
    Surface(
        onClick = { if (game != null) ctx.ok(game, key) },
        onLongClick = game?.let { { ctx.sheet(it, key) } },
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .focusRequester(ctx.focus.requester(key))
            .onFocused { ctx.focus.focused(key) }
            .semantics { contentDescription = "Week $week" },
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WK $week", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1, modifier = Modifier.width(40.dp))
            if (game == null) {
                Text("Bye", style = MaterialTheme.typography.bodyMedium, color = Palette.TextFaint)
                return@Row
            }
            Text(
                "${NflText.day(game.kickoffMillis, view.zone)} ${NflText.date(game.kickoffMillis, view.zone)}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
                maxLines = 1,
                modifier = Modifier.width(84.dp),
            )
            val home = game.home == abbr
            val opponent = if (home) game.away else game.home
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (home) "vs" else "at", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                TeamLogo(opponent, view.team(opponent), 20.dp)
                Text(view.team(opponent)?.name ?: opponent, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(result(ctx, game, abbr), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.End, maxLines = 1)
        }
    }
}

/** "W 24–22" (good), "L 7–27" (bad), "T 20–20", "LIVE 10–13", "Score hidden", or "8:15 PM · NBC" to come. */
private fun result(ctx: NflContext, game: NflGame, abbr: String) = buildAnnotatedString {
    val view = ctx.view
    val mine = (if (game.home == abbr) game.homeScore else game.awayScore) ?: 0
    val theirs = (if (game.home == abbr) game.awayScore else game.homeScore) ?: 0
    when {
        game.state != GameState.PRE && view.hidden(game, ctx.nowMillis) -> withStyle(SpanStyle(color = Palette.TextDim)) { append(NflText.HIDDEN) }
        game.state == GameState.LIVE -> {
            withStyle(SpanStyle(color = NflColors.Live, fontWeight = FontWeight.Bold)) { append("LIVE ") }
            append("$mine–$theirs")
        }
        game.state == GameState.FINAL -> {
            val (letter, color) = when {
                game.winner == abbr -> "W" to Palette.Good
                game.winner == null -> "T" to Palette.TextDim
                else -> "L" to Palette.Bad
            }
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(letter) }
            append(" $mine–$theirs")
        }
        else -> withStyle(SpanStyle(color = Palette.TextDim)) {
            append(if (game.kickoffKnown) NflText.time(game.kickoffMillis, view.zone) else "Time TBD")
            append(" · ")
            append((game.tv + game.streaming).firstOrNull()?.let(com.phonoodlesramen.actioncenter.nfl.WatchLinks::network) ?: "TV TBD")
        }
    }
}
