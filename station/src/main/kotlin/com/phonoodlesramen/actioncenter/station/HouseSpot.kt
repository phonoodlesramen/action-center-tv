package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.station.SpotBody
import com.phonoodlesramen.actioncenter.guide.station.SpotKey
import com.phonoodlesramen.actioncenter.guide.station.StationSpot
import java.nio.file.Path
import kotlinx.serialization.Serializable

/**
 * The house's saved spot (P3, N6): one for every TV, the latest `PUT` from any of them, kept
 * in the volume (`spot.json`) so a restart doesn't lose it.
 *
 * It holds ([current]) for [KEEP_MILLIS] from when it was saved, while its slot is still in
 * the schedule with that item, not filler and not off air: the TVs' `SpotRules` and
 * `GuideTuning.canStartOver`. One that no longer holds reads as none, and an old one is
 * forgotten. Every change moves the house's [Versions] on, so the other TVs hear of it.
 */
class HouseSpot(
    private val file: Path,
    private val versions: Versions,
    /** The schedule's slot on a channel at a start, as the TVs show it ([GuideService.slot]). */
    private val slotAt: suspend (channelId: String, startMillis: Long) -> GuideSlot?,
    private val clock: () -> Long,
) {
    /** The spot, and when the station got it: a TV whose clock is wrong can't keep one for ever. */
    @Serializable
    private data class Saved(val spot: StationSpot, val receivedAtMillis: Long)

    private val lock = Any()
    private var saved: Saved? = try {
        SecretFiles.readJson(file, Saved.serializer())
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The spot while it holds; null when there's none, or it no longer holds. */
    suspend fun current(): StationSpot? {
        val now = clock()
        val held = synchronized(lock) {
            val spot = saved ?: return null
            if (!fresh(spot, now)) {
                // Too old: forgotten quietly. The TVs apply the same 4 hours, so nothing to tell them.
                saved = null
                SecretFiles.delete(file)
                return null
            }
            spot.spot
        }
        val slot = slotAt(held.channelId, held.slotStartMillis) ?: return null
        return held.takeIf { slot.itemKey == held.itemKey && canStartOver(slot) }
    }

    /** Saves [body] as the house's spot, from [device]. Returns the new version. */
    fun put(body: SpotBody, device: PairedDevice): Long {
        if (body.channelId.isBlank() || body.channelId.length > MAX_KEY || body.itemKey.isBlank() || body.itemKey.length > MAX_KEY) {
            throw ApiException(400, "That spot names no channel or program.")
        }
        if (body.positionMs < 0 || body.slotStartMillis <= 0 || body.savedAtMillis <= 0) throw ApiException(400, "That spot's times can't be right.")
        val spot = StationSpot(
            channelId = body.channelId, slotStartMillis = body.slotStartMillis, itemKey = body.itemKey, positionMs = body.positionMs,
            savedAtMillis = body.savedAtMillis, device = device.id, room = device.name,
        )
        synchronized(lock) {
            saved = Saved(spot, clock())
            SecretFiles.writeJson(file, Saved.serializer(), saved!!)
        }
        return versions.bump()
    }

    /** Forgets the spot; with [only], only while it's still that one. Returns the version. */
    fun forget(only: SpotKey?): Long {
        val forgot = synchronized(lock) {
            val spot = saved?.spot ?: return versions.current
            if (only != null && (only.channelId != spot.channelId || only.slotStartMillis != spot.slotStartMillis || only.itemKey != spot.itemKey)) {
                return versions.current
            }
            saved = null
            SecretFiles.delete(file)
            true
        }
        return if (forgot) versions.bump() else versions.current
    }

    /**
     * [channelId] played on [device]: a spot [device] saved on another channel is forgotten,
     * as on a TV by itself (`SpotStore.channelPlays`). Another TV's spot stays. Returns the version.
     */
    fun played(channelId: String, device: PairedDevice): Long {
        synchronized(lock) {
            val spot = saved?.spot ?: return versions.current
            if (spot.device != device.id || spot.channelId == channelId) return versions.current
            saved = null
            SecretFiles.delete(file)
        }
        return versions.bump()
    }

    private fun fresh(saved: Saved, now: Long): Boolean =
        // A clock that went back keeps it (as on the TVs), but never past 4 hours of the station's own.
        now - saved.spot.savedAtMillis < KEEP_MILLIS && now - saved.receivedAtMillis < KEEP_MILLIS

    companion object {
        /** Four hours, as the TVs' `SpotRules.KEEP_MILLIS`. */
        const val KEEP_MILLIS = 4 * 60 * 60_000L
        private const val MAX_KEY = 200

        /** The TVs' `GuideTuning.canStartOver`: a program with a file, not filler and not off air. */
        fun canStartOver(slot: GuideSlot): Boolean = !slot.filler && slot.itemKey != null && slot.offAir == null
    }
}
