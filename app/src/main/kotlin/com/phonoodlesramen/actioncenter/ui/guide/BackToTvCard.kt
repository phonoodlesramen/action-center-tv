package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.lastapp.AppVisit
import com.phonoodlesramen.actioncenter.lastapp.BackToTv
import com.phonoodlesramen.actioncenter.ui.apps.AppArtContent
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.SheetItem
import com.phonoodlesramen.actioncenter.ui.home.rows.ChannelArt
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/** Back to YouTube TV's words, apart from Compose so they're unit-tested. */
internal object BackToTvText {
    const val EYEBROW = "Back to YouTube TV"

    /**
     * "Started on ABC" when the visit began on a channel opened here, else "Live TV". It never
     * says YouTube TV is still on that channel: a change made inside YouTube TV isn't seen.
     */
    fun title(backTo: BackToTv): String = backTo.channel?.let { "Started on ${it.name}" } ?: "Live TV"

    /** What holding OK offers: the eyebrow already says what OK does. */
    fun hint(backTo: BackToTv): String = if (backTo.recent.isEmpty()) "Hold OK for more" else "Hold OK for channels"

    /** "Left 9 min ago", or "Left just now", on the home's minute tick. */
    fun line(visit: AppVisit, nowMillis: Long): String = "Left ${BackToText.ago(nowMillis - visit.leftAt)}"
}

/**
 * Back to YouTube TV's quick actions, the home's side sheet: Resume (YouTube TV back
 * to the front), Watch {channel} for its recent channels, the visit's own first, and Forget
 * this (the card goes back to what it showed, with focus still on it).
 */
internal fun backToTvSheet(backTo: BackToTv, actions: HomeActions, returnTo: FocusRequester?) = QuickSheet(
    title = "YouTube TV",
    returnTo = returnTo,
    items = buildList {
        add(SheetItem(AppIcons.Play, "Resume") { actions.launch(backTo.app) })
        backTo.recent.forEach { channel -> add(SheetItem(AppIcons.Tv, "Watch ${channel.name}") { actions.watchTvChannel(channel) }) }
        add(SheetItem(AppIcons.Close, "Forget this") { actions.forgetVisit(backTo.visit) })
    },
)

/**
 * The Plex Guide card while YouTube TV was the last thing you watched:
 * "BACK TO YOUTUBE TV" in the accent, the channel it started on with its Live-row banner
 * ([button]) or YouTube TV's own banner, the hint, and "Left 9 min ago" on the minute tick.
 */
@Composable
internal fun ColumnScope.BackToTvFace(backTo: BackToTv, button: ChannelButton?, nowMillis: Long) {
    Eyebrow(BackToTvText.EYEBROW, color = LocalAccent.current)
    Row(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(Layout.TileRadius)).background(Palette.Surface2)) {
            if (button != null) {
                ChannelArt(button, Modifier.fillMaxSize())
            } else {
                val banners by LocalCustomBanners.current.state.collectAsState()
                AppArtContent(backTo.app, banners.bannerFor(backTo.app))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = BackToTvText.title(backTo),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(BackToTvText.hint(backTo), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Text(
        text = BackToTvText.line(backTo.visit, nowMillis),
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp),
    )
}
