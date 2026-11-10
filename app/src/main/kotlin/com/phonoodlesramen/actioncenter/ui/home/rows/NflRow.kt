package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflDivisionCard
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflRowModel
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.home.tileColors
import com.phonoodlesramen.actioncenter.ui.nfl.NetworkBadge
import com.phonoodlesramen.actioncenter.ui.nfl.NflColors
import com.phonoodlesramen.actioncenter.ui.nfl.NflOpen
import com.phonoodlesramen.actioncenter.ui.nfl.NflPage
import com.phonoodlesramen.actioncenter.ui.nfl.NflTab
import com.phonoodlesramen.actioncenter.ui.nfl.Possession
import com.phonoodlesramen.actioncenter.ui.nfl.StandingsView
import com.phonoodlesramen.actioncenter.ui.nfl.TeamLogo
import com.phonoodlesramen.actioncenter.ui.nfl.gameSheet
import com.phonoodlesramen.actioncenter.ui.nfl.matchup
import com.phonoodlesramen.actioncenter.ui.nfl.scoreOf
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper

/**
 * The NFL row (Phase 13, F1, F3; ui-spec "The NFL"), from the preseason to the day after the Super
 * Bowl, never on a kid's home ([com.phonoodlesramen.actioncenter.nfl.NflHome.row]). The row anatomy
 * ([OtherAppsRowFrame]): the label ("NFL · WEEK 8 · 1 LIVE"), then
 *
 * - **All games:** the NFL screen at this week;
 * - **the games**, live (yours first) and the next kickoffs ([GameCard]);
 * - **your division**, from your first team: Standings › Division;
 * - **the week's finals**, newest first.
 *
 * OK on a game opens the NFL screen on it (F3); hold OK or Menu gives the ways to watch it, Details,
 * both teams, and Show score while hidden ([gameSheet]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NflRow(
    row: NflRowModel,
    view: NflView,
    nowMillis: Long,
    watch: WatchOptions,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalNflRowFocus.current ?: remember { NflRowFocus() }
    val keys = remember(row) {
        buildList {
            add(NflOpen.ALL_GAMES)
            addAll(row.games.map { it.id })
            if (row.division != null) add(NflOpen.DIVISION)
            addAll(row.finals.map { it.id })
        }
    }
    SideEffect { focus.keys = keys }
    OtherAppsRowFrame(label = row.label, modifier = modifier, state = focus.list) {
        item(key = NflOpen.ALL_GAMES) { AllGamesCard(row, actions, focus.card(NflOpen.ALL_GAMES)) }
        items(row.games, key = { it.id }) { game -> GameCard(game, view, nowMillis, watch, actions, openSheet, focus.card(game.id)) }
        row.division?.let { division -> item(key = NflOpen.DIVISION) { DivisionCard(division, view, actions, focus.card(NflOpen.DIVISION)) } }
        items(row.finals, key = { it.id }) { game -> GameCard(game, view, nowMillis, watch, actions, openSheet, focus.card(game.id)) }
    }
}

/** The NFL row's scroll position and cards, held by the home so Back from the NFL screen lands on the card that opened it, as [LiveRowFocus] does. */
@Stable
class NflRowFocus {
    val list = LazyListState()
    private val cards = mutableMapOf<String, FocusRequester>()

    /** The row's cards' keys, in order ([NflOpen.ALL_GAMES], game ids, [NflOpen.DIVISION]). */
    internal var keys: List<String> = emptyList()

    fun card(key: String): FocusRequester = cards.getOrPut(key) { FocusRequester() }

    /** Focuses [key]'s card, scrolled into the row if need be. False if it has left the row. */
    suspend fun focus(key: String): Boolean {
        withFrameNanos {}
        val index = keys.indexOf(key).takeIf { it >= 0 } ?: return false
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            list.scrollToItem(index)
            withFrameNanos {}
        }
        return runCatching { card(key).requestFocus() }.isSuccess
    }
}

/** The home provides one; elsewhere the row keeps its own. */
val LocalNflRowFocus = staticCompositionLocalOf<NflRowFocus?> { null }

/** All games: a navy card with the football, "NFL" and "Week 8 · 1 live". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllGamesCard(row: NflRowModel, actions: HomeActions, self: FocusRequester) {
    val metrics = LocalRowMetrics.current
    val shape = RoundedCornerShape(Layout.TileRadius)
    val open = { actions.openNfl(NflOpen(NflPage.Tab(NflTab.GAMES), NflOpen.ALL_GAMES)) }
    Column(Modifier.width(metrics.cardWidth)) {
        Surface(
            onClick = open,
            modifier = Modifier
                .rowCard(metrics)
                .bringIntoViewResponder(rememberCaptionRoom())
                .focusRequester(self)
                .semantics { contentDescription = "All games" },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = tileColors(container = Color.Transparent),
            scale = cardLift,
            border = focusRing(shape),
        ) {
            ShownWhole(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(NflColors.NavyFrom, NflColors.NavyTo)))) {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AppIcons.Football, contentDescription = null, tint = NflColors.Gold, modifier = Modifier.size(26.dp))
                        Text("NFL", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp), color = Palette.Text)
                    }
                    Text(
                        text = listOfNotNull(row.week, if (row.live > 0) "${row.live} live" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextDim,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Captions("All games", "Scores, standings, playoffs")
    }
}

/**
 * A game in the row (160×90): two team lines (logo, abbreviation, a star for yours, the
 * possession marker, the score), a status column (LIVE with the clock, the day and time, or Final)
 * with the network's badge, and the away colour washing in from the left. A final's loser is faint.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameCard(
    game: NflGame,
    view: NflView,
    nowMillis: Long,
    watch: WatchOptions,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    self: FocusRequester,
) {
    val metrics = LocalRowMetrics.current
    val shape = RoundedCornerShape(Layout.TileRadius)
    val hidden = view.hidden(game, nowMillis)
    val sheet = {
        openSheet(
            gameSheet(
                game, view, nowMillis, watch, actions, self,
                details = { actions.openNfl(NflOpen(NflPage.Game(game.id), game.id)) },
                team = { actions.openNfl(NflOpen(NflPage.Team(it), game.id)) },
            ),
        )
    }
    val wash = Brush.horizontalGradient(0f to NflColors.wash(view.team(game.away)).copy(alpha = 0.25f), 0.45f to Color.Transparent)
    Column(Modifier.width(metrics.cardWidth)) {
        Surface(
            onClick = { actions.openNfl(NflOpen(NflPage.Tab(NflTab.GAMES, gameId = game.id), game.id)) },
            onLongClick = sheet,
            modifier = Modifier
                .rowCard(metrics)
                .bringIntoViewResponder(rememberCaptionRoom())
                .focusRequester(self)
                .onMenuKey(sheet)
                .semantics { contentDescription = matchup(game, view) },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = tileColors(container = Palette.Surface),
            scale = cardLift,
            border = focusRing(shape),
        ) {
            // Text: hidden while the row peeks at the screen's foot (TV-OV); the wash and logos may show.
            ShownWhole(Modifier.fillMaxSize().background(wash)) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TeamLine(game, game.away, home = false, view, hidden)
                        TeamLine(game, game.home, home = true, view, hidden)
                    }
                    Column(
                        Modifier.fillMaxHeight().padding(start = 4.dp, end = 7.dp, top = 9.dp, bottom = 7.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        GameStatus(game, view)
                        NetworkBadge(NflText.network(game))
                    }
                }
            }
        }
        Captions(
            matchup(game, view),
            when (game.state) {
                GameState.LIVE -> NflText.staleScores(view, nowMillis) ?: game.downDistance ?: NflText.clock(game)
                GameState.PRE -> "${NflText.network(game)} · ${NflText.kickoff(game, view.zone)}"
                GameState.FINAL -> "${NflText.day(game.kickoffMillis, view.zone)} · ${NflText.network(game)}"
            },
        )
    }
}

/** One team in a row's game card: logo, abbreviation, a star for yours, possession, then the score on the right. */
@Composable
private fun TeamLine(game: NflGame, abbr: String, home: Boolean, view: NflView, hidden: Boolean) {
    val lost = !hidden && game.state == GameState.FINAL && game.winner != null && game.winner != abbr
    val color = if (lost) Palette.TextFaint else Palette.Text
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TeamLogo(abbr, view.team(abbr), 22.dp)
        Text(abbr, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = color, maxLines = 1)
        if (abbr in view.settings.yourTeams) Icon(AppIcons.Star, contentDescription = "Your team", tint = LocalAccent.current, modifier = Modifier.size(12.dp))
        if (!hidden && game.state == GameState.LIVE && game.possession == abbr) Possession(towardsStart = true)
        Spacer(Modifier.weight(1f))
        if (!hidden) {
            Text(
                text = scoreOf(game, home),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = color,
                maxLines = 1,
            )
        }
    }
}

/** The status column's top: LIVE with the clock and quarter, the day and time, or Final; "Score hidden" says so. */
@Composable
private fun GameStatus(game: NflGame, view: NflView) {
    val style = MaterialTheme.typography.bodySmall
    Column(horizontalAlignment = Alignment.End) {
        when (game.state) {
            // LIVE alone, without its dot: the card is narrow, and the score comes first.
            GameState.LIVE -> {
                Text("LIVE", style = style.copy(fontWeight = FontWeight.Bold), color = NflColors.Live, maxLines = 1)
                NflText.clock(game).split(' ').forEach { Text(it, style = style, color = Palette.Text, maxLines = 1) }
            }
            GameState.PRE -> {
                Text(NflText.day(game.kickoffMillis, view.zone), style = style, color = Palette.TextDim, maxLines = 1)
                Text(if (game.kickoffKnown) NflText.time(game.kickoffMillis, view.zone) else "TBD", style = style, color = Palette.TextDim, maxLines = 1)
            }
            GameState.FINAL -> Text(NflText.final(game), style = style, color = Palette.TextDim, maxLines = 1)
        }
    }
}

/** Your division (F5): four rows of a logo, the abbreviation and the record, the leader first; OK opens Standings › Division. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DivisionCard(division: NflDivisionCard, view: NflView, actions: HomeActions, self: FocusRequester) {
    val metrics = LocalRowMetrics.current
    val shape = RoundedCornerShape(Layout.TileRadius)
    val mine = view.settings.yourTeams.toSet()
    Column(Modifier.width(metrics.cardWidth)) {
        Surface(
            onClick = { actions.openNfl(NflOpen(NflPage.Tab(NflTab.STANDINGS, standings = StandingsView.DIVISION), NflOpen.DIVISION)) },
            modifier = Modifier
                .rowCard(metrics)
                .bringIntoViewResponder(rememberCaptionRoom())
                .focusRequester(self)
                .semantics { contentDescription = division.name },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = tileColors(container = Palette.Surface),
            scale = cardLift,
            border = focusRing(shape),
        ) {
            ShownWhole(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 5.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    division.teams.take(4).forEach { team ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TeamLogo(team.abbr, view.team(team.abbr), 14.dp)
                            Text(
                                team.abbr,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (team.abbr in mine) FontWeight.Bold else FontWeight.Medium),
                                color = Palette.Text,
                                maxLines = 1,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(team.record, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
                        }
                    }
                }
            }
        }
        Captions(division.name, "Standings")
    }
}

/** A card's two captions, hidden while the row peeks at the screen's foot (TV-OV). */
@Composable
private fun Captions(title: String, detail: String) {
    ShownWhole {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.onWallpaper(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall.onWallpaper(),
                color = Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
