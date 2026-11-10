package com.phonoodlesramen.actioncenter.ui.player

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.player.BehindLive
import com.phonoodlesramen.actioncenter.player.OffAirReason
import com.phonoodlesramen.actioncenter.player.Scrub
import com.phonoodlesramen.actioncenter.player.Seeking
import com.phonoodlesramen.actioncenter.ui.guide.NoChannelsYet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The player's words and times, apart from Compose so they're unit-tested. */
internal object PlayerText {
    private val Clock = DateTimeFormatter.ofPattern("h:mm")
    private val ClockAmPm = DateTimeFormatter.ofPattern("h:mm a")
    private val AmPm = DateTimeFormatter.ofPattern("a")

    /** "9:30 PM". */
    fun clock(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone).format(ClockAmPm)

    /** "8:30 – 9:22 PM", or "11:30 PM – 1:02 AM" when the slot crosses noon or midnight. */
    fun timeRange(startMillis: Long, endMillis: Long, zone: ZoneId): String {
        val start = Instant.ofEpochMilli(startMillis).atZone(zone)
        val end = Instant.ofEpochMilli(endMillis).atZone(zone)
        val first = if (start.format(AmPm) == end.format(AmPm)) start.format(Clock) else start.format(ClockAmPm)
        return "$first – ${end.format(ClockAmPm)}"
    }

    /** "41m left", "1h 52m left", as the home's cards say it; rounded up, so the last seconds read "1m left". */
    fun left(slot: GuideSlot, nowMillis: Long): String = minutesLeft(slot.minutesLeftAt(nowMillis))

    /** [left] for a program watched behind live: what's left from the picture, not from the schedule. */
    fun left(behind: BehindLive, nowMillis: Long): String = minutesLeft(behind.minutesLeftAt(nowMillis))

    private fun minutesLeft(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"

    /** "12 min behind live", "1 hr 5 min behind live", or "Under a minute behind live" (G11). */
    fun behindLive(millis: Long): String {
        val minutes = millis.coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "Under a minute behind live"
            minutes < 60 -> "$minutes min behind live"
            else -> "${minutes / 60} hr ${minutes % 60} min behind live"
        }
    }

    /** The banner's eyebrow behind live: "Paused · 12 min behind live", "Started over · …", or just how far behind after a pause. */
    fun behindEyebrow(behind: BehindLive, nowMillis: Long): String {
        val how = when {
            behind.paused -> "Paused"
            behind.startedOver -> "Started over"
            else -> null
        }
        return listOfNotNull(how, behindLive(behind.behindAt(nowMillis))).joinToString(" · ")
    }

    /** "12:30" or "1:12:30": a place in a program's file, by the whole second (P1, P4). */
    fun position(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1_000
        val h = seconds / 3_600
        val m = seconds % 3_600 / 60
        val s = seconds % 60
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    /**
     * The banner's eyebrow while ⏪ or ⏩ is held (P1): the speed, then where letting go lands,
     * "Rewind ×4 · 14 min behind live", or "Fast forward ×8 · Live" once the marker's at live.
     */
    fun scrubEyebrow(scrub: Scrub, slot: GuideSlot, nowMillis: Long): String {
        val how = "${if (scrub.forward) "Fast forward" else "Rewind"} ×${scrub.speed}"
        val landing = if (scrub.targetMs >= Seeking.liveAt(slot, nowMillis)) "Live" else behindLive(nowMillis - slot.startMillis - scrub.targetMs)
        return "$how · $landing"
    }

    /** "4:59" or "1:49:00": the time left, by the second, rounded up so it reaches 0:00 as the slot starts. */
    fun countdown(millisLeft: Long): String {
        val seconds = (millisLeft.coerceAtLeast(0) + 999) / 1_000
        val h = seconds / 3_600
        val m = seconds % 3_600 / 60
        val s = seconds % 60
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    /** "S3 E1 · Thaw · TV-MA": what the banner and cards say under a title. */
    fun meta(slot: GuideSlot): String? = listOfNotNull(slot.episode, slot.contentRating).joinToString(" · ").ifBlank { null }

    /**
     * The off-air card's headline, in words anyone can follow (no files, codes or HTTP): the
     * slot's own reason when it's marked off air. [slot] is the program that should be on,
     * which says whether it's a movie or an episode.
     */
    fun headline(reason: OffAirReason, detail: String?, slot: GuideSlot? = null): String = when (reason) {
        OffAirReason.NO_CHANNELS -> "No channels yet"
        OffAirReason.NOTHING_ON -> "Nothing on right now"
        OffAirReason.OFF_AIR -> detail ?: "Off air"
        OffAirReason.UNAVAILABLE -> "This ${if (slot?.episode != null) "episode" else "movie"} isn't available right now"
        OffAirReason.CANT_PLAY -> "This one won't play on the Shield"
        OffAirReason.OFFLINE -> "Can't reach Plex"
        OffAirReason.NOT_LINKED -> "Plex isn't linked"
    }

    /**
     * The line under the headline: what to expect, or what to do. Offline and unavailable
     * say the player is trying again, as it does every 30 s. [waitingOn] says why there are
     * no channels (the guide's wording).
     */
    fun explanation(reason: OffAirReason, waitingOn: NoChannelsYet? = null): String = when (reason) {
        OffAirReason.NO_CHANNELS -> "${(waitingOn ?: NoChannelsYet.NOT_SYNCED).sentence}. Press Back to return."
        OffAirReason.NOTHING_ON -> "This channel has nothing scheduled here."
        OffAirReason.OFF_AIR, OffAirReason.CANT_PLAY -> "The channel carries on with the next program."
        OffAirReason.UNAVAILABLE -> "It may be on a drive that's off or restarting. Trying again…"
        OffAirReason.OFFLINE -> "Check that your Plex server is on. Trying again…"
        OffAirReason.NOT_LINKED -> "Sign in from Settings › Plex to watch your channels."
    }

    private fun two(value: Long) = value.toString().padStart(2, '0')
}
