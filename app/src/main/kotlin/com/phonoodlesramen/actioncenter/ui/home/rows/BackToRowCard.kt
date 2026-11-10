package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.player.BackTo
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.guide.BackToText
import com.phonoodlesramen.actioncenter.ui.guide.backToSheet
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.home.tileColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalFocusedArt
import com.phonoodlesramen.actioncenter.ui.wallpaper.reportsArt

/**
 * The Back-to card as the first card of a kid's Live row (P4; they have no Guide card): a
 * Live card's anatomy, with the program's art, "Back to Cartoon Club" on the pill, the
 * paused place on the bar, and under it the title and "Paused 12:30 in · 9 min ago". OK
 * resumes; hold OK or Menu for Resume, Back to live and Forget this. Forgetting it takes the
 * card away, so focus goes to [afterForget], the row's next card.
 *
 * @param self the card's focus, where its quick actions return.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BackToRowCard(
    backTo: BackTo,
    nowMillis: Long,
    lateNight: Boolean,
    actions: HomeActions,
    openSheet: (QuickSheet) -> Unit,
    self: FocusRequester,
    afterForget: FocusRequester?,
) {
    val slot = backTo.slot
    val title = BackToText.title(backTo)
    val line = BackToText.line(backTo.spot, nowMillis)
    val sheet = { openSheet(backToSheet(backTo, actions, returnTo = self, afterForget = afterForget)) }
    val shape = RoundedCornerShape(Layout.TileRadius)
    val metrics = LocalRowMetrics.current
    Column(Modifier.width(metrics.cardWidth)) {
        Surface(
            onClick = { actions.resume(backTo.spot) },
            onLongClick = sheet,
            modifier = Modifier
                .rowCard(metrics)
                .bringIntoViewResponder(rememberCaptionRoom())
                .reportsArt(LocalFocusedArt.current, slot.art)
                .focusRequester(self)
                .onMenuKey(sheet)
                .semantics { contentDescription = "$title, ${slot.title}, $line" },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = tileColors(),
            scale = cardLift,
            border = focusRing(shape),
        ) {
            TitleArt(slot.itemKey ?: slot.title, slot.art, lateNight, Modifier.fillMaxSize())
            // Text: hidden while the row peeks at the screen's foot (TV-OV).
            ShownWhole(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                BackToBadge(backTo.channel.name, Modifier.widthIn(max = metrics.cardWidth - 12.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.22f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(backTo.progress).background(LocalAccent.current))
            }
        }
        ShownWhole {
            Column {
                Text(
                    text = slot.title,
                    style = MaterialTheme.typography.titleSmall.onWallpaper(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.onWallpaper(),
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The Live cards' dark pill, on two lines so a channel's name has the card's width: "Back
 * to" in the accent with a play sign, then the channel.
 */
@Composable
private fun BackToBadge(channel: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Play, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(12.dp))
            Text("Back to", style = MaterialTheme.typography.labelMedium, color = LocalAccent.current, maxLines = 1)
        }
        Text(channel, style = MaterialTheme.typography.labelMedium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
