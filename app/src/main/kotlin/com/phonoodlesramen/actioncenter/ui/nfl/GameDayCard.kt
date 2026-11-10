package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.cardSurface

/**
 * The game-day card (Phase 13, F1, F2; ui-spec "The NFL"), in Quick Watch's spot from 30 minutes
 * before a kickoff until the day's last game ends ([com.phonoodlesramen.actioncenter.nfl.NflHome.card]):
 *
 * - the eyebrow: "NFL", "· YOUR TEAM" for your team's game, then "· ● LIVE · 1:45 3RD" or the
 *   kickoff, and the network's badge;
 * - the score line: each team's logo and abbreviation, the possession marker by the team with the
 *   ball, and the scores (the records before kickoff, "Score hidden" while hidden, F10);
 * - the foot: down and distance, the red zone and how many more are live; or the time to kickoff;
 *   "Scores from 2:41 PM" first when the copy is old (F11).
 *
 * The away team's colour washes in from the left and the home team's from the right. OK opens the
 * NFL screen on this game (F3); hold OK or Menu for [cardSheet].
 *
 * The score line takes what the card has left, so at 115% it still fits the card's 92 dp.
 */
@Composable
fun GameDayCard(
    game: NflGame,
    view: NflView,
    nowMillis: Long,
    watch: WatchOptions,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    self: FocusRequester = remember { FocusRequester() },
    /** Where focus goes once Not today takes the card away: Quick Watch is back in its place. */
    afterNotToday: FocusRequester? = null,
) {
    val away = view.team(game.away)
    val home = view.team(game.home)
    val hidden = view.hidden(game, nowMillis)
    val yours = game.away in view.settings.yourTeams || game.home in view.settings.yourTeams
    val sheet = { openSheet(cardSheet(game, view, nowMillis, watch, actions, self, afterNotToday)) }
    val shape = RoundedCornerShape(Layout.CardRadius)
    val wash = Brush.horizontalGradient(
        0f to NflColors.wash(away).copy(alpha = WASH),
        0.38f to Color.Transparent,
        0.62f to Color.Transparent,
        1f to NflColors.wash(home).copy(alpha = WASH),
    )
    Surface(
        onClick = { actions.openNfl(NflOpen(NflPage.Tab(NflTab.GAMES, gameId = game.id), NflOpen.CARD)) },
        onLongClick = sheet,
        modifier = modifier
            .focusRequester(self)
            .onMenuKey(sheet)
            .semantics { contentDescription = "Game day: ${matchup(game, view)}" },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Palette.Text,
        ),
        scale = cardLift,
        border = focusRing(shape),
    ) {
        Box(Modifier.fillMaxSize().cardSurface(LocalCardSurface.current, shape).background(wash, shape)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                CardEyebrow(game, view, yours)
                ScoreLine(game, view, hidden, Modifier.weight(1f).fillMaxWidth())
                Text(
                    text = foot(game, view, nowMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** How strongly each team's colour washes in. */
private const val WASH = 0.33f

/**
 * "NFL · YOUR TEAM · ● LIVE · 1:45 3RD", and the network's badge on the right. Short of room (115%),
 * "· YOUR TEAM" goes first, then the clock; the badge always shows. Nothing is cut.
 */
@Composable
private fun CardEyebrow(game: NflGame, view: NflView, yours: Boolean) {
    val style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Each part brings its own space before it, so a part that gives way leaves no gap.
        val gap = Modifier.padding(start = 5.dp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("NFL", style = style, color = Palette.Text, maxLines = 1)
            // Weighted, so it's measured last and gives way first.
            if (yours) WholeOrNothing(Modifier.weight(1f, fill = false)) { Text("· YOUR TEAM", style = style, color = Palette.Text, maxLines = 1, modifier = gap) }
            Text("·", style = style, color = Palette.TextDim, modifier = gap)
            when (game.state) {
                GameState.LIVE -> {
                    LiveMark(style, gap)
                    WholeOrNothing { Text("· ${NflText.clock(game).uppercase()}", style = style, color = Palette.TextDim, maxLines = 1, modifier = gap) }
                }
                GameState.PRE -> Text(NflText.kickoff(game, view.zone).substringAfter(' ').uppercase(), style = style, color = Palette.TextDim, maxLines = 1, modifier = gap)
                GameState.FINAL -> Text(NflText.final(game).uppercase(), style = style, color = Palette.TextDim, maxLines = 1, modifier = gap)
            }
        }
        NetworkBadge(NflText.network(game))
    }
}

/** The score line: logo, abbreviation, possession, score – score, possession, abbreviation, logo. */
@Composable
private fun ScoreLine(game: NflGame, view: NflView, hidden: Boolean, modifier: Modifier = Modifier) {
    val abbr = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    val score = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamLogo(game.away, view.team(game.away), LOGO)
        Text(game.away, style = abbr, maxLines = 1, modifier = Modifier.weight(1f))
        PossessionSlot(game.possession == game.away && !hidden, towardsStart = false)
        when {
            hidden -> Text(NflText.HIDDEN, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
            game.state == GameState.PRE -> {
                Text(game.awayRecord.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Palette.TextDim, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = SCORE))
                Text("–", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
                Text(game.homeRecord.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Palette.TextDim, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = SCORE))
            }
            else -> {
                Text(scoreOf(game, home = false), style = score, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = SCORE))
                Text("–", style = MaterialTheme.typography.titleMedium, color = Palette.TextDim)
                Text(scoreOf(game, home = true), style = score, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = SCORE))
            }
        }
        PossessionSlot(game.possession == game.home && !hidden, towardsStart = true)
        Text(game.home, style = abbr, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        TeamLogo(game.home, view.team(game.home), LOGO)
    }
}

/** The possession marker's place, kept whether or not the team has the ball, so the scores don't move. */
@Composable
private fun PossessionSlot(has: Boolean, towardsStart: Boolean) {
    Box(Modifier.width(7.dp)) { if (has) Possession(towardsStart) }
}

private val LOGO = 28.dp
private val SCORE = 36.dp

/** The card's foot: the situation and how many more are live, or the time to kickoff; how old the scores are first, when they are. */
private fun foot(game: NflGame, view: NflView, nowMillis: Long) = buildAnnotatedString {
    val parts = mutableListOf<() -> Unit>()
    NflText.staleScores(view, nowMillis)?.let { stale -> parts += { append(stale) } }
    when (game.state) {
        GameState.LIVE -> {
            game.downDistance?.takeIf { it.isNotBlank() }?.let { dd -> parts += { append(dd) } }
            if (game.redZone) parts += { withStyle(SpanStyle(color = NflColors.Live)) { append("Red zone") } }
        }
        GameState.PRE -> parts += { append(NflText.kickoffIn(game, nowMillis, view.zone)) }
        GameState.FINAL -> parts += { append(NflText.final(game)) }
    }
    val others = view.state?.week?.games.orEmpty().count { it.state == GameState.LIVE && it.id != game.id }
    if (others > 0) parts += { append("$others more live") }
    parts.forEachIndexed { index, part ->
        if (index > 0) append(" · ")
        part()
    }
}
