package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.KidGuide
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.lastapp.BackToTv
import com.phonoodlesramen.actioncenter.player.BackTo
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.onMenuKey
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveClock
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalCardSurface
import com.phonoodlesramen.actioncenter.ui.wallpaper.cardSurface

/**
 * The Plex Guide card in Row B's right-hand spot (plan §6, Q3). With a lineup it reads
 * "PLEX GUIDE · 18 CHANNELS", then "Coming up at 9:30" and two programs starting then
 * ([ComingUp]); what's on now is the Live row's job (G7). Before there are channels it
 * says what they wait on ([NoChannelsYet], G20 B), over a faint sketch of a guide.
 *
 * One focusable surface in the panel's focus group: Left goes to Up Next, Up to Quick
 * Watch, Down to the first row below. OK opens the full guide ([HomeActions.openGuide])
 * in both states; without channels the guide says there are none yet. Holding OK has no
 * sheet, but for Back to (below).
 *
 * For a kid (K8) it counts and shows only the channels inside their [ceiling] ([KidGuide]),
 * and says so when there are none.
 *
 * **Favorites** (Phase 13, C5): a favorite starting something within the hour leads Coming
 * up ([ComingUp]), and a favorite's channel has a star before its name.
 *
 * **Back to (P4):** while the guide player's spot holds ([backTo]), the card is "Back to
 * {channel}" instead ([BackToFace]): OK resumes it, paused where it was left, and holding
 * OK or Menu opens its quick actions (Resume, Back to live, Forget this; [backToSheet]).
 * Forgetting it puts the card back to its lineup, with focus still on it.
 *
 * **Back to YouTube TV:** while YouTube TV is the last thing you watched
 * ([backToTv], newer than any saved spot: the home picks one), the card is "Back to YouTube
 * TV" instead ([BackToTvFace]): OK brings YouTube TV back to the front, and holding OK or
 * Menu opens its quick actions ([backToTvSheet]).
 *
 * @param clock the home's minute tick: the card follows it and has no timer of its own.
 * @param plexLinked whether a server is linked: before the library syncs, the channels wait on it or on Plex.
 * @param ceiling a kid's ratings; null, an adult's, shows every channel.
 * @param backTo the saved spot, while it holds ([com.phonoodlesramen.actioncenter.player.SpotRules]).
 * @param self the card's focus, where its quick actions return.
 * @param backToTv Back to YouTube TV; the home passes it or [backTo], never both.
 * @param tvButton the Live row's button for [backToTv]'s channel, for its banner.
 */
@Composable
fun GuideCard(
    state: GuideCardState,
    clock: LiveClock,
    lateNight: Boolean,
    plexLinked: Boolean,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    ceiling: Set<String>? = null,
    backTo: BackTo? = null,
    openSheet: (QuickSheet) -> Unit = {},
    self: FocusRequester? = null,
    /** The profile's favorite channels (Phase 13, C5): they lead Coming up within the hour, with a star. */
    favorites: List<String> = emptyList(),
    backToTv: BackToTv? = null,
    tvButton: ChannelButton? = null,
) {
    val shown = remember(state, ceiling) { KidGuide.of(state, ceiling) }
    val channels = (shown as? GuideCardState.Ready)?.channels.orEmpty()
    val onClick = when {
        backToTv != null -> ({ actions.launch(backToTv.app) })
        backTo != null -> ({ actions.resume(backTo.spot) })
        else -> ({ actions.openGuide() })
    }
    val sheet = when {
        backToTv != null -> ({ openSheet(backToTvSheet(backToTv, actions, returnTo = self)) })
        backTo != null -> ({ openSheet(backToSheet(backTo, actions, returnTo = self)) })
        else -> null
    }
    CardFrame(onClick, sheet, modifier) {
        when {
            backToTv != null -> BackToTvFace(backToTv, tvButton, clock.nowMillis)
            backTo != null -> BackToFace(backTo, clock.nowMillis, lateNight)
            channels.isEmpty() -> Waiting(NoChannelsYet.of(shown, plexLinked) ?: NoChannelsYet.NOT_SYNCED)
            else -> Lineup(channels, clock, lateNight, favorites)
        }
    }
}

private val CardShape = RoundedCornerShape(Layout.CardRadius)

/** One surface whatever the card shows, so focus stays on it when it changes (Forget this). */
@Composable
private fun CardFrame(onClick: () -> Unit, sheet: (() -> Unit)?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val surface = LocalCardSurface.current
    Box(modifier.focusRestorer().focusGroup()) {
        Surface(
            onClick = onClick,
            onLongClick = sheet,
            modifier = Modifier.fillMaxSize().then(if (sheet != null) Modifier.onMenuKey(sheet) else Modifier),
            shape = ClickableSurfaceDefaults.shape(CardShape),
            // The card surface (Solid or Glass, L4) is drawn inside, so it lifts with focus.
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Palette.Text,
                focusedContainerColor = Color.Transparent,
                focusedContentColor = Palette.Text,
            ),
            scale = cardLift,
            border = focusRing(CardShape),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .cardSurface(surface, CardShape)
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
                content = content,
            )
        }
    }
}

/**
 * No channels yet, and what they wait on ([NoChannelsYet]): Plex, the library's sync, the
 * lineup being made, or a library too small to fill a channel.
 */
@Composable
private fun ColumnScope.Waiting(why: NoChannelsYet) {
    Eyebrow("Plex Guide")
    Text(
        text = "Your library as TV channels",
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        text = why.card,
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextDim,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val accent = LocalAccent.current
    Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp)) { guideSketch(accent) }
}

/** The lineup's size, then what's coming up: two programs, each with its art and channel. */
@Composable
private fun ColumnScope.Lineup(channels: List<GuideChannel>, clock: LiveClock, lateNight: Boolean, favorites: List<String>) {
    val comingUp = remember(channels, clock.nowMillis, favorites) { ComingUp.of(channels, clock.nowMillis, favorites) }
    Eyebrow("Plex Guide · ${if (channels.size == 1) "1 channel" else "${channels.size} channels"}")
    Text(
        text = comingUp.heading(clock),
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
    // The lines sit at the card's foot and shrink to fit at 115% [U4].
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
    ) {
        comingUp.programs.forEach { (channel, slot) ->
            ProgramLine(slot, comingUp.meta(channel, slot, clock), comingUp.isFavorite(channel), lateNight, Modifier.weight(1f, fill = false))
        }
    }
}

/** A program coming up: a 16:9 thumbnail at its own size (spec §6), the title, and its channel, starred when it's a favorite. */
@Composable
private fun ProgramLine(slot: GuideSlot, meta: String, favorite: Boolean, lateNight: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(max = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TitleArt(
            id = slot.itemKey ?: slot.title,
            image = slot.art,
            lateNight = lateNight,
            modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)),
        )
        Text(
            text = slot.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(Modifier.widthIn(max = 150.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (favorite) Icon(AppIcons.Star, contentDescription = "Favorite", tint = LocalAccent.current, modifier = Modifier.size(12.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What the Guide card shows coming up: the first time at which two channels start a
 * program (usually the next half hour, as the scheduler aligns starts) and those two, in
 * lineup order. A lineup that never starts two at once gives its soonest two, each with
 * its own time. Filler and programs that are off air never show. Pure, so it's tested
 * without Compose.
 *
 * **Favorites first** (Phase 13, C5): when a favorite starts something within the next hour,
 * the soonest such time leads instead, favorites first at that time (in the profile's order),
 * then the rest by number; a second program from another channel joins it, the soonest
 * (each with its own time if they differ). Without one in the hour, the rule above.
 */
@Immutable
internal data class ComingUp(
    val atMillis: Long?,
    val programs: List<Pair<GuideChannel, GuideSlot>>,
    /** The profile's favorites among [programs]' channels: a star before their names. */
    val favorites: Set<String> = emptySet(),
) {
    fun heading(clock: LiveClock): String = when {
        atMillis != null -> "Coming up at ${clock.timeOf(atMillis)}"
        programs.isNotEmpty() -> "Coming up next"
        else -> "Nothing scheduled yet"
    }

    /** A line's right side: its channel, and its own time when the heading has none. */
    fun meta(channel: GuideChannel, slot: GuideSlot, clock: LiveClock): String =
        if (atMillis != null) channel.name else "${clock.timeOf(slot.startMillis)} · ${channel.name}"

    fun isFavorite(channel: GuideChannel): Boolean = channel.id in favorites

    companion object {
        private const val SHOWN = 2

        /** How far ahead a favorite's start can lead the card. */
        const val FAVORITES_AHEAD_MILLIS = 60 * 60_000L

        fun of(channels: List<GuideChannel>, millis: Long, favorites: List<String> = emptyList()): ComingUp {
            val upcoming = channels.flatMap { channel ->
                channel.slots.filter { it.startMillis > millis && !it.filler && it.offAir == null }.map { channel to it }
            }
            val picked = favoritesFirst(upcoming, millis, favorites) ?: byTime(upcoming)
            val starred = favorites.toSet()
            return picked.copy(favorites = picked.programs.mapNotNullTo(HashSet()) { (channel, _) -> channel.id.takeIf { it in starred } })
        }

        /** C5: the soonest start of a favorite within the hour, favorites first then; null when none starts. */
        private fun favoritesFirst(upcoming: List<Pair<GuideChannel, GuideSlot>>, millis: Long, favorites: List<String>): ComingUp? {
            if (favorites.isEmpty()) return null
            val rank = favorites.withIndex().associate { (i, id) -> id to i }
            val at = upcoming.filter { (channel, slot) -> channel.id in rank && slot.startMillis <= millis + FAVORITES_AHEAD_MILLIS }
                .minOfOrNull { (_, slot) -> slot.startMillis } ?: return null
            // Favorites in the profile's order, then the rest by number; each channel's first program.
            val order = compareBy<Pair<GuideChannel, GuideSlot>>(
                { (_, slot) -> slot.startMillis },
                { (channel, _) -> rank[channel.id] ?: Int.MAX_VALUE },
                { (channel, _) -> channel.number },
            )
            val together = upcoming.filter { (_, slot) -> slot.startMillis == at }.sortedWith(order).distinctBy { (channel, _) -> channel.id }
            if (together.size >= SHOWN) return ComingUp(at, together.take(SHOWN))
            val lead = together.first()
            val other = upcoming.filter { (channel, _) -> channel.id != lead.first.id }.sortedWith(order).firstOrNull()
                ?: return ComingUp(at, listOf(lead))
            return ComingUp(null, listOf(lead, other))
        }

        /** The first time two channels start a program, or else the soonest two. */
        private fun byTime(upcoming: List<Pair<GuideChannel, GuideSlot>>): ComingUp {
            // groupBy keeps the lineup's order inside each start time.
            val together = upcoming.groupBy { (_, slot) -> slot.startMillis }
                .filterValues { starting -> starting.distinctBy { (channel, _) -> channel.id }.size >= SHOWN }
                .minByOrNull { it.key }
            if (together != null) return ComingUp(together.key, together.value.distinctBy { (channel, _) -> channel.id }.take(SHOWN))
            val soonest = upcoming.sortedBy { (_, slot) -> slot.startMillis }.take(SHOWN)
            return ComingUp(soonest.map { (_, slot) -> slot.startMillis }.distinct().singleOrNull(), soonest)
        }
    }
}

/** Three faint rows of programme blocks and a "now" line: a hint of the guide to come. */
private fun DrawScope.guideSketch(accent: Color) {
    val gap = 5.dp.toPx()
    val rowHeight = ((size.height - 2 * gap) / SKETCH_ROWS.size).coerceAtMost(14.dp.toPx())
    if (rowHeight < 6.dp.toPx()) return
    val now = size.width * NOW
    val radius = CornerRadius(3.dp.toPx())
    SKETCH_ROWS.forEachIndexed { row, blocks ->
        val top = row * (rowHeight + gap)
        var start = 0f
        blocks.forEachIndexed { index, fraction ->
            val width = size.width * fraction - if (index == blocks.lastIndex) 0f else gap
            val onNow = now in start..(start + width)
            drawRoundRect(
                color = if (onNow) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
                topLeft = Offset(start, top),
                size = Size(width, rowHeight),
                cornerRadius = radius,
            )
            start += width + gap
        }
    }
    val bottom = SKETCH_ROWS.size * rowHeight + (SKETCH_ROWS.size - 1) * gap
    drawLine(accent.copy(alpha = 0.8f), Offset(now, -2.dp.toPx()), Offset(now, bottom + 2.dp.toPx()), strokeWidth = 1.5.dp.toPx())
}

/** Each row's programmes as fractions of the width. */
private val SKETCH_ROWS = listOf(listOf(0.42f, 0.33f, 0.25f), listOf(0.2f, 0.5f, 0.3f), listOf(0.58f, 0.42f))
private const val NOW = 0.3f
