package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflBracket
import com.phonoodlesramen.actioncenter.nfl.NflConferenceBracket
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflLoad
import com.phonoodlesramen.actioncenter.nfl.NflMatchup
import com.phonoodlesramen.actioncenter.nfl.NflRules
import com.phonoodlesramen.actioncenter.nfl.NflStanding
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.nfl.SeasonPhase
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.Instant

/**
 * Playoffs (F4): seven columns (Wild card, Divisional, AFC, Super Bowl, NFC, Divisional, Wild card)
 * with hairline connectors, from [NflRules.bracket].
 *
 * - **In the season:** "If the season ended today", from the seeds: the top seed's "First-round
 *   bye", the rounds to come as dashed boxes, and who's in the hunt (seeds 8-10) at the foot.
 * - **In January:** the real games as they're played, with scores and the network under each; OK
 *   on a game that's on watches it. Earlier rounds load from the station (or ESPN).
 * - **In the preseason:** last season's bracket and final standings (D).
 *
 * OK on a team opens its page.
 */
@Composable
internal fun PlayoffsTab(ctx: NflContext, focusKey: String?, modifier: Modifier = Modifier) {
    val state = ctx.state
    val preseason = state.standingsSeason != state.seasonYear
    val january = state.phase == SeasonPhase.POSTSEASON
    val standings = ctx.view.standings(ctx.nowMillis)
    // The rounds already played: this week's from the copy, the others from the station (or ESPN).
    val rounds by produceState<List<NflGame>>(emptyList(), state.seasonYear, state.week, preseason, january) {
        value = when {
            preseason -> (1..LAST_ROUND).flatMap { round -> (ctx.controller.week(POSTSEASON, round, state.standingsSeason) as? NflLoad.Loaded)?.value?.games.orEmpty() }
            january -> {
                val current = state.week?.takeIf { it.seasonType == POSTSEASON }
                val earlier = state.calendar.filter { it.seasonType == POSTSEASON && current != null && it.week < current.week }
                earlier.flatMap { ref -> (ctx.controller.week(POSTSEASON, ref.week, state.seasonYear) as? NflLoad.Loaded)?.value?.games.orEmpty() } +
                    current?.games.orEmpty()
            }
            else -> emptyList()
        }
    }
    val bracket = NflRules.bracket(standings, state.teams, rounds)
    val first = bracket.afc.bye?.let { rowKey(it, ROUND_WILD_CARD) }
    LaunchedEffect(focusKey, first) { ctx.focus.focus(focusKey, *listOfNotNull(first, tabKey(NflTab.PLAYOFFS)).toTypedArray()) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val (title, detail) = when {
                preseason -> "The ${state.standingsSeason} playoffs" to "last season's final standings until Week 1"
                january -> "The playoffs" to (state.week?.label ?: "")
                else -> "If the season ended today" to "seeds after ${state.week?.label ?: "this week"}"
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text("· $detail", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Bracket(ctx, bracket, preseason || january, superBowl(state))
        Spacer(Modifier.height(10.dp))
        if (!preseason && !january) Hunt(ctx, bracket)
        Text(
            "Seeds 1-4 are the division leaders, 5-7 the wild cards. The divisional round re-seeds: the top seed always hosts the lowest one left.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextFaint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private const val POSTSEASON = 3
private const val LAST_ROUND = 5
private const val ROUND_WILD_CARD = "wc"
private const val ROUND_DIVISIONAL = "div"
private const val ROUND_CONFERENCE = "conf"
private const val ROUND_SUPER_BOWL = "sb"

private fun rowKey(abbr: String, round: String) = "po:$abbr:$round"

/** "Super Bowl LXI" and "Feb 2027", from the season and the calendar's last week. */
private fun superBowl(state: com.phonoodlesramen.actioncenter.nfl.NflState): Pair<String, String?> {
    val season = state.standingsSeason
    val name = "Super Bowl ${roman(season - FIRST_SUPER_BOWL_SEASON + 1)}"
    val month = state.calendar.lastOrNull { it.seasonType == POSTSEASON }?.takeIf { season == state.seasonYear }?.let { ref ->
        val local = Instant.ofEpochMilli(ref.startMillis).atZone(java.time.ZoneOffset.UTC)
        "${local.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)} ${local.year}"
    }
    return name to month
}

/** The 1966 season's game was Super Bowl I. */
private const val FIRST_SUPER_BOWL_SEASON = 1966

private fun roman(number: Int): String {
    var n = number
    val out = StringBuilder()
    for ((value, letters) in listOf(50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
        while (n >= value) {
            out.append(letters)
            n -= value
        }
    }
    return out.toString()
}

/** Where a column and a box sit, in dp: seven columns across the width, four wild-card rows down. */
private class BracketGrid(width: Dp) {
    val gap = width * (13f / 864f)
    val box = (width - gap * 6) / 7
    fun x(column: Int): Dp = (box + gap) * column
    fun y(row: Float): Dp = LABEL + LABEL_GAP + STEP * row

    companion object {
        val LABEL = 16.dp
        val LABEL_GAP = 8.dp
        val STEP = 75.dp
        val BOX = 48.dp
        val HEIGHT = LABEL + LABEL_GAP + STEP * 3 + BOX + 18.dp
    }
}

@Composable
private fun Bracket(ctx: NflContext, bracket: NflBracket, real: Boolean, superBowl: Pair<String, String?>) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(BracketGrid.HEIGHT)) {
        val grid = BracketGrid(maxWidth)
        Connectors(grid)
        listOf("Wild card", "Divisional", "AFC", "Super Bowl", "NFC", "Divisional", "Wild card").forEachIndexed { column, label ->
            Box(Modifier.offset(x = grid.x(column)).width(grid.box), contentAlignment = Alignment.Center) {
                Eyebrow(label, color = if (column == 3) NflColors.Gold else Palette.TextDim)
            }
        }
        Box(Modifier.fillMaxSize().focusRestorer().focusGroup()) {
            ConferenceSide(ctx, bracket.afc, grid, column = 0, inward = 1, real)
            ConferenceSide(ctx, bracket.nfc, grid, column = 6, inward = -1, real)
            val sb = bracket.superBowl
            BracketBox(ctx, grid, 3, 1.5f) {
                if (sb != null) Matchup(ctx, sb, ROUND_SUPER_BOWL, seeds = null) else Dashed(listOfNotNull(superBowl.first, superBowl.second).joinToString("\n"), gold = true)
            }
        }
    }
}

/** One conference's three columns, from the wild card ([column]) towards the Super Bowl ([inward]). */
@Composable
private fun ConferenceSide(ctx: NflContext, side: NflConferenceBracket, grid: BracketGrid, column: Int, inward: Int, real: Boolean) {
    val seeds = side.seeds.associate { it.abbr to (it.seed ?: 0) }
    val top = side.bye
    val topSeed = side.seeds.firstOrNull { it.abbr == top }
    BracketBox(ctx, grid, column, 0f) {
        if (topSeed != null) {
            Column(Modifier.fillMaxSize().background(Palette.Surface, RoundedCornerShape(8.dp)).padding(vertical = 2.dp)) {
                SeedRow(ctx, topSeed.abbr, seeds[topSeed.abbr], ROUND_WILD_CARD, standing = topSeed)
                Text("First-round bye", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1, modifier = Modifier.padding(start = 7.dp))
            }
        } else {
            Dashed("Seeds come with the standings")
        }
    }
    side.wildCard.forEachIndexed { index, matchup ->
        BracketBox(ctx, grid, column, index + 1f, network = matchup.game.takeIf { real }) { Matchup(ctx, matchup, ROUND_WILD_CARD, seeds) }
    }
    val divisional = side.divisional
    BracketBox(ctx, grid, column + inward, 0.5f, network = divisional.getOrNull(0)?.game.takeIf { real }) {
        divisional.getOrNull(0)?.let { Matchup(ctx, it, ROUND_DIVISIONAL, seeds) } ?: Dashed(top?.let { "${seeds[it] ?: 1} $it hosts the lowest seed left" } ?: "The top seed hosts the lowest seed left")
    }
    BracketBox(ctx, grid, column + inward, 2.5f, network = divisional.getOrNull(1)?.game.takeIf { real }) {
        divisional.getOrNull(1)?.let { Matchup(ctx, it, ROUND_DIVISIONAL, seeds) } ?: Dashed("The other two winners")
    }
    BracketBox(ctx, grid, column + 2 * inward, 1.5f, network = side.championship?.game.takeIf { real }) {
        side.championship?.let { Matchup(ctx, it, ROUND_CONFERENCE, seeds) } ?: Dashed("${side.conference} Championship")
    }
}

/** A box in the bracket at [column] and [row] (wild-card rows 0-3; halves between), with the game's network under it in January. */
@Composable
private fun BracketBox(ctx: NflContext, grid: BracketGrid, column: Int, row: Float, network: NflGame? = null, content: @Composable () -> Unit) {
    Column(Modifier.offset(x = grid.x(column), y = grid.y(row)).width(grid.box)) {
        Box(Modifier.fillMaxWidth().height(BracketGrid.BOX)) { content() }
        network?.let { game ->
            Text(NflText.network(game), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Two teams that meet, the better seed first; once it's begun, the score; the winner bold and the loser faint. */
@Composable
private fun Matchup(ctx: NflContext, matchup: NflMatchup, round: String, seeds: Map<String, Int>?) {
    val game = matchup.game
    Column(Modifier.fillMaxSize().background(Palette.Surface, RoundedCornerShape(8.dp)).padding(vertical = 2.dp)) {
        listOf(matchup.high, matchup.low).forEach { abbr ->
            SeedRow(ctx, abbr, seeds?.get(abbr), round, game = game)
        }
    }
}

/**
 * A team in a bracket box: the seed, the logo, the abbreviation and the record, or the score once
 * the game has begun (hidden while Hide scores hides it). OK opens the team's page, or watches a
 * game that's on.
 */
@Composable
private fun SeedRow(ctx: NflContext, abbr: String, seed: Int?, round: String, standing: NflStanding? = null, game: NflGame? = null) {
    val key = rowKey(abbr, round)
    val view = ctx.view
    val hidden = game != null && view.hidden(game, ctx.nowMillis)
    val lost = game != null && !hidden && game.state == GameState.FINAL && game.winner != null && game.winner != abbr
    val won = game != null && !hidden && game.winner == abbr
    val record = standing?.record ?: view.standings(ctx.nowMillis).firstOrNull { it.abbr == abbr }?.record.orEmpty()
    val right = when {
        game == null || game.state == GameState.PRE -> record
        hidden -> ""
        else -> scoreOf(game, home = game.home == abbr)
    }
    Surface(
        onClick = { if (game != null && game.state == GameState.LIVE) watchFirst(game, view, ctx.watch, ctx.actions) else ctx.team(abbr, key) },
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .focusRequester(ctx.focus.requester(key))
            .onFocused { ctx.focus.focused(key) }
            .semantics { contentDescription = view.team(abbr)?.fullName ?: abbr },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (lost) Palette.TextFaint else Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(seed?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, textAlign = TextAlign.End, modifier = Modifier.width(12.dp))
            TeamLogo(abbr, view.team(abbr), 16.dp)
            Text(abbr, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Spacer(Modifier.weight(1f))
            WholeOrNothing {
                Text(right, style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (won) FontWeight.Bold else FontWeight.Normal), color = if (game == null) Palette.TextFaint else Palette.Text, maxLines = 1)
            }
        }
    }
}

/** A round to come: a dashed box saying who'll be in it. */
@Composable
private fun Dashed(text: String, gold: Boolean = false) {
    val edge = if (gold) NflColors.Gold else NflColors.Connector
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRoundRect(
                color = edge,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            )
        }.padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp),
            color = if (gold) NflColors.Gold else Palette.TextFaint,
            textAlign = if (gold) TextAlign.Center else TextAlign.Start,
            maxLines = 2,
            modifier = if (gold) Modifier.fillMaxWidth() else Modifier,
        )
    }
}

/** The hairline connectors: wild card to divisional, divisional to the conference, the conference to the Super Bowl. */
@Composable
private fun Connectors(grid: BracketGrid) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val path = Path()
            val box = grid.box.toPx()
            val half = BracketGrid.BOX.toPx() / 2
            fun x(column: Int) = grid.x(column).toPx()
            fun y(row: Float) = grid.y(row).toPx() + half
            for ((column, dir) in listOf(0 to 1, 6 to -1)) {
                val from = x(column) + if (dir > 0) box else 0f
                val to = x(column + dir) + if (dir > 0) 0f else box
                val mid = (from + to) / 2
                for ((a, b, target) in listOf(Triple(0f, 1f, 0.5f), Triple(2f, 3f, 2.5f))) {
                    for (row in listOf(a, b)) {
                        path.moveTo(from, y(row))
                        path.lineTo(mid, y(row))
                        path.lineTo(mid, y(target))
                        path.lineTo(to, y(target))
                    }
                }
                val from2 = x(column + dir) + if (dir > 0) box else 0f
                val to2 = x(column + 2 * dir) + if (dir > 0) 0f else box
                val mid2 = (from2 + to2) / 2
                for (row in listOf(0.5f, 2.5f)) {
                    path.moveTo(from2, y(row))
                    path.lineTo(mid2, y(row))
                    path.lineTo(mid2, y(1.5f))
                    path.lineTo(to2, y(1.5f))
                }
                val from3 = x(column + 2 * dir) + if (dir > 0) box else 0f
                val to3 = x(3) + if (dir > 0) 0f else box
                path.moveTo(from3, y(1.5f))
                path.lineTo(to3, y(1.5f))
            }
            drawPath(path, NflColors.Connector, style = Stroke(width = 1.5.dp.toPx()))
        },
    )
}

/** "AFC in the hunt: 8 PIT 5-3 · 9 BAL 4-4 · 10 LV 3-4", each conference's seeds 8-10. */
@Composable
private fun Hunt(ctx: NflContext, bracket: NflBracket) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf(bracket.afc, bracket.nfc).forEach { side ->
            Column(Modifier.weight(1f)) {
                Eyebrow("${side.conference} in the hunt")
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    side.hunt.forEach { standing ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${standing.seed}", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                            TeamLogo(standing.abbr, ctx.view.team(standing.abbr), 14.dp)
                            Text("${standing.abbr} ${standing.record}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
