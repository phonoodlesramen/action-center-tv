package com.phonoodlesramen.actioncenter.guide.station

import com.phonoodlesramen.actioncenter.guide.ChannelEntity
import com.phonoodlesramen.actioncenter.guide.ChannelLooks
import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.GuideDatabase
import com.phonoodlesramen.actioncenter.guide.GuideLibrary
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.PoolRatings
import com.phonoodlesramen.actioncenter.guide.SlotEntity
import com.phonoodlesramen.actioncenter.guide.toGuideSlot
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.plex.cache.SyncStateEntity
import java.time.Instant
import java.time.ZoneId

/**
 * The guide station's read of `guide.db` (Phase 8): every channel, off ones too, with its
 * slots as a TV shows them, for `GET /v1/guide`. It sits beside the engine (JVM only, so the
 * TVs never carry it) because it needs what the engine keeps to itself: which programs still
 * have a file, and what each channel can play.
 *
 * - **Off air:** a program whose title left the cache, or lost its file, is off air, as on
 *   the TVs' card.
 * - **Ratings (R2, K8):** every title a channel's rule can play and each program still to
 *   finish, as `GuideEngine` works them out, so each TV's kid filter reads the same thing.
 */
class StationLineupReader(guide: GuideDatabase, plex: PlexDatabase) {
    private val dao = guide.dao()
    private val library = GuideLibrary(plex.guideLibrary())
    private val looks = ChannelLooks(plex)

    /** The lineup in number order, and when its schedule was last extended (null before it ever was). */
    data class Lineup(val channels: List<StationChannel>, val extendedAtMillis: Long?)

    /** Every channel, with its slots that end after [from]; ratings as at [now] in [zone]. */
    suspend fun read(from: Long, now: Long, zone: ZoneId): Lineup {
        val channels = dao.channels()
        val slots = dao.slotsFrom(from).groupBy { it.channelId }
        val programs = slots.values.asSequence().flatten().filter { it.kind == SlotEntity.PROGRAM }.mapNotNull { it.itemKey }.toSet()
        val playable = library.playable(programs)
        val sync = library.syncState()
        val year = Instant.ofEpochMilli(now).atZone(zone).year
        val lineup = channels.map { channel ->
            val own = slots[channel.id].orEmpty()
            val rated = ratingsOf(channel, own, now, year, sync)
            StationChannel(
                id = channel.id,
                number = channel.number,
                name = channel.name,
                // A one-show channel's show poster, which every TV draws over its icon.
                logo = looks.of(channel.name, channel.rule).logo,
                enabled = channel.enabled,
                alignmentMinutes = channel.alignmentMinutes,
                filler = channel.filler,
                // A rule that won't read is shown as the default, as the editor does; saving over it fixes it.
                rule = runCatching { ChannelRule.fromJson(channel.rule) }.getOrDefault(ChannelRule()),
                contentRatings = rated.ratings.sorted(),
                unrated = rated.unrated,
                slots = own.map { StationSlot.of(it.toGuideSlot(playable)) },
            )
        }
        return Lineup(lineup, channels.mapNotNull { it.extendedAt }.maxOrNull())
    }

    /** The slot on [channelId] that starts at [startMillis], as a TV shows it; null when the schedule has none. */
    suspend fun slot(channelId: String, startMillis: Long): GuideSlot? {
        val slot = dao.slotAt(channelId, startMillis)?.takeIf { it.startMillis == startMillis } ?: return null
        val key = slot.itemKey
        val playable = if (slot.kind == SlotEntity.PROGRAM && key != null) library.playable(listOf(key)) else emptyMap()
        return slot.toGuideSlot(playable)
    }

    /** As `GuideEngine.ratingsOf`: the rule's titles, and the programs still to finish. */
    private suspend fun ratingsOf(channel: ChannelEntity, slots: List<SlotEntity>, now: Long, year: Int, sync: SyncStateEntity?): PoolRatings {
        val pool = library.channelRatings(channel.rule, year, sync)
        if (pool.nothing) return pool
        val ahead = slots.filter { it.kind == SlotEntity.PROGRAM && it.endMillis > now }
        return PoolRatings(
            ratings = pool.ratings + ahead.mapNotNull { slot -> slot.contentRating?.takeIf { it.isNotBlank() } },
            unrated = pool.unrated || ahead.any { it.contentRating.isNullOrBlank() },
        )
    }
}
