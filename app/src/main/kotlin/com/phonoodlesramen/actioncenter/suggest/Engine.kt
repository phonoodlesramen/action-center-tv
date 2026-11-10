package com.phonoodlesramen.actioncenter.suggest

import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One title the engine picked for a card. [reason] is the human-readable line [REQ];
 * [evidence] is what Why this pick? adds after it. [ratingKey] is what Play hands off: a
 * movie, or an episode (a series pick plays that episode). [episodesLeft] is set on
 * Tonight's Pick's "finish it" pick only (S5): the mode decides whether it leads (M3).
 */
data class Picked(val ratingKey: String, val reason: String, val evidence: List<String> = emptyList(), val episodesLeft: Int? = null)

/**
 * How much you'd like a movie or show, from your signals: zero is no opinion, positive
 * leans your way. `Taste` builds the real one; tests and lenses that don't care use [None].
 */
fun interface Affinity {
    fun of(title: ItemEntity): Double

    companion object {
        val None = Affinity { 0.0 }
    }
}

/**
 * Everything one run of the engine works from, read once. Times are millis; [zone] turns
 * them into days and hours. [reason] gives a title's taste reason ("Because you liked
 * Heat") when it has one: the lens card's Action & adventure line and Quick Watch's short
 * titles use it.
 */
class RunInput(
    val library: Library,
    val signals: List<Signal>,
    val hides: Hides,
    val affinity: Affinity = Affinity.None,
    val now: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    val reason: (ItemEntity) -> String? = { null },
) {
    val time: LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
    val today: LocalDate = time.toLocalDate()

    /** Epoch seconds (the cache's unit) as a local date. */
    fun dayOf(epochSeconds: Long): LocalDate = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()
}

/**
 * Ties break the same way all day, and differently tomorrow: a seed of the date and the
 * title, mixed (MurmurHash3's finish). A plain hash of the two would move every title by
 * the same amount from one day to the next, so ties would keep their order.
 */
object Seeds {
    fun tieBreak(ratingKey: String, day: LocalDate): Int {
        var h = ratingKey.hashCode() * 31 + day.hashCode()
        h = (h xor (h ushr 16)) * 0x85ebca6b.toInt()
        h = (h xor (h ushr 13)) * 0xc2b2ae35.toInt()
        return h xor (h ushr 16)
    }
}
