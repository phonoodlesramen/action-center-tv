package com.phonoodlesramen.actioncenter.ui.dice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.dice.DialStop
import com.phonoodlesramen.actioncenter.dice.DiceDeck
import com.phonoodlesramen.actioncenter.dice.DiceFilters
import com.phonoodlesramen.actioncenter.dice.DiceTable
import com.phonoodlesramen.actioncenter.dice.DieLines
import com.phonoodlesramen.actioncenter.dice.DieRoll
import com.phonoodlesramen.actioncenter.dice.DieTitle
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.launch

/** How long a throw tumbles before it lands (spec §7). */
const val TUMBLE_MILLIS = 900

/**
 * The dice tray (spec §4, the die), over the home from the top bar's die. It throws as it
 * opens: one press of the die, and a title. Play, Roll again and Trailer under it; the three
 * dials below (What, Genre, Decade), each chip with how many titles it would leave, and OK on
 * one throws again with it. Back closes it, to the die.
 *
 * The table is read from the Plex cache as it opens ([DiceTable.deck], the die shaking in its
 * cup meanwhile), so nothing waits on the network. Focus stays inside until it closes.
 *
 * @param version the last sync's time: a new one reads the table again.
 * @param onLanded the face the die landed on, for the top bar's.
 */
@Composable
fun DiceTray(
    profile: ActiveProfile,
    version: Long?,
    table: DiceTable,
    now: LocalDateTime,
    lateNight: Boolean,
    actions: HomeActions,
    onLanded: (face: Int) -> Unit,
    onClose: () -> Unit,
) {
    val tray = remember(table, profile.id) { TrayState(table.filters(profile.id), table.face) }
    val tumble = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val entrance = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val primary = remember { FocusRequester() }
    val play = remember { FocusRequester() }
    var rollFocused by remember { mutableStateOf(false) }

    fun throwDie(fresh: Boolean) {
        val deck = tray.deck ?: return
        val throws = if (fresh) 1 else tray.throws + 1
        tray.throws = throws
        val roll = table.roll(profile.id, deck, tray.filters, throws)
        if (roll == null) {
            tray.flight = null
            tray.landed = null
            tray.noDice = true
            return
        }
        tray.noDice = false
        val flight = DieFlight.of(roll, from = tray.face)
        tray.flight = flight
        scope.launch {
            // A newer throw takes the die mid-air: this one's animation is cancelled and never lands.
            tumble.snapTo(0f)
            tumble.animateTo(1f, tween(TUMBLE_MILLIS, easing = LinearEasing))
            if (tray.flight !== flight) return@launch
            val first = tray.landed == null
            tray.face = roll.face
            tray.landed = roll
            tray.flight = null
            table.face = roll.face
            onLanded(roll.face)
            // The first landing moves to Play, unless you've gone off to the dials meanwhile.
            if (first && rollFocused) runCatching { play.requestFocus() }
        }
    }

    fun choose(filters: DiceFilters) {
        val same = filters == tray.filters
        tray.filters = filters
        table.keep(profile.id, filters)
        throwDie(fresh = !same)
    }

    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        runCatching { primary.requestFocus() }
        entrance.animateTo(1f, tween(durationMillis = 200, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(table, profile, version) {
        val deck = table.deck(profile, version)
        tray.deck = deck
        if (!deck.isEmpty) throwDie(fresh = true)
    }
    // Nothing to roll: Close is the one button, and takes focus once it's there.
    LaunchedEffect(tray.emptyTable) {
        if (tray.emptyTable) runCatching { primary.requestFocus() }
    }
    // Shaking the cup while the table is read.
    LaunchedEffect(tray.deck == null) {
        if (tray.deck != null) {
            shake.animateTo(0f, tween(SHAKE_MILLIS))
        } else {
            while (true) {
                shake.animateTo(SHAKE_DEGREES, tween(SHAKE_MILLIS))
                shake.animateTo(-SHAKE_DEGREES, tween(SHAKE_MILLIS * 2))
                shake.animateTo(0f, tween(SHAKE_MILLIS))
            }
        }
    }

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = entrance.value }) {
        Box(Modifier.fillMaxSize().background(Color(0x9E05070A)))
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { translationY = (1f - entrance.value) * 40.dp.toPx() }
                .width(TRAY_WIDTH)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.SheetBackground)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            Stage(tray, tumble, shake, now, lateNight) {
                Buttons(
                    tray = tray,
                    kid = profile.kid,
                    primary = primary,
                    play = play,
                    onRollFocus = { rollFocused = it },
                    onPlay = { title ->
                        onClose()
                        actions.play(title.playKey, title.title)
                    },
                    onRoll = { throwDie(fresh = false) },
                    onTrailer = { title ->
                        onClose()
                        actions.trailer(Pick(id = title.playKey, title = title.title, meta = "", reason = "", trailerKey = title.trailerKey, year = title.year))
                    },
                    onClose = onClose,
                )
            }
            Dials(tray, onChoose = { choose(it) })
            Footer(tray)
        }
    }
}

/** The tray's state: the table, the dials, what's in the air and what it last landed on. */
@Stable
internal class TrayState(filters: DiceFilters, face: Int) {
    var deck by mutableStateOf<DiceDeck?>(null)
    var filters by mutableStateOf(filters)
    var landed by mutableStateOf<DieRoll?>(null)
    var flight by mutableStateOf<DieFlight?>(null)
    var face by mutableIntStateOf(face)
    var throws by mutableIntStateOf(0)

    /** Nothing fits the dials. */
    var noDice by mutableStateOf(false)

    val emptyTable: Boolean get() = deck?.isEmpty == true
}

/**
 * One throw's path through the air: the faces it shows on the way (each unlike the last),
 * ending on the roll's, and how many quarter turns it spins, so it lands square. The same roll
 * always flies the same way.
 */
@Immutable
internal class DieFlight(val roll: DieRoll, val faces: List<Int>, val quarterTurns: Int) {
    companion object {
        private const val FLICKERS = 9

        fun of(roll: DieRoll, from: Int): DieFlight {
            val random = Random(roll.title.ratingKey.hashCode() * 31 + roll.throws)
            val faces = ArrayList<Int>(FLICKERS)
            var last = from
            repeat(FLICKERS - 1) { index ->
                val next = (1..6).filter { it != last && (index < FLICKERS - 2 || it != roll.face) }.random(random)
                faces += next
                last = next
            }
            faces += roll.face
            return DieFlight(roll, faces, quarterTurns = 5 + random.nextInt(4))
        }
    }
}

/** A throw's motion at progress 0..1: it spins and flickers fast then slow, and hops three times, smaller each time. Pure. */
internal object Tumble {
    private val HOPS = listOf(Triple(0f, 0.5f, 1f), Triple(0.5f, 0.78f, 0.33f), Triple(0.78f, 0.92f, 0.08f))
    private const val SQUASH_WIDTH = 0.04f

    fun eased(p: Float): Float = 1f - (1f - p.coerceIn(0f, 1f)).pow(3)

    fun face(flight: DieFlight, p: Float): Int = flight.faces[min(flight.faces.size - 1, (eased(p) * flight.faces.size).toInt())]

    fun turn(flight: DieFlight, p: Float): Float = 90f * flight.quarterTurns * eased(p)

    /** Height off the table, 0..1 of the first hop's. */
    fun hop(p: Float): Float {
        val (from, to, height) = HOPS.firstOrNull { p >= it.first && p < it.second } ?: return 0f
        val q = (p - from) / (to - from)
        return height * 4 * q * (1 - q)
    }

    /** How flat it squashes as it lands, 0 in the air. */
    fun squash(p: Float): Float = HOPS.sumOf { (_, landing, height) ->
        ((1f - abs(p - landing) / SQUASH_WIDTH).coerceAtLeast(0f) * SQUASH * height).toDouble()
    }.toFloat()

    private const val SQUASH = 0.14f
}

/** The top: the die on its table at the left, the title over its art at the right, and [buttons] under the title. */
@Composable
private fun Stage(
    tray: TrayState,
    tumble: Animatable<Float, AnimationVector1D>,
    shake: Animatable<Float, AnimationVector1D>,
    now: LocalDateTime,
    lateNight: Boolean,
    buttons: @Composable () -> Unit,
) {
    val shown = tray.landed?.title
    Box(Modifier.fillMaxWidth().height(STAGE_HEIGHT)) {
        if (shown != null) {
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(ART_SHARE)) {
                TitleArt(id = shown.ratingKey, image = shown.art, lateNight = lateNight, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to Palette.SheetBackground,
                            0.45f to Palette.SheetBackground.copy(alpha = 0.72f),
                            1f to Palette.SheetBackground.copy(alpha = 0.35f),
                        ),
                    ),
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Palette.SheetBackground)))
            }
        }
        Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 24.dp, top = 16.dp, bottom = 12.dp)) {
            DieOnTable(tray, tumble, shake, Modifier.width(DIE_STAGE_WIDTH).fillMaxHeight())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Words(tray, now)
                Spacer(Modifier.weight(1f))
                buttons()
            }
        }
    }
}

/** The big die: the accent's colour with ink pips, its shadow on the table under it. */
@Composable
private fun DieOnTable(
    tray: TrayState,
    tumble: Animatable<Float, AnimationVector1D>,
    shake: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
) {
    val body = LocalAccent.current
    val label = tray.landed?.let { "The die landed on ${it.face}" } ?: "The die"
    Canvas(modifier.semantics { contentDescription = label }) {
        val p = tumble.value
        val flight = tray.flight
        val face = if (flight != null) Tumble.face(flight, p) else tray.face
        val turn = (if (flight != null) Tumble.turn(flight, p) else 0f) + shake.value
        val hop = if (flight != null) Tumble.hop(p) else 0f
        val squash = if (flight != null) Tumble.squash(p) else 0f
        val box = DIE_BOX.toPx()
        val ground = size.height - GROUND_GAP.toPx()
        val bottom = box * (1 - DieGeometry.INSET)
        val left = (size.width - box) / 2
        val top = ground - bottom - hop * HOP_HEIGHT.toPx()
        // The shadow stays on the table: smaller and fainter the higher the die.
        val shadowWidth = box * 0.72f * (1 - 0.35f * hop)
        val shadowHeight = SHADOW_HEIGHT.toPx() * (1 - 0.3f * hop)
        drawOval(
            color = Color.Black.copy(alpha = 0.5f * (1 - 0.6f * hop)),
            topLeft = Offset(size.width / 2 - shadowWidth / 2, ground - shadowHeight / 3),
            size = Size(shadowWidth, shadowHeight),
        )
        translate(left = left, top = top) {
            scale(scaleX = 1 + squash, scaleY = 1 - squash, pivot = Offset(box / 2, bottom)) {
                rotate(turn, pivot = Offset(box / 2, box / 2)) {
                    drawDie(face, body, Palette.AccentInk, area = Size(box, box))
                }
            }
        }
    }
}

/** The eyebrow, the title, what it is, and why it's a fair landing; or what's going on instead. */
@Composable
private fun Words(tray: TrayState, now: LocalDateTime) {
    val accent = LocalAccent.current
    val landed = tray.landed
    when {
        tray.deck == null -> Eyebrow("Shaking the cup…", color = accent)
        tray.emptyTable -> {
            Eyebrow("Nothing on the table", color = accent)
            Title("Nothing to roll yet")
            Line("Once Plex has your library on this TV, the die rolls from it. What you've watched this past year sits out.", maxLines = 3)
        }
        tray.noDice -> {
            Eyebrow("No dice", color = accent)
            Title("Nothing fits all three")
            Line("Turn a dial back to Any.")
        }
        else -> {
            val flying = tray.flight != null
            Eyebrow(if (flying) "Rolling…" else landed?.line.orEmpty(), color = accent)
            if (landed != null) {
                // The last landing stays, faint, while the die is in the air: the card doesn't jump.
                Column(Modifier.graphicsLayer { alpha = if (flying) 0.3f else 1f }) {
                    Title(landed.title.title)
                    Line(DieLines.meta(landed.title), color = Palette.TextDim)
                    Line(DieLines.detail(landed.title, now))
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun Line(text: String, color: Color = Palette.Text, maxLines: Int = 1) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Play, Roll again and Trailer (grown-ups only: its fallback is a YouTube search); Close alone when there's nothing to roll. */
@Composable
private fun Buttons(
    tray: TrayState,
    kid: Boolean,
    primary: FocusRequester,
    play: FocusRequester,
    onRollFocus: (Boolean) -> Unit,
    onPlay: (DieTitle) -> Unit,
    onRoll: () -> Unit,
    onTrailer: (DieTitle) -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tray.emptyTable) {
            PillButton("Close", onClick = onClose, icon = AppIcons.Close, modifier = Modifier.focusRequester(primary))
        } else {
            // Play and Trailer are there from the start, faint until the die lands, so nothing moves under focus.
            val ready = tray.landed?.title?.takeIf { tray.flight == null && !tray.noDice }
            val waiting = Modifier.graphicsLayer { alpha = if (ready != null) 1f else 0.4f }
            PillButton("Play", onClick = { ready?.let(onPlay) }, icon = AppIcons.Play, modifier = waiting.focusRequester(play))
            PillButton(
                text = "Roll again",
                onClick = onRoll,
                icon = AppIcons.Die,
                modifier = Modifier.focusRequester(primary).onFocusChanged { onRollFocus(it.isFocused) }
                    .graphicsLayer { alpha = if (tray.noDice) 0.4f else 1f },
            )
            if (!kid) PillButton("Trailer", onClick = { ready?.let(onTrailer) }, icon = AppIcons.OpenIn, modifier = waiting)
        }
    }
}

/** What, Genre and Decade: a row of chips each, the chosen one ticked. Room is kept for them while the table is read. */
@Composable
private fun Dials(tray: TrayState, onChoose: (DiceFilters) -> Unit) {
    val deck = tray.deck
    val filters = tray.filters
    Column(Modifier.fillMaxWidth().height(DIALS_HEIGHT).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (deck != null && !deck.isEmpty) {
            val kinds = remember(deck, filters) { deck.kinds(filters) }
            val genres = remember(deck, filters) { deck.genres(filters) }
            val decades = remember(deck, filters) { deck.decades(filters) }
            Dial("What", kinds, filters.kind, keyOf = { it.name }) { onChoose(filters.copy(kind = it)) }
            Dial("Genre", genres, filters.genre, keyOf = { it ?: ANY_KEY }) { onChoose(filters.copy(genre = it)) }
            Dial("Decade", decades, filters.decade, keyOf = { it?.toString() ?: ANY_KEY }) { onChoose(filters.copy(decade = it)) }
        }
    }
}

@Composable
private fun <T> Dial(name: String, stops: List<DialStop<T>>, chosen: T, keyOf: (T) -> String, onChoose: (T) -> Unit) {
    // Opens with the chosen chip in view, however far along it is.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (stops.indexOfFirst { it.value == chosen } - 1).coerceAtLeast(0))
    Row(Modifier.fillMaxWidth().height(DIAL_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = Palette.TextDim, modifier = Modifier.width(DIAL_LABEL_WIDTH), maxLines = 1)
        LazyRow(
            state = list,
            modifier = Modifier.weight(1f).focusRestorer(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(stops, key = { keyOf(it.value) }) { stop ->
                DialChip(stop.label, stop.count, chosen = stop.value == chosen, onClick = { onChoose(stop.value) })
            }
        }
    }
}

/** A chip: its label and count (faint at none), ticked and in the accent when chosen; filled with the accent when focused. */
@Composable
private fun DialChip(label: String, count: Int, chosen: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Surface(
        onClick = onClick,
        modifier = Modifier.height(CHIP_HEIGHT).semantics { selected = chosen },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (chosen) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
            contentColor = when {
                chosen -> accent
                count == 0 -> Palette.TextFaint
                else -> Palette.Text
            },
            focusedContainerColor = accent,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (chosen) Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(counted(count), style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}

/** "Back to close", and how much is on the table: "3,612 on the table", or "47 of 3,612 fit". */
@Composable
private fun Footer(tray: TrayState) {
    val deck = tray.deck
    val fit = remember(deck, tray.filters) { deck?.matching(tray.filters)?.size }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Back to close", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        Spacer(Modifier.weight(1f))
        if (deck != null && fit != null && !deck.isEmpty) {
            val all = counted(deck.titles.size)
            val text = if (tray.filters.open) "$all on the table" else "${counted(fit)} of $all fit"
            Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
        }
    }
}

private fun counted(n: Int): String = String.format(Locale.US, "%,d", n)

private const val ANY_KEY = "any"
private const val SHAKE_MILLIS = 70
private const val SHAKE_DEGREES = 9f

private val TRAY_WIDTH = 640.dp
private val STAGE_HEIGHT = 196.dp
private const val ART_SHARE = 0.64f
private val DIE_STAGE_WIDTH = 136.dp
private val DIE_BOX = 104.dp
private val HOP_HEIGHT = 44.dp
private val GROUND_GAP = 14.dp
private val SHADOW_HEIGHT = 10.dp
private val CHIP_HEIGHT = 30.dp
private val DIAL_HEIGHT = 38.dp
private val DIALS_HEIGHT = 3 * 38.dp + 2 * 2.dp
private val DIAL_LABEL_WIDTH = 60.dp
