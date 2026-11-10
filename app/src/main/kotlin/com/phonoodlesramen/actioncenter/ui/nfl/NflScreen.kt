package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.nfl.NflController
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflSource
import com.phonoodlesramen.actioncenter.nfl.NflText
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.nfl.SeasonPhase
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import com.phonoodlesramen.actioncenter.ui.guide.GuideCaredScreen
import com.phonoodlesramen.actioncenter.ui.guide.GuideScreenCare
import com.phonoodlesramen.actioncenter.ui.guide.rememberFallbackFocus
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.QuickActionsSheet
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.home.rows.MarginBringIntoViewSpec
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The NFL screen (Phase 13, F4; ui-spec "The NFL"), inside the home like the full guide: Games,
 * Standings and Playoffs, with game details and team pages. It draws only the copy ([NflView]), so
 * it opens at once; other weeks, schedules and game details load from the station (or ESPN) with
 * a quiet "Loading…" in their place, and a sentence when they fail.
 *
 * - **Pages** ([NflPage]): the screen keeps its own stack. OK on a game or a team pushes its page;
 *   Back closes a sheet, then pops to the page it came from, with focus back where it was, and
 *   from the page it opened on calls [onBack]: the home's card that opened it.
 * - **The header** (44 dp): the football, "NFL", the week and its dates, the tabs (the open one
 *   tinted, with an accent underline), and on Games ‹ Week and Week › through the season's
 *   calendar. Up from a tab's top row reaches it.
 * - **Screen care** (G10), as the full guide ([GuideCaredScreen]).
 */
@Composable
fun NflScreen(
    view: NflView,
    open: NflOpen,
    nowMillis: Long,
    controller: NflController,
    actions: HomeActions,
    watch: WatchOptions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    idleMinutes: Int? = null,
    lastInputMillis: () -> Long = { 0L },
    care: GuideScreenCare = remember { GuideScreenCare() },
) {
    var stack by remember(open) { mutableStateOf(listOf(NflEntry(open.page))) }
    var sheet by remember { mutableStateOf<QuickSheet?>(null) }
    // Bumped as a sheet closes, so the focused element takes focus back.
    var refocus by remember { mutableIntStateOf(0) }
    val focus = remember { NflFocus() }
    val entry = stack.last()

    BackHandler {
        if (stack.size > 1) stack = stack.dropLast(1) else onBack()
    }

    val nav = NflNav(
        push = { page, from ->
            stack = stack.dropLast(1) + stack.last().copy(focus = from) + NflEntry(page)
        },
        replace = { page, focusKey ->
            stack = stack.dropLast(1) + NflEntry(page, focus = focusKey)
        },
        tab = { tab -> stack = listOf(NflEntry(tab, focus = tabKey(tab.tab))) },
    )
    val ctx = NflContext(view, nowMillis, watch, actions, controller, openSheet = { sheet = it }, nav = nav, focus = focus)

    GuideCaredScreen(
        care = care,
        idleMinutes = idleMinutes,
        lastInputMillis = lastInputMillis,
        nowMillis = nowMillis,
        zone = view.zone,
        modifier = modifier,
        overlay = {
            sheet?.let { shown ->
                QuickActionsSheet(shown) {
                    sheet = null
                    refocus++
                }
            }
        },
    ) { area ->
        val state = view.state
        if (state == null || view.outdated) {
            NothingYet(view, area)
            return@GuideCaredScreen
        }
        @OptIn(ExperimentalFoundationApi::class)
        CompositionLocalProvider(LocalBringIntoViewSpec provides remember(focus) { MarginBringIntoViewSpec(EDGE_PX) }) {
            when (val page = entry.page) {
                is NflPage.Tab -> Column(area) {
                    NflHeader(ctx, page)
                    Spacer(Modifier.height(8.dp))
                    when (page.tab) {
                        NflTab.GAMES -> GamesTab(ctx, page, entry.focus, Modifier.weight(1f))
                        NflTab.STANDINGS -> StandingsTab(ctx, page, entry.focus, Modifier.weight(1f))
                        NflTab.PLAYOFFS -> PlayoffsTab(ctx, entry.focus, Modifier.weight(1f))
                    }
                }
                is NflPage.Game -> GameDetails(ctx, page, entry.focus, area)
                is NflPage.Team -> TeamPage(ctx, page.abbr, entry.focus, area)
            }
        }
        // A sheet closing hands focus back to what opened it: QuickSheet's returnTo, else the page's own.
        LaunchedEffect(refocus) { if (refocus > 0 && sheet == null) focus.restore() }
    }
}

/** How far from a scroller's edges the focused thing is kept, in px. */
private const val EDGE_PX = 24f

/** A page in the screen's stack, and the key of what had focus when another page was pushed over it. */
internal data class NflEntry(val page: NflPage, val focus: String? = null)

/** Moving between pages: [push] a page over this one (remembering [from]'s key), [replace] this one, or open a [tab]. */
internal class NflNav(
    val push: (page: NflPage, from: String?) -> Unit,
    val replace: (page: NflPage, focus: String?) -> Unit,
    val tab: (NflPage.Tab) -> Unit,
)

/** What every page of the NFL screen draws from and acts through. */
@Stable
internal class NflContext(
    val view: NflView,
    val nowMillis: Long,
    val watch: WatchOptions,
    val actions: HomeActions,
    val controller: NflController,
    val openSheet: (QuickSheet) -> Unit,
    val nav: NflNav,
    val focus: NflFocus,
) {
    val state get() = view.state!!

    /** OK on a game (F3 B): watch it while it's on or starts within 30 minutes, else its details. */
    fun ok(game: NflGame, from: String) {
        if (watchesOnOk(game, nowMillis)) watchFirst(game, view, watch, actions) else nav.push(NflPage.Game(game.id, game), from)
    }

    /** Hold OK or Menu on a game: its ways to watch, Details, both teams, Show score ([gameSheet]). */
    fun sheet(game: NflGame, from: String) = openSheet(
        gameSheet(
            game, view, nowMillis, watch, actions, focus.requester(from),
            details = { nav.push(NflPage.Game(game.id, game), from) },
            team = { nav.push(NflPage.Team(it), from) },
        ),
    )

    /** A team's page, from [from]. */
    fun team(abbr: String, from: String) = nav.push(NflPage.Team(abbr), from)
}

/** The screen's focus targets by key ("game:401873035", "team:PHI", "tab:GAMES"), so Back and a closing sheet land where they were. */
@Stable
internal class NflFocus {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private var last: String? = null

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** [key] took focus. */
    fun focused(key: String) {
        last = key
    }

    /** Focuses [key] (or the first of [fallbacks] that can take it), once it's laid out. */
    suspend fun focus(key: String?, vararg fallbacks: String) {
        withFrameNanos {}
        for (candidate in listOfNotNull(key) + fallbacks) {
            if (runCatching { requester(candidate).requestFocus() }.getOrDefault(false)) return
        }
    }

    /** Back to the last focused element (a sheet closed). */
    suspend fun restore() = focus(last)
}

fun tabKey(tab: NflTab) = "tab:${tab.name}"

/**
 * The header (44 dp): the football, "NFL", the week and its dates (or last season's, in the
 * preseason's Standings and Playoffs, D), the tabs, ‹ Week and Week › on Games, and the clock.
 */
@Composable
private fun NflHeader(ctx: NflContext, page: NflPage.Tab) {
    val state = ctx.state
    val week = shownWeekRef(state, page)
    val calendar = state.calendar
    val index = calendar.indexOfFirst { it.seasonType == week.first && it.week == week.second }
    val subtitle = when {
        page.tab != NflTab.GAMES && state.standingsSeason != state.seasonYear -> "The ${state.standingsSeason} season's final"
        else -> listOf(calendar.getOrNull(index)?.label ?: state.week?.label.orEmpty(), weekDates(ctx, page)).filter { it.isNotBlank() }.joinToString(" · ")
    }
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Football, contentDescription = null, tint = NflColors.Gold, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("NFL", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(12.dp))
            // The week and its dates take what the tabs leave, whole or not at all (115%).
            WholeOrNothing(Modifier.weight(1f, fill = false)) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 1)
            }
            Spacer(Modifier.width(18.dp))
            // On Standings, Down from a tab goes to the open view's pill: geometric search would pick a division's row.
            val down = if (page.tab == NflTab.STANDINGS) ctx.focus.requester(viewKey(page.standings)) else null
            // Up into the tabs lands on the open one the first time, then on the one last left.
            Row(Modifier.focusRestorer(ctx.focus.requester(tabKey(page.tab))).focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NflTab.entries.forEach { tab -> TabPill(ctx, tab, open = tab == page.tab, down = down) }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (page.tab == NflTab.GAMES) {
            Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                calendar.getOrNull(index - 1)?.takeIf { index > 0 }?.let { previous ->
                    WeekPill(ctx, "‹ ${previous.label}", "week:previous") {
                        ctx.nav.replace(page.copy(seasonType = previous.seasonType, week = previous.week, gameId = null), "week:previous")
                    }
                }
                calendar.getOrNull(index + 1)?.takeIf { index >= 0 }?.let { next ->
                    WeekPill(ctx, "${next.label} ›", "week:next") {
                        ctx.nav.replace(page.copy(seasonType = next.seasonType, week = next.week, gameId = null), "week:next")
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(clock(ctx), style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 1)
    }
}

/** A tab: a pill; the open one tinted with an accent underline. */
@Composable
private fun TabPill(ctx: NflContext, tab: NflTab, open: Boolean, down: FocusRequester?) {
    val accent = LocalAccent.current
    val key = tabKey(tab)
    PillButton(
        text = tab.label,
        onClick = { if (!open) ctx.nav.tab(NflPage.Tab(tab)) },
        colors = pillButtonColors(container = if (open) accent.copy(alpha = 0.14f) else Palette.Text.copy(alpha = 0.08f)),
        modifier = Modifier
            .focusRequester(ctx.focus.requester(key))
            .then(if (down != null) Modifier.focusProperties { this.down = down } else Modifier)
            .onFocused { ctx.focus.focused(key) }
            .then(
                if (open) {
                    Modifier.drawBehind {
                        val line = 2.dp.toPx()
                        drawLine(accent, Offset(size.height / 2, size.height - line / 2), Offset(size.width - size.height / 2, size.height - line / 2), line)
                    }
                } else {
                    Modifier
                },
            ),
    )
}

@Composable
private fun WeekPill(ctx: NflContext, text: String, key: String, onClick: () -> Unit) {
    PillButton(
        text = text,
        small = true,
        onClick = onClick,
        colors = pillButtonColors(container = Palette.Text.copy(alpha = 0.08f)),
        modifier = Modifier.focusRequester(ctx.focus.requester(key)).onFocused { ctx.focus.focused(key) },
    )
}

/** The clock on the right of the header, the home's minute tick. */
private fun clock(ctx: NflContext): String = Instant.ofEpochMilli(ctx.nowMillis).atZone(ctx.view.zone).format(CLOCK)

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

/** The week a Games tab shows: [NflPage.Tab]'s, else the copy's own. */
internal fun shownWeekRef(state: com.phonoodlesramen.actioncenter.nfl.NflState, page: NflPage.Tab): Pair<Int, Int> {
    val own = state.week
    return (page.seasonType ?: own?.seasonType ?: 2) to (page.week ?: own?.week ?: 1)
}

/** "Oct 29 – Nov 2" for the week shown: the copy's, or the calendar's dates for another. */
private fun weekDates(ctx: NflContext, page: NflPage.Tab): String {
    val state = ctx.state
    val (type, number) = shownWeekRef(state, page)
    val own = state.week
    if (own != null && own.seasonType == type && own.week == number) return NflText.dates(own, ctx.view.zone)
    val ref = state.calendar.firstOrNull { it.seasonType == type && it.week == number } ?: return ""
    val from = NflText.date(ref.startMillis, ctx.view.zone)
    val to = NflText.date(ref.endMillis - 1, ctx.view.zone)
    return if (from == to) from else "$from – $to"
}

/** No copy yet, or a 1.1 station: what the NFL waits on, calmly; Back still goes home. */
@Composable
private fun NothingYet(view: NflView, modifier: Modifier) {
    val fallback = rememberFallbackFocus(enabled = true)
    Column(
        modifier.focusRequester(fallback).focusable(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Eyebrow("NFL")
        Text(
            text = when {
                view.outdated -> NflSource.OUTDATED
                view.state?.phase == SeasonPhase.OFF -> "The NFL is back with the preseason in August"
                else -> "The NFL appears once the guide station (or ESPN) answers"
            },
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextDim,
            textAlign = TextAlign.Center,
        )
        view.problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Caution, textAlign = TextAlign.Center) }
    }
}

/** "Loading…", quietly, or the sentence it failed with, in place of what's loading. */
@Composable
internal fun Quietly(text: String, modifier: Modifier = Modifier, failed: Boolean = false) {
    Box(modifier.padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (failed) Palette.Caution else Palette.TextDim, textAlign = TextAlign.Center)
    }
}

/** Runs [block] when this takes focus. */
internal fun Modifier.onFocused(block: () -> Unit): Modifier = onFocusChanged { if (it.isFocused) block() }

/** The rounded shape of the screen's rows. */
internal val RowShape = RoundedCornerShape(8.dp)
