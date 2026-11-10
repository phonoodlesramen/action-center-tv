package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflLoad
import com.phonoodlesramen.actioncenter.nfl.NflRules
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.nfl.NflWeek
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * Games (F4): the info panel for the focused game, then the week's games by time slot
 * ([NflRules.slots]), live first in each, three tiles across, and the byes at the foot. Another
 * week loads from the station (or ESPN): "Loading…" until it's there.
 */
@Composable
internal fun GamesTab(ctx: NflContext, page: NflPage.Tab, focusKey: String?, modifier: Modifier = Modifier) {
    val state = ctx.state
    val (type, number) = shownWeekRef(state, page)
    val own = state.week?.takeIf { it.seasonType == type && it.week == number }
    val load by produceState<NflLoad<NflWeek>>(if (own != null) NflLoad.Loaded(own) else NflLoad.Loading, type, number, own) {
        value = own?.let { NflLoad.Loaded(it) } ?: ctx.controller.week(type, number, state.seasonYear)
    }
    Column(modifier) {
        when (val loaded = load) {
            NflLoad.Loading -> Quietly("Loading…", Modifier.fillMaxWidth())
            is NflLoad.Failed -> Quietly(loaded.sentence, Modifier.fillMaxWidth(), failed = true)
            is NflLoad.Loaded -> WeekGames(ctx, loaded.value, page, focusKey)
        }
    }
}

@Composable
private fun WeekGames(ctx: NflContext, week: NflWeek, page: NflPage.Tab, focusKey: String?) {
    val view = ctx.view
    val slots = remember(week, view.zone) { NflRules.slots(week, view.zone) }
    val games = slots.flatMap { it.games }
    // The card's game, or the featured one: what the panel shows before anything has focus.
    val opening = page.gameId?.let { id -> games.firstOrNull { it.id == id } }
        ?: NflRules.featured(week, view.settings.yourTeams, ctx.nowMillis)?.takeIf { featured -> games.any { it.id == featured.id } }
        ?: games.firstOrNull()
    var shown by remember(week) { mutableStateOf(opening) }
    val list = rememberLazyListState()
    LaunchedEffect(week, focusKey) {
        // Scrolled to the game first, so its tile is there to focus.
        val target = focusKey?.removePrefix(GAME)?.let { id -> games.firstOrNull { it.id == id } } ?: opening
        val row = rowIndexOf(slots, target)
        if (row != null) list.scrollToItem(row)
        ctx.focus.focus(focusKey?.takeIf { !it.startsWith(GAME) }, *listOfNotNull(target?.let { GAME + it.id }).toTypedArray(), tabKey(NflTab.GAMES))
    }
    shown?.let { InfoPanel(ctx, it, Modifier.fillMaxWidth().height(128.dp)) }
    Spacer(Modifier.height(4.dp))
    // Room under the last games, so any slot can scroll up to the top of the list and none
    // starts half under the panel (the week's last slots are short).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(bottom = (maxHeight - LAST_ROOM).coerceAtLeast(0.dp)),
        ) {
            slots.forEach { slot ->
                val live = slot.games.any { it.state == GameState.LIVE }
                item(key = "slot:${slot.label}") {
                    SlotHeader(slot.label, slot.games.size, live)
                }
                items(slot.games.chunked(ACROSS), key = { row -> "row:" + row.joinToString(",") { it.id } }) { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { game -> GameTile(ctx, game, Modifier.weight(1f)) { shown = game } }
                        repeat(ACROSS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (week.byes.isNotEmpty()) item(key = "byes") { Byes(ctx, week.byes) }
        }
    }
}

/** What stays of the list's height under its padding: a slot's header and a row of tiles. */
private val LAST_ROOM = 110.dp

/**
 * Where to scroll the list for [game]: its slot's header when it's in the slot's first row, so the
 * header shows over it, else its own row. Each slot is its header, then its rows.
 */
private fun rowIndexOf(slots: List<com.phonoodlesramen.actioncenter.nfl.NflSlot>, game: NflGame?): Int? {
    if (game == null) return null
    var index = 0
    for (slot in slots) {
        val header = index
        index++
        val at = slot.games.indexOfFirst { it.id == game.id }
        if (at >= 0) return if (at < ACROSS) header else index + at / ACROSS
        index += (slot.games.size + ACROSS - 1) / ACROSS
    }
    return null
}

private const val ACROSS = 3
internal const val GAME = "game:"

/** "SUNDAY · NOV 1 · 1:00 · 8 GAMES · LIVE". */
@Composable
private fun SlotHeader(label: String, count: Int, live: Boolean) {
    val style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp)
    Row(Modifier.padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Eyebrow("$label · $count game${if (count == 1) "" else "s"}")
        if (live) {
            Text("·", style = style, color = Palette.TextDim)
            Text("LIVE", style = style.copy(fontWeight = FontWeight.Bold), color = NflColors.Live)
        }
    }
}

/**
 * A game's tile (280×62 on the canvas, a third of the width): two team lines (logo, name, a star
 * for yours, the record faint, the possession marker, the score), then past a hairline the status
 * and the network's badge. A live tile washes red from the left; a final's loser is faint.
 */
@Composable
private fun GameTile(ctx: NflContext, game: NflGame, modifier: Modifier = Modifier, onFocus: () -> Unit) {
    val view = ctx.view
    val key = GAME + game.id
    val hidden = view.hidden(game, ctx.nowMillis)
    val shape = RoundedCornerShape(Layout.TileRadius)
    val live = game.state == GameState.LIVE
    Surface(
        onClick = { ctx.ok(game, key) },
        onLongClick = { ctx.sheet(game, key) },
        modifier = modifier
            .height(62.dp)
            .focusRequester(ctx.focus.requester(key))
            .onFocused {
                ctx.focus.focused(key)
                onFocus()
            }
            .onMenuKey { ctx.sheet(game, key) }
            .semantics { contentDescription = matchup(game, view) },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = focusRing(shape),
    ) {
        val wash = if (live) Brush.horizontalGradient(0f to NflColors.Live.copy(alpha = 0.10f), 0.4f to Color.Transparent) else null
        Row(Modifier.fillMaxSize().then(if (wash != null) Modifier.background(wash) else Modifier)) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                TileTeam(ctx, game, game.away, home = false, hidden)
                TileTeam(ctx, game, game.home, home = true, hidden)
            }
            Box(Modifier.padding(vertical = 8.dp).width(1.dp).fillMaxHeight().background(NflColors.Hairline))
            Column(
                Modifier.fillMaxHeight().padding(horizontal = 8.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                TileStatus(ctx, game, hidden)
                NetworkBadge(NflText.network(game))
            }
        }
    }
}

@Composable
private fun TileTeam(ctx: NflContext, game: NflGame, abbr: String, home: Boolean, hidden: Boolean) {
    val view = ctx.view
    val team = view.team(abbr)
    val lost = !hidden && game.state == GameState.FINAL && game.winner != null && game.winner != abbr
    val color = if (lost) Palette.TextFaint else Palette.Text
    val yours = abbr in view.settings.yourTeams
    val record = if (home) game.homeRecord else game.awayRecord
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TeamLogo(abbr, team, 22.dp)
        // The name first: the record (faint) goes where both don't fit, then the name shrinks.
        NameThenRecord(
            name = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FitText(
                        team?.name ?: abbr,
                        style = MaterialTheme.typography.titleSmall,
                        smaller = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = color,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (yours) Icon(AppIcons.Star, contentDescription = "Your team", tint = LocalAccent.current, modifier = Modifier.size(13.dp))
                }
            },
            record = { if (record != null) Text(record, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1) },
            modifier = Modifier.weight(1f),
        )
        if (!hidden && game.state == GameState.LIVE && game.possession == abbr) Possession(towardsStart = true)
        if (!hidden && game.state != GameState.PRE) {
            Text(
                text = scoreOf(game, home),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = color,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 20.dp),
            )
        }
    }
}

@Composable
private fun TileStatus(ctx: NflContext, game: NflGame, hidden: Boolean) {
    val style = MaterialTheme.typography.bodySmall
    when {
        game.state == GameState.LIVE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LiveDot()
            Text(NflText.clock(game), style = style.copy(fontWeight = FontWeight.Bold), color = NflColors.Live, maxLines = 1)
        }
        hidden -> Text("Hidden", style = style, color = Palette.TextDim, maxLines = 1)
        game.state == GameState.FINAL -> Text(NflText.final(game), style = style, color = Palette.TextDim, maxLines = 1)
        else -> Text(
            if (game.kickoffKnown) NflText.time(game.kickoffMillis, ctx.view.zone) else "TBD",
            style = style,
            color = Palette.TextDim,
            maxLines = 1,
        )
    }
}

/** "On a bye: (logo) Saints (logo) Giants …", at the foot. */
@Composable
private fun Byes(ctx: NflContext, byes: List<String>) {
    Column(Modifier.padding(top = 8.dp, bottom = 16.dp)) {
        Eyebrow("On a bye", Modifier.padding(bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            byes.forEach { abbr ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TeamLogo(abbr, ctx.view.team(abbr), 18.dp)
                    Text(ctx.view.team(abbr)?.name ?: abbr, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The info panel (864×128) for the focused game: both teams (52 dp logos, the name, the record),
 * the scores with the status over them, the situation or the venue under; on the right, past a
 * hairline, where to watch it and what OK does.
 */
@Composable
private fun InfoPanel(ctx: NflContext, game: NflGame, modifier: Modifier = Modifier) {
    val view = ctx.view
    val hidden = view.hidden(game, ctx.nowMillis)
    val ways = waysFor(game, view, ctx.watch)
    Row(modifier.background(Palette.Surface, RoundedCornerShape(Layout.CardRadius))) {
        Column(Modifier.weight(INFO_TEAMS).fillMaxHeight().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoSide(ctx, game, game.away, home = false, Modifier.weight(1f))
                InfoMiddle(ctx, game, hidden)
                InfoSide(ctx, game, game.home, home = true, Modifier.weight(1f))
            }
            Text(
                text = situation(ctx, game, hidden),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextDim,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(NflColors.Hairline))
        Column(Modifier.weight(INFO_SIDE).fillMaxHeight().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow("Where to watch")
            val first = ways.firstOrNull()
            Text(
                text = when {
                    first == null -> "Not announced yet"
                    first.installed -> first.label
                    else -> first.missing
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = if (first?.installed == false) Palette.TextDim else Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val also = ways.drop(1).joinToString(", ") { if (it.installed) it.label else "${it.app.label} (not on this TV)" }
            if (also.isNotEmpty()) Text("Also: $also", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (watchesOnOk(game, ctx.nowMillis)) "OK to watch · hold OK for more" else "OK for details · hold OK for more",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextFaint,
                maxLines = 1,
            )
        }
    }
}

/** The teams' part of the panel and the "where to watch" part: 564 and 300 of its 864 dp. */
private const val INFO_TEAMS = 564f
private const val INFO_SIDE = 300f

/** One team in the panel: the 52 dp logo, the name (20 sp, bold) and the record; the home team's mirrored. */
@Composable
private fun InfoSide(ctx: NflContext, game: NflGame, abbr: String, home: Boolean, modifier: Modifier = Modifier) {
    val view = ctx.view
    val team = view.team(abbr)
    val record = (if (home) game.homeRecord else game.awayRecord) ?: view.standings(ctx.nowMillis).firstOrNull { it.abbr == abbr }?.record.orEmpty()
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (home) Arrangement.spacedBy(10.dp, Alignment.End) else Arrangement.spacedBy(10.dp),
    ) {
        if (!home) TeamLogo(abbr, team, 52.dp)
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (home) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (home && abbr in view.settings.yourTeams) Icon(AppIcons.Star, contentDescription = "Your team", tint = LocalAccent.current, modifier = Modifier.size(14.dp))
                FitText(
                    team?.name ?: abbr,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                    smaller = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    smallest = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!home && abbr in view.settings.yourTeams) Icon(AppIcons.Star, contentDescription = "Your team", tint = LocalAccent.current, modifier = Modifier.size(14.dp))
            }
            Text(record, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
        }
        if (home) TeamLogo(abbr, team, 52.dp)
    }
}

/** The status over the scores ("● LIVE · 1:45 3RD", "FINAL", "MON 8:15 PM"), the scores (40 sp), or "Score hidden". */
@Composable
private fun InfoMiddle(ctx: NflContext, game: NflGame, hidden: Boolean) {
    val eyebrow = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp)
    Column(Modifier.padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (game.state) {
            GameState.LIVE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LiveMark(eyebrow)
                Text("· ${NflText.clock(game).uppercase()}", style = eyebrow, color = NflColors.Live, maxLines = 1)
            }
            GameState.FINAL -> Text(NflText.final(game).uppercase(), style = eyebrow, color = Palette.TextDim, maxLines = 1)
            GameState.PRE -> Text(NflText.kickoff(game, ctx.view.zone).uppercase(), style = eyebrow, color = Palette.TextDim, maxLines = 1)
        }
        val score = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
        when {
            hidden -> Text(NflText.HIDDEN, style = MaterialTheme.typography.titleMedium, color = Palette.TextDim, modifier = Modifier.padding(top = 8.dp))
            game.state == GameState.PRE -> Text("vs", style = MaterialTheme.typography.titleMedium, color = Palette.TextFaint, modifier = Modifier.padding(top = 8.dp))
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(scoreOf(game, home = false), style = score, maxLines = 1)
                Text("–", style = MaterialTheme.typography.titleLarge, color = Palette.TextDim)
                Text(scoreOf(game, home = true), style = score, maxLines = 1)
            }
        }
    }
}

/** Under the teams: the situation while it's on ("2nd & Goal at PHI 7 · Red zone"), else the venue; how old the scores are first. */
private fun situation(ctx: NflContext, game: NflGame, hidden: Boolean) = buildAnnotatedString {
    val parts = mutableListOf<() -> Unit>()
    NflText.staleScores(ctx.view, ctx.nowMillis)?.takeIf { game.state == GameState.LIVE }?.let { parts += { append(it) } }
    if (game.state == GameState.LIVE && !hidden) {
        game.downDistance?.let { dd -> parts += { append(dd) } }
        if (game.redZone) parts += { withStyle(SpanStyle(color = NflColors.Live)) { append("Red zone") } }
    }
    if (parts.isEmpty()) game.venue?.let { venue -> parts += { append(venue) } }
    if (game.headline != null && game.seasonType == POSTSEASON) parts += { append(game.headline) }
    parts.forEachIndexed { index, part ->
        if (index > 0) append(" · ")
        part()
    }
}

private const val POSTSEASON = 3
