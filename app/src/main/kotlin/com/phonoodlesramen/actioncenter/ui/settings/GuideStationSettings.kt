package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.guide.station.PairState
import com.phonoodlesramen.actioncenter.guide.station.StationDevice
import com.phonoodlesramen.actioncenter.guide.station.StationInfo
import com.phonoodlesramen.actioncenter.station.GuideStationController
import com.phonoodlesramen.actioncenter.station.StationPhase
import com.phonoodlesramen.actioncenter.station.StationReach
import com.phonoodlesramen.actioncenter.station.StationView
import com.phonoodlesramen.actioncenter.ui.guide.BackToText
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Settings › Plex Guide › Guide station's words, apart from Compose so they're unit-tested. */
internal object StationText {
    /** The rooms offered first; any other is typed. */
    val ROOMS = listOf("Living room", "Kitchen", "Bedroom")

    /** A 1.1 station answers favorites' calls 404 (Phase 13). */
    const val FAVORITES_NEED_UPDATE = "The station needs an update for favorites. Until then, this TV keeps its own."

    /** The line under "Guide station" on Settings › Plex Guide. */
    fun entry(view: StationView, nowMillis: Long): String = when (val phase = view.phase) {
        StationPhase.Unpaired -> if (view.paired) status(view, nowMillis) else "Not paired: this TV makes its own guide"
        is StationPhase.Pairing -> if (phase.state == PairState.PENDING) "Pairing · code ${phase.code}" else "Pairing didn't finish"
        is StationPhase.Paired -> listOf(view.room, status(view, nowMillis).replaceFirstChar { it.lowercase() }).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** "Connected · updated 3 min ago", "Can't reach the guide station · updated 2 hr ago" or "Unpaired by the station". */
    fun status(view: StationView, nowMillis: Long): String {
        val updated = view.checkedAtMillis?.let { " · updated ${ago(nowMillis - it)}" }.orEmpty()
        return when (view.reach) {
            StationReach.CONNECTED -> "Connected$updated"
            StationReach.UNREACHABLE -> "${StationView.CANT_REACH}$updated"
            StationReach.UNPAIRED -> "Unpaired by the station"
        }
    }

    /** "just now", "9 min ago", "2 hr ago", "3 days ago". */
    fun ago(millis: Long): String {
        val minutes = millis.coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} hr ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /** "Signed in · Den PC", and the library's last sync, or its problem. */
    fun plex(info: StationInfo, nowMillis: Long): Pair<String, String> {
        val server = info.plex.serverName ?: "your Plex server"
        val library = info.library
        val error = library.error
        val syncedAt = library.lastSyncMillis
        val synced = when {
            error != null -> "The station's last sync hit a problem: $error"
            syncedAt != null -> "Its library synced ${ago(nowMillis - syncedAt)}."
            else -> "Its library hasn't synced yet."
        }
        return "Signed in to Plex · $server" to synced
    }

    /** A TV on the station: "SHIELD Android TV · paired 12 Oct · seen 5 min ago", or a pending one's code. */
    fun device(device: StationDevice, nowMillis: Long, zone: ZoneId): String = if (device.pending) {
        listOf("Waiting for approval", "code ${device.code ?: "not shown"}", device.model).filter { it.isNotBlank() }.joinToString(" · ")
    } else {
        listOfNotNull(
            device.model.takeIf { it.isNotBlank() },
            device.pairedAtMillis?.let { "paired ${Instant.ofEpochMilli(it).atZone(zone).format(DAY)}" },
            device.lastSeenMillis?.let { "seen ${ago(nowMillis - it)}" },
        ).joinToString(" · ")
    }

    /** "Waiting for approval · the code works until 9:46 PM". */
    fun waiting(expiresAtMillis: Long, zone: ZoneId): String =
        "Waiting for approval · the code works until ${Instant.ofEpochMilli(expiresAtMillis).atZone(zone).format(CLOCK)}"

    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a")
    private val DAY = DateTimeFormatter.ofPattern("d MMM")
}

/**
 * Settings › Plex Guide's first row (Phase 8): the guide station, and where this TV stands
 * with it. OK opens [GuideStationPage].
 */
@Composable
internal fun GuideStationRow(view: StationView, nowMillis: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Guide station", style = MaterialTheme.typography.titleSmall)
                Text(
                    StationText.entry(view, nowMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (view.editingLocked != null) Palette.Caution else Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (view.paired) {
                Spacer(Modifier.width(16.dp))
                Dot(dotColour(view.reach))
            }
        }
    }
}

/**
 * Settings › Plex Guide › Guide station (Phase 8): pairing this TV with the station on the
 * NAS, and once paired, its status, the station's own Plex sign-in, the house's TVs, and
 * leaving. Everything works with the D-pad; the address and a room of your own are typed
 * with the TV keyboard. Back returns to Plex Guide.
 */
@Composable
internal fun ColumnScope.GuideStationPage(
    view: StationView,
    station: GuideStationController,
    nowMillis: Long,
    zone: ZoneId,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // While it's open: the station, its TVs and its Plex sign-in, kept current.
    LaunchedEffect(station) { station.follow() }
    Column {
        Text("Guide station", style = MaterialTheme.typography.titleMedium)
        Text("Back returns to Plex Guide", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
    }
    when (val phase = view.phase) {
        StationPhase.Unpaired -> UnpairedPage(view, station)
        is StationPhase.Pairing -> PairingPage(view, phase, station, zone)
        is StationPhase.Paired -> PairedPage(view, phase, station, nowMillis, zone)
    }
}

/** Not paired: the address, this TV's room, and Pair. */
@Composable
private fun ColumnScope.UnpairedPage(view: StationView, station: GuideStationController) {
    var address by remember { mutableStateOf(view.address) }
    var room by remember { mutableStateOf(view.room) }
    var other by remember { mutableStateOf(view.room.isNotEmpty() && view.room !in StationText.ROOMS) }
    val field = remember { FocusRequester() }
    val pair = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { (if (view.address.isEmpty()) field else pair).requestFocus() } }

    Note(
        "A guide station on your NAS keeps one guide for the house: every paired TV shows the same programs at the same " +
            "times, a change made on one reaches them all, and Back to works from any room. Until this TV pairs, it makes its own guide.",
    )
    Eyebrow("Station address", Modifier.padding(top = 4.dp))
    StationField(
        value = address,
        onChange = { address = it.take(MAX_ADDRESS).trim() },
        placeholder = "The NAS's address, like 192.168.1.20",
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.focusRequester(field),
    )
    Note("Port 8443, unless you add another after a colon, like 192.168.1.20:9443.")
    Eyebrow("This TV's room", Modifier.padding(top = 8.dp))
    Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StationText.ROOMS.forEach { name ->
            PillButton(
                text = name,
                icon = if (!other && room == name) AppIcons.Check else null,
                onClick = {
                    other = false
                    room = name
                },
            )
        }
        PillButton(
            text = "Another room",
            icon = if (other) AppIcons.Check else null,
            onClick = {
                if (!other) room = ""
                other = true
            },
        )
    }
    if (other) {
        StationField(
            value = room,
            onChange = { room = it.take(MAX_ROOM) },
            placeholder = "The room's name, like Den",
            keyboardType = KeyboardType.Text,
        )
    }
    Note("Other rooms' Back to cards name it: \"Paused in the living room\".")
    PillButton(
        text = "Pair",
        icon = AppIcons.Tv,
        onClick = { station.pair(address, room) },
        modifier = Modifier.padding(top = 4.dp).focusRequester(pair),
    )
    if (view.starting) Note("Asking the station for a code…")
    view.problem?.let { Problem(it) }
}

/** A code is showing: compare it, approve it, or see that it was refused or expired. */
@Composable
private fun ColumnScope.PairingPage(view: StationView, phase: StationPhase.Pairing, station: GuideStationController, zone: ZoneId) {
    val first = remember { FocusRequester() }
    LaunchedEffect(phase.state) { runCatching { first.requestFocus() } }
    when (phase.state) {
        PairState.PENDING, PairState.APPROVED -> {
            Text("Check that the station shows the same code", style = MaterialTheme.typography.titleSmall)
            BigCode(phase.code)
            Note("If the station's code is different, something else answered at that address: don't approve this TV.")
            Eyebrow("To approve this TV", Modifier.padding(top = 4.dp))
            Note(
                // As station/README.md: File Station makes folders, not empty files, and the image has no shell.
                "The first TV, on the NAS: in File Station, make a folder named ${phase.code} in the station's " +
                    "data/approve folder. Or run its approve command over SSH (the station's README).",
            )
            Note("Later TVs, on a TV that's paired: Settings › Plex Guide › Guide station, then this TV under Devices.")
            StatusLine(LocalAccent.current, StationText.waiting(phase.expiresAtMillis, zone))
            PillButton(
                text = "Cancel",
                onClick = station::cancelPairing,
                colors = pillButtonColors(container = Palette.Surface2),
                modifier = Modifier.focusRequester(first),
            )
        }
        PairState.REFUSED, PairState.EXPIRED -> {
            Text(
                if (phase.state == PairState.REFUSED) "The station didn't approve this TV" else "The code expired before it was approved",
                style = MaterialTheme.typography.titleSmall,
            )
            Note(
                if (phase.state == PairState.REFUSED) {
                    "Code ${phase.code} was turned down. Try again for a new code."
                } else {
                    "Codes last 5 minutes. Try again for a new one."
                },
            )
            view.problem?.let { Problem(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(text = "Try again", icon = AppIcons.Restart, onClick = { station.pair(view.address, view.room) }, modifier = Modifier.focusRequester(first))
                PillButton(text = "Cancel", onClick = station::cancelPairing, colors = pillButtonColors(container = Palette.Surface2))
            }
        }
    }
}

/** A confirm step on the paired page: Cancel is focused, and Back cancels. */
private sealed interface Confirm {
    data class Approve(val device: StationDevice) : Confirm
    data class Unpair(val device: StationDevice) : Confirm
    data object Leave : Confirm
}

/** Paired: how it's going, the station's Plex, the house's TVs, and Leave. */
@Composable
private fun ColumnScope.PairedPage(view: StationView, phase: StationPhase.Paired, station: GuideStationController, nowMillis: Long, zone: ZoneId) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val leave = remember { FocusRequester() }
    val pairAgain = remember { FocusRequester() }
    val unpaired = view.reach == StationReach.UNPAIRED
    LaunchedEffect(unpaired) { runCatching { (if (unpaired) pairAgain else leave).requestFocus() } }

    StatusLine(dotColour(view.reach), StationText.status(view, nowMillis))
    Note(listOf(view.room, view.address).filter { it.isNotEmpty() }.joinToString(" · "))
    when (view.reach) {
        StationReach.CONNECTED -> Unit
        StationReach.UNREACHABLE -> Note(
            "This TV shows its copy of the guide: the lineup and the next 48 hours. Channels can't be changed until the station is back.",
        )
        StationReach.UNPAIRED -> {
            Note("Another TV unpaired this one, so its guide is the last copy. Pair it again, or leave the station to make its own guide again.")
            PillButton(
                text = "Pair again",
                icon = AppIcons.Tv,
                onClick = { station.pair(view.address, view.room) },
                modifier = Modifier.focusRequester(pairAgain),
            )
            if (view.starting) Note("Asking the station for a code…")
        }
    }
    // A 1.1 station keeps no favorites (Phase 13): each TV keeps its own until it's updated.
    if (view.favoritesNeedUpdate) Problem(StationText.FAVORITES_NEED_UPDATE)

    if (view.reach != StationReach.UNPAIRED) {
        Eyebrow("The station's Plex", Modifier.padding(top = 8.dp))
        val info = phase.station
        when {
            info == null -> Note(if (view.reach == StationReach.CONNECTED) "Reading the station…" else "Not read yet.")
            info.plex.linked -> {
                val (line, library) = StationText.plex(info, nowMillis)
                Text(line, style = MaterialTheme.typography.titleSmall)
                Note(library)
            }
            phase.plexCode != null -> {
                Note("On your phone or computer, go to plex.tv/link and enter the code below to sign the station in.")
                BigCode(phase.plexCode)
            }
            else -> {
                Note("The station isn't signed in to Plex, so it has no library to make channels from.")
                PillButton(text = "Sign the station in to Plex", onClick = station::linkPlex, colors = pillButtonColors(container = Palette.Surface2))
            }
        }

        Eyebrow("Devices", Modifier.padding(top = 8.dp))
        val devices = phase.devices
        when {
            devices == null -> Note(if (view.reach == StationReach.CONNECTED) "Reading the TVs…" else "Not read yet.")
            devices.isEmpty() -> Note("No TVs yet.")
            else -> {
                Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { device ->
                        val self = device.id == phase.thisDevice
                        DeviceRow(device = device, self = self, nowMillis = nowMillis, zone = zone) {
                            confirm = when {
                                self -> Confirm.Leave
                                device.pending -> Confirm.Approve(device)
                                else -> Confirm.Unpair(device)
                            }
                        }
                    }
                }
                Note("OK on a TV waiting for approval approves it, once its code matches. OK on another TV unpairs it.")
            }
        }
    }
    view.problem?.let { Problem(it) }

    when (val current = confirm) {
        null -> PillButton(
            text = "Leave the station",
            onClick = { confirm = Confirm.Leave },
            colors = pillButtonColors(container = Palette.Surface2),
            modifier = Modifier.padding(top = 8.dp).focusRequester(leave),
        )
        else -> ConfirmStep(current, station) { confirm = null }
    }
    if (phase.leaving) Note("Leaving the station…")
}

@Composable
private fun ColumnScope.ConfirmStep(confirm: Confirm, station: GuideStationController, onDone: () -> Unit) {
    val cancel = remember { FocusRequester() }
    BackHandler(onBack = onDone)
    LaunchedEffect(confirm) { runCatching { cancel.requestFocus() } }
    val (title, note, action) = when (confirm) {
        is Confirm.Approve -> Triple(
            "Approve ${confirm.device.name}?",
            "Check that the TV in ${BackToText.inRoom(confirm.device.name)} shows " +
                (confirm.device.code?.let { "the code $it" } ?: "the same code as the station") + ". If it doesn't, cancel.",
            "Approve",
        )
        is Confirm.Unpair -> Triple(
            "Unpair ${confirm.device.name}?",
            "It keeps its copy of the guide, and can't reach the station until it pairs again.",
            "Unpair",
        )
        Confirm.Leave -> Triple(
            "Leave the guide station?",
            "This TV goes back to making its own guide, from its copy of the lineup. The station and the other TVs keep theirs.",
            "Leave",
        )
    }
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Note(note)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(
            text = action,
            onClick = {
                when (confirm) {
                    is Confirm.Approve -> station.approve(confirm.device)
                    is Confirm.Unpair -> station.unpair(confirm.device)
                    Confirm.Leave -> station.leave()
                }
                onDone()
            },
        )
        PillButton(text = "Cancel", onClick = onDone, colors = pillButtonColors(container = Palette.Surface2), modifier = Modifier.focusRequester(cancel))
    }
}

/** A TV on the station: its room, and what it's doing or a pending one's code. */
@Composable
private fun DeviceRow(device: StationDevice, self: Boolean, nowMillis: Long, zone: ZoneId, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (self) "${device.name} · this TV" else device.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    StationText.device(device, nowMillis, zone),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.pending) LocalAccent.current else Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A code to read off the screen: the pairing code, or the station's plex.tv/link code. */
@Composable
private fun BigCode(code: String) {
    Text(
        text = code,
        style = MaterialTheme.typography.displaySmall.copy(letterSpacing = 8.sp, fontWeight = FontWeight.Bold),
        color = LocalAccent.current,
    )
}

/** A 10 dp dot and a status in titleSmall: the colour always has its words. */
@Composable
private fun StatusLine(colour: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Dot(colour)
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Dot(colour: Color) {
    Box(Modifier.size(10.dp).background(colour, CircleShape))
}

private fun dotColour(reach: StationReach): Color = when (reach) {
    StationReach.CONNECTED -> Palette.Good
    StationReach.UNREACHABLE -> Palette.Caution
    StationReach.UNPAIRED -> Palette.Bad
}

/** A text field typed with the TV keyboard, which OK opens (as a kid's name is). */
@Composable
private fun StationField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = LocalAccent.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = Palette.Text),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = modifier
            .fillMaxWidth()
            .fieldLook(if (focused) accent else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .okOpensKeyboard { keyboard?.show() },
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.titleMedium, color = Palette.TextFaint)
                inner()
            }
        },
    )
}

/** Something the station turned down, or couldn't be asked, in its own words. */
@Composable
private fun Problem(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.tv.material3.Icon(AppIcons.Warning, contentDescription = null, tint = Palette.Caution, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
    }
}

private const val MAX_ADDRESS = 64
private const val MAX_ROOM = 24
