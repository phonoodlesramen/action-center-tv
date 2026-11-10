package com.phonoodlesramen.actioncenter.ui.settings

import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationDevice
import com.phonoodlesramen.actioncenter.guide.station.StationInfo
import com.phonoodlesramen.actioncenter.guide.station.StationLibrary
import com.phonoodlesramen.actioncenter.guide.station.StationPlex
import com.phonoodlesramen.actioncenter.station.GuideStationController
import com.phonoodlesramen.actioncenter.station.StationPhase
import com.phonoodlesramen.actioncenter.station.StationReach
import com.phonoodlesramen.actioncenter.station.StationView
import kotlinx.coroutines.flow.MutableStateFlow

/** Settings › Plex Guide › Guide station's states for tests and screenshots: the living room's Shield, the kitchen's TV, and one waiting in the bedroom. */
internal object SampleStation {
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    fun unpaired() = StationView()

    fun pairing(now: Long) = StationView(
        address = "192.168.1.20",
        room = "Living room",
        phase = StationPhase.Pairing("7K3QPA", expiresAtMillis = now + 4 * MINUTE + 30_000),
    )

    fun devices(now: Long) = listOf(
        StationDevice("6f", "Living room", "SHIELD Android TV", pairedAtMillis = now - 2 * DAY, lastSeenMillis = now),
        StationDevice("70", "Kitchen", "Google TV", pairedAtMillis = now - 2 * DAY, lastSeenMillis = now - 5 * MINUTE),
        StationDevice("71", "Bedroom", "Chromecast with Google TV", pending = true, code = "H4TW2C"),
    )

    fun paired(now: Long) = StationView(
        address = "192.168.1.20",
        room = "Living room",
        paired = true,
        reach = StationReach.CONNECTED,
        checkedAtMillis = now - 3 * MINUTE,
        phase = StationPhase.Paired(
            station = StationInfo(StationApi.VERSION, StationPlex(linked = true, serverName = "Den PC"), StationLibrary(lastSyncMillis = now - 12 * MINUTE), "America/Los_Angeles"),
            devices = devices(now),
            thisDevice = "6f",
        ),
    )

    fun unreachable(now: Long) = paired(now).copy(
        reach = StationReach.UNREACHABLE,
        checkedAtMillis = now - 2 * 60 * MINUTE,
        phase = StationPhase.Paired(thisDevice = "6f"),
    )
}

/** A [GuideStationController] that shows [view] and writes down what it was asked. */
internal class RecordingStation(view: StationView) : GuideStationController {
    override val view = MutableStateFlow(view)
    val calls = mutableListOf<String>()

    override fun pair(address: String, room: String) {
        calls += "pair $address $room"
    }

    override fun cancelPairing() {
        calls += "cancel"
    }

    override fun approve(device: StationDevice) {
        calls += "approve ${device.id}"
    }

    override fun unpair(device: StationDevice) {
        calls += "unpair ${device.id}"
    }

    override fun leave() {
        calls += "leave"
    }

    override fun linkPlex() {
        calls += "link plex"
    }
}
