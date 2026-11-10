package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflGameDetail
import com.phonoodlesramen.actioncenter.nfl.NflLoad
import com.phonoodlesramen.actioncenter.nfl.NflRules
import com.phonoodlesramen.actioncenter.nfl.NflStat
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.nfl.NflWeek
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * A game's details (`Screen.NFL_GAME` in the spec; a page of [NflScreen]): the scoreboard on both
 * teams' colours, the score by quarter with the venue, the networks and the last play, team stats
 * as paired bars, and buttons: each way to watch (a missing app dimmed), both teams, Standings. A
 * game to come shows its time, network and records; its stats wait for kickoff. The stats load
 * from the station (or ESPN).
 */
@Composable
internal fun GameDetails(ctx: NflContext, page: NflPage.Game, focusKey: String?, modifier: Modifier = Modifier) {
    val view = ctx.view
    val load by produceState<NflLoad<NflGameDetail>>(NflLoad.Loading, page.id) { value = ctx.controller.game(page.id) }
    // The copy's game is the freshest; else the one the page was opened with; else the details'.
    val game = ctx.state.week?.games?.firstOrNull { it.id == page.id } ?: page.game ?: (load as? NflLoad.Loaded)?.value?.game
    val ways = game?.takeIf { it.state != GameState.FINAL }?.let { waysFor(it, view, ctx.watch) }.orEmpty().take(2)
    LaunchedEffect(focusKey, game != null) {
        ctx.focus.focus(focusKey, *(ways.indices.map { "watch:$it" } + listOf("team:away")).toTypedArray())
    }
    if (game == null) {
        Box(modifier) {
            when (val loaded = load) {
                is NflLoad.Failed -> Quietly(loaded.sentence, Modifier.fillMaxWidth(), failed = true)
                else -> Quietly("Loading…", Modifier.fillMaxWidth())
            }
        }
        return
    }
    val hidden = view.hidden(game, ctx.nowMillis)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow("NFL · ${weekLabel(ctx, game)}", color = Palette.Text)
            Eyebrow("· ${slotLabel(ctx, game)}", Modifier.weight(1f))
            Text(NflText.time(ctx.nowMillis, view.zone), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        Scoreboard(ctx, game, hidden, Modifier.fillMaxWidth().height(132.dp))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().weight(1f).clipToBounds(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(400f)) {
                Eyebrow("Score by quarter", Modifier.padding(bottom = 6.dp))
                if (hidden) Text(NflText.HIDDEN, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim) else LineScore(ctx, game)
                Text(
                    text = listOfNotNull(game.venue, (game.tv + game.streaming).map(com.phonoodlesramen.actioncenter.nfl.WatchLinks::network).joinToString(", ").ifEmpty { "TV to be announced" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
                game.lastPlay?.takeIf { !hidden && game.state == GameState.LIVE }?.let {
                    Text("Last play: $it", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Column(Modifier.weight(432f)) {
                Eyebrow("Team stats", Modifier.padding(bottom = 6.dp))
                when {
                    game.state == GameState.PRE -> Text("Team stats appear here once it kicks off.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                    hidden -> Text("Team stats are hidden with the score.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                    else -> when (val loaded = load) {
                        NflLoad.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                        is NflLoad.Failed -> Text(loaded.sentence, style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
                        is NflLoad.Loaded -> Stats(ctx, game, loaded.value)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ways.forEachIndexed { index, way ->
                val key = "watch:$index"
                PillButton(
                    text = way.label,
                    icon = if (index == 0) AppIcons.Play else null,
                    onClick = { if (way.installed) ctx.actions.watch(way, matchup(game, view)) else ctx.actions.say(way.missing) },
                    colors = if (way.installed) pillButtonColors() else pillButtonColors(content = Palette.TextFaint),
                    modifier = Modifier.focusRequester(ctx.focus.requester(key)).onFocused { ctx.focus.focused(key) },
                )
            }
            listOf("team:away" to game.away, "team:home" to game.home).forEach { (key, abbr) ->
                PillButton(
                    text = view.team(abbr)?.name ?: abbr,
                    onClick = { ctx.team(abbr, key) },
                    colors = pillButtonColors(container = Palette.Surface2),
                    modifier = Modifier.focusRequester(ctx.focus.requester(key)).onFocused { ctx.focus.focused(key) },
                )
            }
            PillButton(
                text = "Standings",
                onClick = { ctx.nav.push(NflPage.Tab(NflTab.STANDINGS), "standings") },
                colors = pillButtonColors(container = Palette.Surface2),
                modifier = Modifier.focusRequester(ctx.focus.requester("standings")).onFocused { ctx.focus.focused("standings") },
            )
        }
    }
}

/** "WEEK 8", "WILD CARD": the calendar's name for the game's week. */
private fun weekLabel(ctx: NflContext, game: NflGame): String =
    ctx.state.calendar.firstOrNull { it.seasonType == game.seasonType && it.week == game.week }?.label ?: "Week ${game.week}"

/** The game's time slot, as Games names it: "Sunday night", "Monday night". */
private fun slotLabel(ctx: NflContext, game: NflGame): String =
    NflRules.slots(NflWeek(ctx.state.seasonYear, game.seasonType, game.week, "", listOf(game)), ctx.view.zone).firstOrNull()?.label.orEmpty()

/** The scoreboard (132 dp): each team's 84 dp logo, its city and name, record and place, its score (56 sp); the status between. */
@Composable
private fun Scoreboard(ctx: NflContext, game: NflGame, hidden: Boolean, modifier: Modifier) {
    val view = ctx.view
    val wash = Brush.horizontalGradient(
        0f to NflColors.wash(view.team(game.away)).copy(alpha = 0.31f),
        0.35f to Palette.Surface,
        0.65f to Palette.Surface,
        1f to NflColors.wash(view.team(game.home)).copy(alpha = 0.31f),
    )
    Row(
        modifier.background(Palette.Surface, RoundedCornerShape(12.dp)).background(wash, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigSide(ctx, game, game.away, home = false, hidden, Modifier.weight(1f))
        Column(Modifier.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val big = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            when (game.state) {
                GameState.LIVE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveMark(big)
                        Text(" · ${NflText.clock(game)}", style = big, color = NflColors.Live, maxLines = 1)
                    }
                    if (!hidden) game.downDistance?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
                    if (!hidden && game.redZone) Text("RED ZONE", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = NflColors.Live, modifier = Modifier.padding(top = 2.dp))
                }
                GameState.FINAL -> Text(NflText.final(game), style = big, maxLines = 1)
                GameState.PRE -> {
                    Text(NflText.kickoff(game, view.zone), style = big, maxLines = 1)
                    Text(NflText.network(game), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
                }
            }
            if (hidden) Text(NflText.HIDDEN, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(top = 4.dp))
            NflText.staleScores(view, ctx.nowMillis)?.takeIf { game.state == GameState.LIVE }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
        BigSide(ctx, game, game.home, home = true, hidden, Modifier.weight(1f))
    }
}

/** One side of the scoreboard: logo, city and name, "2-5 · 3rd in NFC East", and the score. */
@Composable
private fun BigSide(ctx: NflContext, game: NflGame, abbr: String, home: Boolean, hidden: Boolean, modifier: Modifier) {
    val view = ctx.view
    val team = view.team(abbr)
    val standing = view.standings(ctx.nowMillis).firstOrNull { it.abbr == abbr }
    val record = (if (home) game.homeRecord else game.awayRecord) ?: standing?.record.orEmpty()
    val place = standing?.let { "${NflText.ordinal(it.divisionRank)} in ${team?.divisionName ?: ""}" }
    val score = if (hidden || game.state == GameState.PRE) "" else scoreOf(game, home)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (home) Arrangement.spacedBy(14.dp, Alignment.End) else Arrangement.spacedBy(14.dp)) {
        if (home && score.isNotEmpty()) BigScore(score)
        if (!home) TeamLogo(abbr, team, 84.dp)
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (home) Alignment.End else Alignment.Start) {
            FitText(
                team?.location ?: abbr,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
                smaller = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(team?.name ?: "", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp), maxLines = 1)
            Text(listOfNotNull(record.ifEmpty { null }, place).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (home) TeamLogo(abbr, team, 84.dp)
        if (!home && score.isNotEmpty()) BigScore(score)
    }
}

@Composable
private fun BigScore(score: String) {
    Text(
        score,
        style = MaterialTheme.typography.displayMedium.copy(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        maxLines = 1,
    )
}

/** The score by quarter, overtime last, and the total. */
@Composable
private fun LineScore(ctx: NflContext, game: NflGame) {
    val quarters = maxOf(4, game.awayLineScores.size, game.homeLineScores.size)
    val heads = (1..quarters).map { if (it <= 4) it.toString() else "OT" } + "T"
    val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    @Composable
    fun line(first: @Composable () -> Unit, cells: List<String>, bold: Boolean, color: Color = Palette.Text) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(96.dp)) { first() }
            cells.forEachIndexed { index, value ->
                Text(
                    value,
                    style = if (bold && index == cells.lastIndex) style.copy(fontWeight = FontWeight.Bold) else style,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    line({}, heads, bold = false, color = Palette.TextFaint)
    listOf(game.away to game.awayLineScores, game.home to game.homeLineScores).forEach { (abbr, scores) ->
        val total = if (game.state == GameState.PRE) "" else scoreOf(game, home = abbr == game.home)
        line(
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TeamLogo(abbr, ctx.view.team(abbr), 16.dp)
                    Text(abbr, style = style.copy(fontWeight = FontWeight.Medium))
                }
            },
            (0 until quarters).map { scores.getOrNull(it)?.toString().orEmpty() } + total,
            bold = true,
        )
    }
}

/** Team stats as paired bars: the away team's value and bar, the stat's name, the home team's bar and value. */
@Composable
private fun Stats(ctx: NflContext, game: NflGame, detail: NflGameDetail) {
    val away = detail.awayStats.associateBy { it.label }
    val home = detail.homeStats.associateBy { it.label }
    val labels = (detail.awayStats.map { it.label } + detail.homeStats.map { it.label }).distinct()
    if (labels.isEmpty()) {
        Text("ESPN has no team stats for this game.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
        return
    }
    val awayColor = NflColors.wash(ctx.view.team(game.away))
    val homeColor = NflColors.wash(ctx.view.team(game.home))
    labels.forEach { label ->
        val a = away[label]
        val h = home[label]
        val an = a?.let(::number) ?: 0f
        val hn = h?.let(::number) ?: 0f
        val total = (an + hn).takeIf { it > 0f } ?: 1f
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(a?.value.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            Bar(an / total, awayColor, fromEnd = true, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(96.dp))
            Bar(hn / total, homeColor, fromEnd = false, modifier = Modifier.weight(1f))
            Text(h?.value.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(44.dp))
        }
    }
}

/** A stat's bar, [fraction] of the track, from its end towards the middle. */
@Composable
private fun Bar(fraction: Float, color: Color, fromEnd: Boolean, modifier: Modifier) {
    Box(modifier.height(6.dp).background(NflColors.Hairline, RoundedCornerShape(3.dp)), contentAlignment = if (fromEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color, RoundedCornerShape(3.dp)))
    }
}

/** A stat's number: "257", or "22:39" as seconds. */
private fun number(stat: NflStat): Float? {
    val value = stat.value.trim()
    if (':' in value) {
        val (minutes, seconds) = value.split(':').map { it.toFloatOrNull() ?: return null }.let { it[0] to it.getOrElse(1) { 0f } }
        return minutes * 60 + seconds
    }
    return value.replace(",", "").toFloatOrNull()
}
