package com.phonoodlesramen.actioncenter.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.player.GuidePlayerState
import com.phonoodlesramen.actioncenter.player.TrackKind
import com.phonoodlesramen.actioncenter.player.TrackMenu
import com.phonoodlesramen.actioncenter.ui.guide.favoriteLabel
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.QuickSheet
import com.phonoodlesramen.actioncenter.ui.home.SheetItem
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/** What the player's overlays can ask for. Defaults do nothing (screenshots). */
interface PlayerActions {
    /** The program on screen from its start (G11). */
    fun startOver() {}

    /** Whatever's on now (G11). */
    fun backToLive() {}

    /** The control row's ⏯ (P2): pauses, or carries on behind live. */
    fun playPause() {}

    /** The control row's ⏪ 10 and ⏩ 30 (P1, P2). */
    fun skip(forward: Boolean) {}

    /** Back with the control row up (P2). */
    fun hideControls() {}

    /** The options' Audio or Subtitles list. */
    fun openTracks(kind: TrackKind) {}

    /** A track for this program only (G14); null turns subtitles off. */
    fun chooseTrack(kind: TrackKind, streamId: String?) {}

    fun openDetails() {}

    /** Back on an overlay, or an action that's done with it. */
    fun closeOverlay() {}

    /**
     * Watch in Plezy or Plex (Phase 11): [slot]'s title handed off where the guide is, or null
     * when it can't be: no file, or the server isn't known.
     */
    fun keepWatching(slot: GuideSlot): KeepWatching? = null

    /** Back on the credits card (K5): it goes, and the channel carries on. */
    fun dismissCreditsCard() {}

    /** Settings › Plex › Play in (Phase 11): which app the credits card names. */
    fun playIn(): PlayIn = PlayIn.PLEZY

    /** The options' Add {channel} to favorites, or Remove (Phase 13, C2): the channel on screen. A toast says what changed. */
    fun setFavorite(favorite: Boolean) {}

    companion object None : PlayerActions
}

/**
 * Watch in Plezy or Plex (Phase 11): [app] is Settings › Plex › Play in, and [run] hands the
 * title off, from the frame on screen when it's the program playing.
 */
class KeepWatching(val app: PlayIn, val run: () -> Unit) {
    val label: String get() = "Watch in ${app.label}"
}

/**
 * The options sheet (hold OK or Menu, plan §1): the home's quick actions sheet (U6), with
 * what can be done with the program on screen, six rows at most (they fit at 115%). First,
 * Watch in Plezy or Plex (Phase 11) for a program with a file once the server's known; then
 * Start over when it has a file, Back to live when behind, the tracks once they're matched
 * (G13), Details, and Add {channel} to favorites, or Remove (Phase 13, C2).
 *
 * To stay at six with favorites (Phase 13), a file with both kinds of track has one row,
 * "Audio and subtitles", whose sheet lists both ([TrackSheet]).
 */
internal fun optionsSheet(state: GuidePlayerState, actions: PlayerActions): QuickSheet = QuickSheet(
    title = state.slot?.title ?: state.channel?.name.orEmpty(),
    items = listOfNotNull(
        state.slot?.let(actions::keepWatching)?.let { SheetItem(AppIcons.OpenIn, it.label, run = it.run) },
        SheetItem(AppIcons.StartOver, "Start over") { actions.startOver() }.takeIf { state.canStartOver },
        SheetItem(PlayerIcons.Live, "Back to live") { actions.backToLive() }.takeIf { state.behind != null },
        tracksItem(state.tracks, actions),
        SheetItem(AppIcons.Info, "Details") { actions.openDetails() }.takeIf { state.detailsSlot != null },
        state.channel?.let { channel -> SheetItem(AppIcons.Star, favoriteLabel(channel.name, state.favorite)) { actions.setFavorite(!state.favorite) } },
    ),
)

/** Audio, Subtitles, or both in one row once the file has both; null before its tracks are matched. */
private fun tracksItem(tracks: TrackMenu, actions: PlayerActions): SheetItem? {
    val audio = tracks.audio.isNotEmpty()
    val subtitles = tracks.subtitles.isNotEmpty()
    return when {
        audio && subtitles -> SheetItem(PlayerIcons.Subtitles, "Audio and subtitles") { actions.openTracks(TrackKind.AUDIO) }
        audio -> SheetItem(PlayerIcons.Audio, "Audio") { actions.openTracks(TrackKind.AUDIO) }
        subtitles -> SheetItem(PlayerIcons.Subtitles, "Subtitles") { actions.openTracks(TrackKind.SUBTITLES) }
        else -> null
    }
}

/**
 * The options' Audio or Subtitles list: Plex's names for the file's tracks (G13), Off first
 * for subtitles, the one on now ticked and focused. It looks like the quick actions sheet,
 * but scrolls: a film can carry a dozen subtitle tracks. A choice is for this program only
 * (G14).
 *
 * A file with both kinds is "Audio and subtitles" (Phase 13): the audio list, then the
 * subtitles under their own eyebrow, [kind]'s track on now focused.
 */
@Composable
internal fun TrackSheet(kind: TrackKind, program: String, menu: TrackMenu, onChoose: (TrackKind, String?) -> Unit, onClose: () -> Unit) {
    val entrance = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(kind) {
        runCatching { focus.requestFocus() }
        entrance.animateTo(1f, tween(durationMillis = OVERLAY_MILLIS, easing = FastOutSlowInEasing))
    }
    val kinds = listOf(TrackKind.AUDIO, TrackKind.SUBTITLES).filter { menu.of(it).isNotEmpty() }.ifEmpty { listOf(kind) }
    val both = kinds.size > 1
    val rows = buildList {
        kinds.forEach { each ->
            if (each == TrackKind.SUBTITLES) add(TrackChoice(each, null, "Off", menu.subtitlesOff))
            menu.of(each).forEach { add(TrackChoice(each, it.streamId, it.label, it.selected)) }
        }
    }
    // [kind]'s one on now takes focus; with none on (audio still choosing), its first.
    val focused = rows.indexOfFirst { it.kind == kind && it.selected }.takeIf { it >= 0 } ?: rows.indexOfFirst { it.kind == kind }.coerceAtLeast(0)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = entrance.value }) {
        Box(Modifier.fillMaxSize().background(SHEET_SCRIM))
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .graphicsLayer { translationX = (1f - entrance.value) * SHEET_SLIDE.toPx() }
                .fillMaxHeight()
                .width(SHEET_WIDTH)
                .background(Palette.SheetBackground)
                .padding(horizontal = 28.dp, vertical = 27.dp),
        ) {
            Eyebrow(program)
            Text(
                text = when {
                    both -> "Audio and subtitles"
                    kind == TrackKind.AUDIO -> "Audio"
                    else -> "Subtitles"
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )
            // The rows take the room down to the hint, and scroll within it.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    if (both && rows.getOrNull(index - 1)?.kind != row.kind) {
                        Eyebrow(if (row.kind == TrackKind.AUDIO) "Audio" else "Subtitles", Modifier.padding(top = if (index == 0) 0.dp else 10.dp, bottom = 2.dp))
                    }
                    TrackRow(row.label, row.selected, onClick = { onChoose(row.kind, row.streamId) }, modifier = if (index == focused) Modifier.focusRequester(focus) else Modifier)
                }
            }
            Text("Back to close", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** One row of [TrackSheet]: a track of [kind], or subtitles' Off ([streamId] null). */
private data class TrackChoice(val kind: TrackKind, val streamId: String?, val label: String, val selected: Boolean)

/** A sheet row (44 dp, the accent when focused), with a tick for the track that's on. */
@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected) Icon(AppIcons.Check, contentDescription = "On", modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The quick actions sheet's own look (spec §4, U6): 336 dp from the right over a dimmed picture, sliding in 40 dp. */
private val SHEET_WIDTH = 336.dp
private val SHEET_SLIDE = 40.dp
private val SHEET_SCRIM = Color(0x9E05070A)
private const val OVERLAY_MILLIS = 200
