package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.LogoSize
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflLogoFiles
import com.phonoodlesramen.actioncenter.nfl.NflTeam
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.nfl.WatchLinks
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import com.phonoodlesramen.actioncenter.nfl.WatchWay
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.SheetItem
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import androidx.compose.ui.focus.FocusRequester
import java.io.File

/** The NFL screen's tabs (F4). */
enum class NflTab(val label: String) {
    GAMES("Games"),
    STANDINGS("Standings"),
    PLAYOFFS("Playoffs"),
}

/** Standings' three views (F4). */
enum class StandingsView(val label: String) {
    DIVISION("Division"),
    CONFERENCE("Conference"),
    LEAGUE("League"),
}

/** A page of the NFL screen: a tab, a game's details (`Screen.NFL_GAME` in the spec) or a team's page (`Screen.NFL_TEAM`). */
@Immutable
sealed interface NflPage {
    /**
     * A tab. On Games, the week [seasonType] / [week] (null: the copy's), focused on [gameId]
     * (else the card's game); on Standings, the [standings] view.
     */
    data class Tab(
        val tab: NflTab,
        val gameId: String? = null,
        val standings: StandingsView = StandingsView.DIVISION,
        val seasonType: Int? = null,
        val week: Int? = null,
    ) : NflPage

    /** A game's details; [game] as the page that opened it had it (another week's), until its details load. */
    data class Game(val id: String, val game: NflGame? = null) : NflPage

    data class Team(val abbr: String) : NflPage
}

/**
 * How the NFL screen opens (F3): on [page], from [opener], the home's card that opened it, where
 * Back lands: [CARD], [ALL_GAMES], [DIVISION] or a game's id on the NFL row.
 */
@Immutable
data class NflOpen(val page: NflPage = NflPage.Tab(NflTab.GAMES), val opener: String = CARD) {
    companion object {
        const val CARD = "card"
        const val ALL_GAMES = "all-games"
        const val DIVISION = "division"
    }
}

/** The NFL's own colours, as the design mock-up chose them. */
object NflColors {
    /** LIVE, its dot, the red zone, and a live tile's wash. */
    val Live = Color(0xFFFF6B6B)

    /** The possession marker. */
    val Possession = Color(0xFFFFD54F)

    /** The football icon, and the Super Bowl's box. */
    val Gold = Color(0xFFC9A46A)

    /** The All games card. */
    val NavyFrom = Color(0xFF13294B)
    val NavyTo = Color(0xFF0B1524)

    /** Standings' division eyebrows. */
    val Afc = Color(0xFFEF9A9A)
    val Nfc = Color(0xFF90CAF9)

    /** The hairlines between a tile's teams and its status, and the info panel's rule. */
    val Hairline = Color(0xFF263140)

    /** The bracket's connectors and its rounds to come. */
    val Connector = Color(0xFF344150)

    /** A network badge. */
    val BadgeFill = Color(0xFF0B0F13)
    val BadgeEdge = Color(0xFF3A4654)
    val BadgeText = Color(0xFFDFE6EE)

    /** The leader's row in a division box. */
    val Leader = Color(0xFF1D2733)

    /** [hex] ("#06424D") as a colour; grey if it isn't one. */
    fun of(hex: String?): Color = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color(0xFF5B6573)

    /** A team's colour for a wash or a bar: its own, or its second when the first is too dark to see on the surface. */
    fun wash(team: NflTeam?): Color {
        val main = of(team?.color)
        return if (main.luminance() < 0.01f) of(team?.altColor).takeIf { it.luminance() >= 0.01f } ?: Color(0xFF8A96A3) else main
    }
}

/** Where logos come from: the app's cache through [NflLogoFiles]; screenshots and previews have none. */
val LocalNflLogos = staticCompositionLocalOf { NflLogoFiles.None }

/**
 * A team's logo at [size] (Phase 13): from the cache at the size drawn, never decoded larger
 * ([LogoSize.forPx]). Until it's there, or without one, the mock's stand-in: a circle in the
 * team's colour ringed in its second colour, with the abbreviation when it's big enough to read
 * (12 sp or more); smaller ones always have the abbreviation beside them.
 */
@Composable
fun TeamLogo(abbr: String, team: NflTeam?, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val logos = LocalNflLogos.current
    val file by produceState<File?>(null, abbr, px, logos) { value = logos.file(abbr, LogoSize.forPx(px)) }
    val context = LocalContext.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val loaded = file
        if (loaded == null) {
            StandIn(abbr, team, size)
        } else {
            AsyncImage(
                model = remember(loaded, px) { ImageRequest.Builder(context).data(loaded).size(px, px).build() },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** The mock's stand-in: the team's colour, ringed, with its abbreviation from 12 sp up. */
@Composable
private fun StandIn(abbr: String, team: NflTeam?, size: Dp) {
    val fill = NflColors.of(team?.color)
    val second = NflColors.of(team?.altColor)
    // A black second colour vanishes on the dark surface: a grey ring instead (the Eagles, the Raiders).
    val ring = if (second.luminance() < 0.02f) Color(0xFF8A96A3) else second
    val ink = if (fill.luminance() > 0.3f) Color(0xFF111111) else Color.White
    val text = (size.value * 0.32f)
    Box(
        Modifier.size(size).background(fill, CircleShape).border((size * 0.07f).coerceAtLeast(1.5.dp), ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (text >= MIN_TEXT_SP) {
            Text(abbr, style = TextStyle(fontSize = text.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp), color = ink, maxLines = 1)
        }
    }
}

private const val MIN_TEXT_SP = 12f

/** A network on a badge (18 dp): "NBC", "NFL NET", "PRIME". */
@Composable
fun NetworkBadge(network: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(18.dp)
            .background(NflColors.BadgeFill, RoundedCornerShape(4.dp))
            .border(1.dp, NflColors.BadgeEdge, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = NflText.badge(network),
            style = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
            color = NflColors.BadgeText,
            maxLines = 1,
        )
    }
}

/** The possession marker: a small amber triangle pointing at the team with the ball ([towardsStart] points left). */
@Composable
fun Possession(towardsStart: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 7.dp, height = 10.dp)) {
        val path = Path().apply {
            if (towardsStart) {
                moveTo(size.width, 0f)
                lineTo(0f, size.height / 2)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2)
                lineTo(0f, size.height)
            }
            close()
        }
        drawPath(path, NflColors.Possession)
    }
}

/** LIVE's dot. */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(NflColors.Live, CircleShape))
}

/** "● LIVE" in the live red. */
@Composable
fun LiveMark(style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        LiveDot()
        Text("LIVE", style = style.copy(fontWeight = FontWeight.Bold), color = NflColors.Live, maxLines = 1)
    }
}

/**
 * [text] in [style], or in [smaller] (then [smallest]) when it doesn't fit on one line; an
 * ellipsis only if none does. Long team names ("Commanders") in narrow places at the larger
 * layout scales.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    smaller: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    smallest: TextStyle? = null,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val width = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val chosen = remember(text, style, smaller, smallest, width, bounded) {
            val candidates = listOfNotNull(style, smaller, smallest)
            if (!bounded) style else candidates.firstOrNull { measurer.measure(text, it, maxLines = 1, softWrap = false).size.width <= width } ?: candidates.last()
        }
        Text(text, style = chosen, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A team's [name] and then its [record], the record left out where both don't fit: the name
 * matters more. The name takes what's left of the width ([FitText] inside it shrinks first).
 */
@Composable
fun NameThenRecord(name: @Composable () -> Unit, record: @Composable () -> Unit, modifier: Modifier = Modifier, gap: Dp = 5.dp) {
    Layout(contents = listOf(name, record), modifier = modifier) { (names, records), constraints ->
        val space = gap.roundToPx()
        val shown = names.firstOrNull()?.measure(constraints.copy(minWidth = 0))
        val left = constraints.maxWidth - (shown?.width ?: 0) - space
        val extra = records.firstOrNull()?.measure(Constraints())?.takeIf { it.width <= left }
        val width = (shown?.width ?: 0) + (extra?.let { space + it.width } ?: 0)
        val height = maxOf(shown?.height ?: 0, extra?.height ?: 0)
        // At the start of whatever width it's given (a weight fills it), never centred in it.
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height.coerceAtLeast(constraints.minHeight)) {
            shown?.place(0, (height - shown.height) / 2)
            extra?.place((shown?.width ?: 0) + space, (height - extra.height) / 2)
        }
    }
}

/** [content] whole, or nothing at all where it doesn't fit: a team's record beside its name at 115%. Never cut, never an ellipsis. */
@Composable
fun WholeOrNothing(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        if (placeable == null || placeable.width > constraints.maxWidth) {
            layout(0, 0) {}
        } else {
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }
}

/** A game "Eagles at Commanders". */
fun matchup(game: NflGame, view: NflView): String = "${view.team(game.away)?.name ?: game.away} at ${view.team(game.home)?.name ?: game.home}"

/** A game's ways to watch on this TV (F6, F7). */
fun waysFor(game: NflGame, view: NflView, watch: WatchOptions): List<WatchWay> = WatchLinks.ways(game, view.settings, watch, view.zone)

/** OK watches a game that's on, or starts within 30 minutes (F3); else it opens its details. */
fun watchesOnOk(game: NflGame, nowMillis: Long): Boolean =
    game.state == GameState.LIVE || (game.state == GameState.PRE && game.kickoffKnown && game.kickoffMillis - nowMillis <= WATCH_LEAD_MILLIS)

/** How close to kickoff OK watches rather than opening the details. */
const val WATCH_LEAD_MILLIS = 30 * 60_000L

/** Watches [game] in its first way that's on this TV, or says none is. */
fun watchFirst(game: NflGame, view: NflView, watch: WatchOptions, actions: HomeActions) {
    val ways = waysFor(game, view, watch)
    val way = ways.firstOrNull { it.installed }
    when {
        way != null -> actions.watch(way, matchup(game, view))
        ways.isNotEmpty() -> actions.say(ways.first().missing)
        else -> actions.say("No one has said where ${matchup(game, view)} is on yet")
    }
}

/** A way to watch as a sheet row: "Watch: NBC on YouTube TV", or dimmed with why. */
private fun watchItem(way: WatchWay, game: NflGame, view: NflView, actions: HomeActions) = SheetItem(
    icon = AppIcons.Play,
    label = if (way.installed) "Watch: ${way.label}" else "Watch: ${way.label} · not on this TV",
    dimmed = !way.installed,
) { if (way.installed) actions.watch(way, matchup(game, view)) else actions.say(way.missing) }

/** The ways to watch [game] while it's on or still to come: two at most, or one while its score is hidden (room for Show score). */
private fun watchItems(game: NflGame, view: NflView, watch: WatchOptions, actions: HomeActions, hidden: Boolean): List<SheetItem> =
    if (game.state == GameState.FINAL) emptyList() else waysFor(game, view, watch).take(if (hidden) 1 else 2).map { watchItem(it, game, view, actions) }

/** Show score (C), while [game]'s is hidden. */
private fun showScoreItem(game: NflGame, actions: HomeActions) = SheetItem(AppIcons.Show, "Show score") { actions.showScore(game.id) }

/** The sheet's title line: "Eagles at Commanders · Live". */
private fun sheetTitle(game: NflGame, view: NflView): String = matchup(game, view)

/**
 * An NFL game's quick actions (the row, Games, a team page; spec §4): the ways to watch it,
 * Details, the away team, the home team, and Show score while hidden. Five at most.
 */
fun gameSheet(
    game: NflGame,
    view: NflView,
    nowMillis: Long,
    watch: WatchOptions,
    actions: HomeActions,
    returnTo: FocusRequester?,
    details: () -> Unit,
    team: (String) -> Unit,
): QuickSheet {
    val hidden = view.hidden(game, nowMillis)
    val items = watchItems(game, view, watch, actions, hidden) +
        listOfNotNull(
            SheetItem(AppIcons.Info, "Details") { details() },
            if (hidden) showScoreItem(game, actions) else null,
            SheetItem(AppIcons.Tv, view.team(game.away)?.name ?: game.away) { team(game.away) },
            SheetItem(AppIcons.Tv, view.team(game.home)?.name ?: game.home) { team(game.home) },
        )
    return QuickSheet(title = sheetTitle(game, view), items = items.take(MAX_ROWS), returnTo = returnTo)
}

/**
 * The game-day card's quick actions (spec §4): the ways to watch (two at most, a missing app
 * dimmed), All games, Standings and Not today (Quick Watch until tomorrow); Show score while
 * hidden. Five at most.
 */
fun cardSheet(
    game: NflGame,
    view: NflView,
    nowMillis: Long,
    watch: WatchOptions,
    actions: HomeActions,
    returnTo: FocusRequester?,
    afterNotToday: FocusRequester?,
): QuickSheet {
    val hidden = view.hidden(game, nowMillis)
    val items = watchItems(game, view, watch, actions, hidden) +
        listOfNotNull(
            if (hidden) showScoreItem(game, actions) else null,
            SheetItem(AppIcons.Football, "All games") { actions.openNfl(NflOpen(NflPage.Tab(NflTab.GAMES, gameId = game.id), NflOpen.CARD)) },
            SheetItem(AppIcons.Guide, "Standings") { actions.openNfl(NflOpen(NflPage.Tab(NflTab.STANDINGS), NflOpen.CARD)) },
            SheetItem(AppIcons.Hide, "Not today", returnTo = afterNotToday) { actions.notToday() },
        )
    return QuickSheet(title = sheetTitle(game, view), items = items.take(MAX_ROWS), returnTo = returnTo)
}

private const val MAX_ROWS = 5

/** A score as the TV shows it: "13", or nothing before kickoff. */
fun scoreOf(game: NflGame, home: Boolean): String = if (game.state == GameState.PRE) "" else ((if (home) game.homeScore else game.awayScore) ?: 0).toString()
