package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.station.StationDevice
import java.nio.file.Path
import java.security.cert.X509Certificate
import kotlinx.serialization.Serializable

/** A paired TV: its room ([name]), and the serial of the certificate it was issued. */
@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val model: String,
    val serial: String,
    val pairedAtMillis: Long,
    val lastSeenMillis: Long? = null,
) {
    fun toApi() = StationDevice(id = id, name = name, model = model, pairedAtMillis = pairedAtMillis, lastSeenMillis = lastSeenMillis)
}

/** Who a client certificate the TLS layer accepted belongs to. */
sealed interface Caller {
    data class Paired(val device: PairedDevice) : Caller

    /** Revoked, or not a TV this station knows: `403 {"error":"unpaired"}`. */
    data object Unpaired : Caller
}

/**
 * The paired TVs and the serials of the ones unpaired (`devices.json`, mode 600). A TV is
 * known by its certificate's serial; an unpaired TV's serial stays revoked for good. When a
 * TV was last seen is kept in memory and written at most every few minutes ([flush]).
 */
class DeviceStore(private val file: Path) {
    @Serializable
    private data class Saved(val devices: List<PairedDevice> = emptyList(), val revoked: List<String> = emptyList())

    private val lock = Any()
    private var saved: Saved = SecretFiles.readJson(file, Saved.serializer()) ?: Saved()
    private val seen = HashMap<String, Long>()
    private var dirty = false

    fun all(): List<PairedDevice> = synchronized(lock) { saved.devices.map { it.copy(lastSeenMillis = seen[it.id] ?: it.lastSeenMillis) } }

    fun byId(id: String): PairedDevice? = all().firstOrNull { it.id == id }

    fun add(device: PairedDevice) {
        synchronized(lock) {
            saved = saved.copy(devices = saved.devices.filterNot { it.id == device.id } + device)
            write()
        }
    }

    /** [certificate]'s TV, or [Caller.Unpaired] once it's revoked or for a serial this station never issued to a paired TV. */
    fun identify(certificate: X509Certificate): Caller {
        synchronized(lock) {
            val serial = certificate.serialHex
            if (serial in saved.revoked) return Caller.Unpaired
            val device = saved.devices.firstOrNull { it.serial == serial } ?: return Caller.Unpaired
            return Caller.Paired(device.copy(lastSeenMillis = seen[device.id] ?: device.lastSeenMillis))
        }
    }

    fun isRevoked(serial: String): Boolean = synchronized(lock) { serial in saved.revoked }

    /** Unpairs [id]: its serial is revoked. Null when no such TV is paired. */
    fun revoke(id: String): PairedDevice? {
        synchronized(lock) {
            val device = saved.devices.firstOrNull { it.id == id } ?: return null
            saved = Saved(devices = saved.devices - device, revoked = (saved.revoked + device.serial).distinct())
            seen.remove(id)
            write()
            return device
        }
    }

    fun seen(id: String, nowMillis: Long) {
        synchronized(lock) {
            val written = saved.devices.firstOrNull { it.id == id }?.lastSeenMillis
            seen[id] = nowMillis
            if (written == null || nowMillis - written >= SEEN_WRITE_MILLIS) dirty = true
        }
    }

    /** Writes when each TV was last seen, if that moved on enough to be worth a write. */
    fun flush() {
        synchronized(lock) { if (dirty) write() }
    }

    /** Under [lock]: the file, with when each TV was last seen. */
    private fun write() {
        saved = saved.copy(devices = saved.devices.map { it.copy(lastSeenMillis = seen[it.id] ?: it.lastSeenMillis) })
        dirty = false
        SecretFiles.writeJson(file, Saved.serializer(), saved)
    }

    private companion object {
        const val SEEN_WRITE_MILLIS = 5 * 60_000L
    }
}
