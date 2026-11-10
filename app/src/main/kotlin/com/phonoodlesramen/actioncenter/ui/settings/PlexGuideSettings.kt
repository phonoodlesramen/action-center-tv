package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.EditRejected
import com.phonoodlesramen.actioncenter.guide.EditableChannel
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.profiles.AgeBracket
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.station.GuideStationController
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Settings › Plex Guide (G1): the guide's status, Edit on your phone ([onEditOnPhone]),
 * Remake the starter lineup (after a confirm step), and every channel with its number,
 * name, what's on now and an on/off switch. All of it works with the D-pad:
 * - **OK** on a channel turns it on or off. An off channel leaves the Live row, the Guide
 *   card and the full guide, and keeps its schedule, so it's back at once.
 * - **Hold OK, or press Menu,** to pick a channel up: Up and Down move it, and OK, Back or
 *   Menu put it down. The lineup is then numbered 1 to n in its new order.
 * - **Right** reaches the channel's Ratings (R2), and **OK** there steps through Any and
 *   each age bracket's ratings from Settings › Profiles ([brackets]). Each step saves the
 *   rule's content ratings, for everyone, from the end of the program on now (G16). A list
 *   set on the phone that's no bracket's shows as Custom until it's changed.
 * - A change the editor can't make shows as a line of text, never a crash: on the
 *   channel's row for that channel's change, under the buttons for a remake.
 *
 * - **The guide station** (Phase 8, [station]): the first row opens Settings › Plex Guide ›
 *   Guide station ([GuideStationPage]). While this TV is paired and can't reach it, editing
 *   is greyed out with why: there's no offline queue.
 *
 * @param editor null in previews: the section shows without a lineup.
 * @param brackets Settings › Profiles' age brackets (K3), for the Ratings choices.
 * @param nowMillis and [zone] write "scheduled until 9:30 PM tomorrow".
 * @param station null in previews: the section shows without the guide station's row.
 */
@Composable
fun ColumnScope.PlexGuideSettings(
    editor: GuideEditor?,
    onEditOnPhone: () -> Unit,
    brackets: List<AgeBracket> = AgeBrackets.DEFAULT,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    station: GuideStationController? = null,
) {
    val stationView = station?.let { remember(it) { it.view }.collectAsState().value }
    var stationOpen by remember { mutableStateOf(false) }
    // Back from the station's page lands on its row again.
    var backFromStation by remember { mutableStateOf(false) }
    val stationRow = remember { FocusRequester() }
    if (station != null && stationView != null && stationOpen) {
        GuideStationPage(stationView, station, nowMillis, zone) {
            stationOpen = false
            backFromStation = true
        }
        return
    }
    if (station != null && stationView != null) {
        LaunchedEffect(Unit) {
            if (backFromStation) runCatching { stationRow.requestFocus() }
            backFromStation = false
        }
        GuideStationRow(stationView, nowMillis, onClick = { stationOpen = true }, modifier = Modifier.focusRequester(stationRow))
    }
    // While paired and the station can't be reached (or unpaired this TV), nothing can be changed.
    val locked = stationView?.editingLocked
    PlexGuideLineup(editor, onEditOnPhone, brackets, nowMillis, zone, locked)
}

/** The lineup and its editing; [locked] greys every change out, with why. */
@Composable
private fun ColumnScope.PlexGuideLineup(
    editor: GuideEditor?,
    onEditOnPhone: () -> Unit,
    brackets: List<AgeBracket>,
    nowMillis: Long,
    zone: ZoneId,
    locked: String?,
) {
    val lineup by remember(editor) { editor?.lineup ?: emptyFlow() }.collectAsState(initial = if (editor == null) emptyList() else null)
    val ratingsChoices = remember(brackets) { ChannelRatings.choices(brackets) }
    // Ratings picked on the TV, by channel, until the lineup shows them; saved one at a time, in order.
    var picked by remember { mutableStateOf(emptyMap<String, Picked>()) }
    val saves = remember { Mutex() }
    var problem by remember { mutableStateOf<Turned?>(null) }
    var moving by remember { mutableStateOf<Move?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var remaking by remember { mutableStateOf(false) }
    // Back on Remake the starter lineup once its confirm step closes, as Weather's Change does.
    var refocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val remake = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }

    /**
     * Runs a change. One the editor turns down shows its sentence, on [channelId]'s row (where
     * the focus is) or else under the buttons, until the next change works. [after] hears
     * whether it worked.
     */
    fun change(channelId: String?, block: suspend GuideEditor.() -> Unit, after: (Boolean) -> Unit = {}) {
        val target = editor ?: return
        // Greyed out: the press says why, on the row or under the buttons, and nothing is sent.
        if (locked != null) {
            problem = Turned("$locked.", channelId)
            after(false)
            return
        }
        scope.launch {
            val rejected = try {
                target.block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: EditRejected) {
                e.message ?: CANT
            } catch (e: Exception) {
                // The editor turns its own failures into EditRejected; this is only a guard.
                CANT
            }
            problem = rejected?.let { Turned(it, channelId) }
            after(rejected == null)
        }
    }

    fun closeConfirm() {
        confirming = false
        refocus = true
    }

    val channels = lineup
    // While a channel is picked up, and until the lineup shows where it was put, the list shows the new order.
    val shown = channels?.let { list -> moving?.arrange(list) ?: list }.orEmpty()
    LaunchedEffect(channels) {
        val current = moving
        if (current != null && current.placed && (current.saved || channels?.map { it.id } == current.order)) moving = null
    }

    /** Puts the carried channel down: saved where it is now, or left alone if it's back where it was. */
    fun putDown() {
        val current = moving ?: return
        val to = current.order.indexOf(current.id)
        if (to == current.from) {
            moving = null
            return
        }
        moving = current.copy(placed = true)
        change(current.id, { move(current.id, to) }) { worked ->
            // A move that fails shows the lineup as it is again; one that worked waits for the lineup to show it.
            val placed = moving?.takeIf { it.placed && it.id == current.id }
            if (placed != null) moving = if (!worked || lineup?.map { it.id } == placed.order) null else placed.copy(saved = true)
        }
    }

    // A pick shows until its saves have landed and the lineup shows it.
    LaunchedEffect(channels, picked) {
        val list = channels ?: return@LaunchedEffect
        val landed = picked.filter { (id, pick) ->
            val saved = list.firstOrNull { it.id == id } ?: return@filter true
            pick.saving == 0 && saved.draft.rule.contentRatings.toSet() == pick.ratings.toSet()
        }
        if (landed.isNotEmpty()) picked = picked - landed.keys
    }

    /** The channel's ratings as the row shows them: one picked here until it's saved, else its rule's. */
    fun ratingsOf(channel: EditableChannel): List<String> = picked[channel.id]?.ratings ?: channel.draft.rule.contentRatings

    /** Steps [channel]'s Ratings on to the next choice and saves it as its rule's content ratings. */
    fun nextRatings(channel: EditableChannel) {
        if (editor == null) return
        if (locked != null) return change(channel.id, {})
        val next = ChannelRatings.next(ratingsOf(channel), ratingsChoices)
        val id = channel.id
        picked = picked + (id to Picked(next.ratings, saving = (picked[id]?.saving ?: 0) + 1))
        val draft = channel.draft.copy(rule = channel.draft.rule.copy(contentRatings = next.ratings))
        // One save at a time, in the order they were asked for, so quick presses land as the last one shows.
        change(id, { saves.withLock { update(id, draft) } }) { worked ->
            val pick = picked[id] ?: return@change
            // One turned down shows the lineup's ratings again, and the sentence on the row.
            picked = if (worked) picked + (id to pick.copy(saving = pick.saving - 1)) else picked - id
        }
    }

    Text(status(channels, nowMillis, zone), style = MaterialTheme.typography.titleSmall)
    if (locked != null) Problem("$locked. Channels can't be changed until it's back, here or on your phone.")
    // Greyed out while the station can't be reached: still focusable, so the lineup can be read.
    val greyed = if (locked != null) Modifier.alpha(LOCKED_ALPHA) else Modifier
    if (!channels.isNullOrEmpty()) {
        Hint("OK turns a channel on or off. Off channels leave the Live row and the guide, and keep their schedule. Hold OK or press Menu to move one.")
        Hint(
            "Right, then OK, sets a channel's ratings for everyone, after the program on now. " +
                "Kids see the channels whose titles are all within their ratings.",
        )
    }

    if (confirming) {
        BackHandler(onBack = ::closeConfirm)
        LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
        Text("Remake the starter lineup?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        Hint("Every channel is replaced by a new lineup from your library, including channels you made or changed on your phone.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                text = "Remake",
                icon = AppIcons.Restart,
                onClick = {
                    closeConfirm()
                    remaking = true
                    change(channelId = null, { remakeStarterLineup() }) { remaking = false }
                },
            )
            PillButton(
                text = "Cancel",
                onClick = ::closeConfirm,
                colors = pillButtonColors(container = Palette.Surface2),
                modifier = Modifier.focusRequester(cancel),
            )
        }
    } else {
        LaunchedEffect(Unit) {
            if (refocus) runCatching { remake.requestFocus() }
            refocus = false
        }
        Row(greyed, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(text = "Edit on your phone", onClick = { if (locked == null) onEditOnPhone() else change(null, {}) })
            PillButton(
                text = "Remake the starter lineup",
                icon = AppIcons.Restart,
                onClick = { if (locked == null) confirming = true else change(null, {}) },
                colors = pillButtonColors(container = Palette.Surface2),
                modifier = Modifier.focusRequester(remake),
            )
        }
    }
    if (remaking) Hint("Making a new lineup from your library…")
    problem?.takeIf { it.channelId == null }?.let { Problem(it.text) }

    Eyebrow("Channels", Modifier.padding(top = 8.dp))
    when {
        channels == null -> Hint("Reading the lineup…")
        channels.isEmpty() -> Hint(
            "A starter lineup is made from your library once Plex has synced it. " +
                "Remake the starter lineup makes one now.",
        )
        else -> Column(greyed.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEachIndexed { index, channel ->
                key(channel.id) {
                    val current = moving
                    // The channel, then its Ratings to the right, as tall as each other.
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChannelRow(
                            channel = channel,
                            // Moving numbers the lineup 1 to n, so that's what it shows meanwhile.
                            number = if (current != null) index + 1 else channel.number,
                            carried = current != null && !current.placed && current.id == channel.id,
                            problem = problem?.takeIf { it.channelId == channel.id }?.text,
                            onToggle = { change(channel.id, { setEnabled(channel.id, !channel.enabled) }) },
                            onPickUp = { if (locked != null) change(channel.id, {}) else if (moving == null) moving = Move(channel.id, shown.map { it.id }) },
                            onStep = { delta -> moving = moving?.step(delta) },
                            onPutDown = ::putDown,
                            modifier = Modifier.weight(1f),
                        )
                        RatingsChip(
                            choice = ChannelRatings.of(ratingsOf(channel), ratingsChoices),
                            onClick = { nextRatings(channel) },
                            modifier = Modifier.fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

private const val CANT = "That didn't work. Try again."

/** How dim editing is while the guide station can't be reached. */
private const val LOCKED_ALPHA = 0.45f

/** A change the editor turned down, and the channel it was for (null: the whole lineup). */
private data class Turned(val text: String, val channelId: String?)

/** A channel's Ratings picked on the TV, and how many of its saves are still to land. */
private data class Picked(val ratings: List<String>, val saving: Int)

/** A channel's Ratings in Settings › Plex Guide (R2): "Ratings: Ages 7–9", and under it "Up to PG and TV-PG". */
internal data class RatingsChoice(
    /** "Any", "Ages 7–9" or "Custom". */
    val label: String,
    /** What it lets the channel play: "No limit", "Up to PG and TV-PG", or a Custom list. */
    val detail: String,
    /** The rule's content ratings, films then TV, each from the youngest; empty for Any. */
    val ratings: List<String>,
)

/**
 * The Ratings choices for a channel's rule (R2): Any (no ratings in the rule), then each age
 * bracket's ratings from Settings › Profiles (K3), youngest first. A rule's list that's none
 * of them is Custom (set on the phone) until it's changed. Pure.
 */
internal object ChannelRatings {
    val ANY = RatingsChoice("Any", "No limit", emptyList())

    /**
     * Any, then a choice per bracket, youngest first. A bracket with no ratings ticked is
     * left out (it would read as Any), and so is one with the same ratings as a younger one.
     */
    fun choices(brackets: List<AgeBracket>): List<RatingsChoice> {
        val seen = HashSet<Set<String>>()
        val byAge = brackets.sortedBy { it.fromAge }.filter { it.ratings.isNotEmpty() && seen.add(it.ratings) }.map { bracket ->
            val label = if (bracket.fromAge == bracket.toAge) "Age ${bracket.fromAge}" else "Ages ${bracket.fromAge}–${bracket.toAge}"
            RatingsChoice(label, "Up to ${ceilingSummary(bracket.ratings)}", ordered(bracket.ratings))
        }
        return listOf(ANY) + byAge
    }

    /** What a rule naming [ratings] shows as: Any, the bracket with exactly those, or Custom. */
    fun of(ratings: List<String>, choices: List<RatingsChoice>): RatingsChoice {
        if (ratings.isEmpty()) return ANY
        val set = ratings.toSet()
        return choices.firstOrNull { it.ratings.isNotEmpty() && it.ratings.toSet() == set }
            ?: RatingsChoice("Custom", ordered(set).joinToString(", "), ordered(set))
    }

    /** The choice after [ratings]' in [choices], round to Any; from Custom, the youngest bracket's. */
    fun next(ratings: List<String>, choices: List<RatingsChoice>): RatingsChoice {
        val at = choices.indexOf(of(ratings, choices))
        return if (at < 0) choices.getOrElse(1) { ANY } else choices[(at + 1) % choices.size]
    }

    /** Films then TV, each from the youngest, as Settings › Profiles lists them; any others after, A to Z. */
    private fun ordered(ratings: Set<String>): List<String> {
        val known = FILM_RATINGS + TV_RATINGS
        return known.filter { it in ratings } + (ratings - known.toSet()).sorted()
    }
}

/**
 * A channel picked up to move: the lineup's ids in their new order, and where it was
 * ([from]). [placed] once it's put down, and [saved] once the editor has saved it, until
 * the lineup shows the new order.
 */
private data class Move(
    val id: String,
    val order: List<String>,
    val from: Int = order.indexOf(id),
    val placed: Boolean = false,
    val saved: Boolean = false,
) {
    fun step(delta: Int): Move {
        val at = order.indexOf(id)
        val to = (at + delta).coerceIn(0, order.lastIndex)
        if (at == to) return this
        return copy(order = order.toMutableList().apply { add(to, removeAt(at)) })
    }

    /** [channels] in this order; one added meanwhile goes at the end. */
    fun arrange(channels: List<EditableChannel>): List<EditableChannel> {
        val byId = channels.associateBy { it.id }
        val ordered = order.mapNotNull { byId[it] }
        return ordered + channels.filter { it.id !in order }
    }
}

/** The keys a carried row keeps to itself. */
private val CarriedKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back, Key.Escape,
)

/**
 * "18 channels, 2 off · scheduled until 9:30 PM tomorrow": how far ahead every channel
 * that's on is scheduled.
 */
private fun status(channels: List<EditableChannel>?, nowMillis: Long, zone: ZoneId): String {
    if (channels == null) return "Reading the lineup…"
    if (channels.isEmpty()) return "No channels yet"
    val off = channels.count { !it.enabled }
    val count = "${channels.size} ${if (channels.size == 1) "channel" else "channels"}" + if (off > 0) ", $off off" else ""
    val until = channels.filter { it.enabled }.mapNotNull { it.scheduledUntil }.minOrNull() ?: return count
    val at = Instant.ofEpochMilli(until).atZone(zone)
    val day = when (ChronoUnit.DAYS.between(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(), at.toLocalDate())) {
        0L -> "today"
        1L -> "tomorrow"
        else -> at.format(DAY)
    }
    return "$count · scheduled until ${at.format(CLOCK)} $day"
}

private val CLOCK = DateTimeFormatter.ofPattern("h:mm a")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * One channel. The whole row is its on/off switch; held (or Menu) it's picked up, and while
 * [carried] it takes Up and Down to move (Left and Right too, so it can't reach its Ratings)
 * and stays focused until it's put down.
 */
@Composable
private fun ChannelRow(
    channel: EditableChannel,
    number: Int,
    carried: Boolean,
    /** Why its last change was turned down, shown in place of what's on now. */
    problem: String?,
    onToggle: () -> Unit,
    onPickUp: () -> Unit,
    onStep: (Int) -> Unit,
    onPutDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val bring = remember { BringIntoViewRequester() }
    // The OK press that puts it down, as against the release of the hold that picked it up.
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(carried, number) { if (carried) runCatching { bring.bringIntoView() } }

    /** True when the key is the row's: Menu always, and while it's carried, the D-pad, OK and Back. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.Menu -> {
                if (!down) if (carried) onPutDown() else onPickUp()
                return true
            }
            !in CarriedKeys -> return false
        }
        if (!carried) return false
        when (event.key) {
            Key.DirectionUp -> if (down) onStep(-1)
            Key.DirectionDown -> if (down) onStep(1)
            Key.Back, Key.Escape -> if (!down) onPutDown()
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when {
                down -> if (event.nativeKeyEvent.repeatCount == 0) pressed = true
                pressed -> {
                    pressed = false
                    onPutDown()
                }
                // The release of the hold that picked it up goes on to the row, so it isn't taken as OK.
                else -> return false
            }
        }
        return true
    }

    Surface(
        onClick = onToggle,
        onLongClick = onPickUp,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .onPreviewKeyEvent(::onKey),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        border = if (carried) {
            ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, accent), shape = RoundedCornerShape(10.dp)))
        } else {
            ClickableSurfaceDefaults.border()
        },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (carried) accent else Palette.TextDim,
                modifier = Modifier.width(44.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    channel.draft.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (channel.enabled || carried) Palette.Text else Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        carried -> "Up or down to move it, then OK"
                        problem != null -> problem
                        else -> channel.onNow ?: "Nothing scheduled right now"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        carried -> accent
                        problem != null -> Palette.Caution
                        else -> Palette.TextDim
                    },
                    // A sentence from the editor may need two lines.
                    maxLines = if (problem != null && !carried) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            if (carried) {
                Icon(AppIcons.Move, contentDescription = "Moving", tint = accent, modifier = Modifier.size(22.dp))
            } else {
                OnOff(on = channel.enabled)
            }
        }
    }
}

/** The row's switch, as Channels & apps draws it. */
@Composable
private fun OnOff(on: Boolean) {
    val accent = LocalAccent.current
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            // Off stays visible on the focused row's Surface3 too.
            .background(if (on) accent else Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(18.dp)
                .background(if (on) Palette.AccentInk else Palette.TextDim, CircleShape),
        )
    }
}

/**
 * A channel's Ratings (R2), to the right of its row and as tall: "Ratings: Ages 7–9" and,
 * under it, what that lets it play. OK steps on to the next choice ([ChannelRatings.next]).
 */
@Composable
private fun RatingsChip(choice: RatingsChoice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(RATINGS_WIDTH),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Text("Ratings: ${choice.label}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(choice.detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Wide enough for "Ratings: Ages 10–12" and "Up to PG-13 and TV-PG". */
private val RATINGS_WIDTH = 196.dp

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
}

/** A change the editor turned down, in its own words. */
@Composable
private fun Problem(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(AppIcons.Warning, contentDescription = null, tint = Palette.Caution, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
    }
}
