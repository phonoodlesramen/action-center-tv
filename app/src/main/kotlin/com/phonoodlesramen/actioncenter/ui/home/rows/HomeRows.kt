package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.home.HomeRowsState
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.lastapp.TunedChannel
import com.phonoodlesramen.actioncenter.nfl.NflRowModel
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.nfl.WatchOptions
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet

/**
 * The rows below the dashboard, in L10's order: Live (was Channels, G7), Continue in your
 * apps, Apps. Each row is its own file and owns its spacing; an empty row draws nothing.
 *
 * Phase 13: the NFL row ([NflRow]) goes first on game days, from an hour before the first
 * kickoff ([NflRowModel.first]), and after Live otherwise.
 */
@Composable
fun HomeRows(
    state: HomeUiState,
    rows: HomeRowsState,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    modifier: Modifier = Modifier,
    /** The Plex Guide's lineup: its channels join YouTube TV's in the Live row (G7). */
    guide: GuideCardState = GuideCardState.NotSetUp,
    /** The profile's favorite channels (Phase 13): first in the Live row. */
    favorites: List<String> = emptyList(),
    /** The NFL row (Phase 13); null leaves it out. */
    nflRow: NflRowModel? = null,
    nfl: NflView = NflView.None,
    watch: WatchOptions = WatchOptions(),
    /** The YouTube TV channels opened here, newest first: after the favorites in the Live row. */
    recentTv: List<TunedChannel> = emptyList(),
) {
    Column(modifier.fillMaxWidth()) {
        val nflFirst = nflRow?.takeIf { it.first }
        val nflAfter = nflRow?.takeIf { !it.first }
        nflFirst?.let { NflRow(it, nfl, state.nowMillis, watch, actions, openSheet) }
        LiveRow(rows.channels, guide, LiveClock(state.now, state.nowMillis), lateNight, actions, openSheet, favorites = favorites, recentTv = recentTv)
        nflAfter?.let { NflRow(it, nfl, state.nowMillis, watch, actions, openSheet) }
        ContinueInAppsRow(rows.continueInApps, lateNight, actions, openSheet)
        AppsRow(state, actions, openSheet)
    }
}
