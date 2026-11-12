package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.nfl.GameState
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflRules
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.ui.nfl.NflOpen
import com.phonoodlesramen.actioncenter.ui.nfl.NflPage
import com.phonoodlesramen.actioncenter.ui.nfl.NflTab
import com.phonoodlesramen.actioncenter.ui.nfl.matchup
import com.phonoodlesramen.actioncenter.ui.profiles.argb
import com.phonoodlesramen.actioncenter.ui.profiles.initialOf
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.cardSurface

private val CardShape = RoundedCornerShape(Layout.CardRadius)
private val RowShape = RoundedCornerShape(Layout.TileRadius)

/**
 * Weekend mornings' right-hand card (Moments, H3, K11): "Who's watching?" with each kid's
 * avatar, one press from their own home. The top bar leaves its small avatars out meanwhile.
 */
@Composable
internal fun WhosWatchingCard(kids: List<ActiveProfile>, actions: HomeActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .cardSurface(LocalCardSurface.current, CardShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Eyebrow("Who's watching?")
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            kids.forEach { kid -> KidTile(kid, onClick = { actions.switchProfile(kid.id) }) }
        }
        Text(eachGetsTheirOwn(kids.map { it.name }), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Maya gets their own home." or "Maya and Leo each get their own home." */
internal fun eachGetsTheirOwn(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "${names[0]} gets their own home."
    else -> "${names.dropLast(1).joinToString(", ")} and ${names.last()} each get their own home."
}

/** A kid on the card: their initial on their colour, their name below. OK switches to them. */
@Composable
private fun KidTile(kid: ActiveProfile, onClick: () -> Unit) {
    val color = kid.color?.let(::argb) ?: Palette.Surface3
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(64.dp).semantics { contentDescription = "Switch to ${kid.name}" },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = color,
                contentColor = Palette.AccentInk,
                focusedContainerColor = color,
                focusedContentColor = Palette.AccentInk,
            ),
            scale = cardLift,
            border = focusRing(CircleShape),
        ) {
            initialOf(kid.name)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Palette.AccentInk,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Text(kid.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
    }
}

/**
 * Game day's right-hand card (Moments, H3): today's other games, live first, then those to
 * come, then the latest finals; three at most. OK opens a game in the NFL screen (F3).
 */
@Composable
internal fun AroundTheLeague(games: List<NflGame>, view: NflView, nowMillis: Long, actions: HomeActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .cardSurface(LocalCardSurface.current, CardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Eyebrow("Around the league")
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).focusRestorer().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            games.forEach { game -> ScoreRow(game, view, nowMillis, onClick = { actions.openNfl(NflOpen(NflPage.Tab(NflTab.GAMES, gameId = game.id), NflOpen.CARD)) }) }
        }
    }
}

/** Today's other games for [AroundTheLeague]: not [featured], live first, then to come, then the latest finals. */
internal fun aroundTheLeague(view: NflView, featured: NflGame?, nowMillis: Long): List<NflGame> {
    val week = view.state?.week ?: return emptyList()
    val today = view.today(nowMillis)
    fun order(game: NflGame) = when (game.state) {
        GameState.LIVE -> 0
        GameState.PRE -> 1
        GameState.FINAL -> 2
    }
    return week.games
        .filter { it.id != featured?.id && NflRules.localDate(it.kickoffMillis, view.zone) == today }
        .sortedWith(compareBy<NflGame>(::order).thenBy { if (it.state == GameState.FINAL) -it.kickoffMillis else it.kickoffMillis })
        .take(AROUND_THE_LEAGUE)
}

private const val AROUND_THE_LEAGUE = 3

/** "PHI 21  NYG 10  Q3 · 9:40": each team and its score (or Score hidden, F10), and the status. */
@Composable
private fun ScoreRow(game: NflGame, view: NflView, nowMillis: Long, onClick: () -> Unit) {
    val hidden = view.hidden(game, nowMillis)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = matchup(game, view) },
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val score = { home: Boolean -> if (game.state == GameState.PRE || hidden) "" else ((if (home) game.homeScore else game.awayScore) ?: 0).toString() }
            Team(game.away, score(false), Modifier.weight(1f))
            Team(game.home, score(true), Modifier.weight(1f))
            Text(
                text = if (hidden && game.state != GameState.PRE) "Score hidden" else NflText.status(game, view.zone),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Team(abbr: String, score: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(abbr, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp), maxLines = 1)
        if (score.isNotEmpty()) Text(score, style = MaterialTheme.typography.titleSmall, color = Palette.TextDim, maxLines = 1)
    }
}

/** Room for the late-night card's eyebrow (M3: Quick Watch shows what fits in the next 45 minutes). */
internal const val LATE_NIGHT_QUICK = "Fits in the next 45 minutes"

/** Under a weeknight's taller Quick Watch: "1h 49m until 10:30", or nothing once it's past. */
internal fun untilCutoff(minutes: Long, cutoff: String): String? = when {
    minutes <= 0 -> null
    minutes < 60 -> "$minutes min until $cutoff"
    else -> "${minutes / 60}h ${minutes % 60}m until $cutoff"
}

