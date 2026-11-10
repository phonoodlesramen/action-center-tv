package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.GuideDatabase
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.RuleChoices
import com.phonoodlesramen.actioncenter.guide.station.ChannelPatch
import com.phonoodlesramen.actioncenter.guide.station.Changed
import com.phonoodlesramen.actioncenter.guide.station.Created
import com.phonoodlesramen.actioncenter.guide.station.Preview
import com.phonoodlesramen.actioncenter.guide.station.StationApi
import com.phonoodlesramen.actioncenter.guide.station.StationGuide
import com.phonoodlesramen.actioncenter.guide.station.StationLineupReader
import com.phonoodlesramen.actioncenter.guide.station.StationSlot
import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The house's guide: `guide.db` read whole after every change the station makes (an
 * extension, a sync, an edit) and every few minutes, with the [Versions] number moved on
 * whenever what it reads differs. `GET /v1/guide` serves the latest read, [StationApi.BEHIND_MILLIS]
 * back, tagged with the version it was read at.
 *
 * Edits go through [editor] (`RoomGuideEditor`), so they take effect after the program on
 * now (G16) as on the TVs, and each answers with the version that includes it.
 */
class GuideService(
    guide: GuideDatabase,
    plex: PlexDatabase,
    private val editor: GuideEditor,
    private val versions: Versions,
    private val clock: () -> Long,
    private val zone: ZoneId,
) {
    private val reader = StationLineupReader(guide, plex)
    private val refreshing = Mutex()

    /** What was read last, and when. Swapped with the version together, under [swap]. */
    private class Read(val lineup: StationLineupReader.Lineup, val atMillis: Long)

    private val swap = Any()
    private var last: Read? = null

    /** Reads the lineup again; a change moves the version on. Returns the version. */
    suspend fun refresh(): Long = refreshing.withLock {
        val now = clock()
        val fresh = Read(reader.read(Long.MIN_VALUE, now, zone), now)
        synchronized(swap) {
            val before = last
            last = fresh
            if (before != null && before.lineup != fresh.lineup) versions.bump() else versions.current
        }
    }

    /** The guide as `GET /v1/guide` answers it, and its version; null while it hasn't been read yet. */
    fun guide(): StationGuide? {
        val (read, version) = synchronized(swap) { (last ?: return null) to versions.current }
        val from = clock() - StationApi.BEHIND_MILLIS
        return StationGuide(
            version = version,
            updatedAtMillis = read.lineup.extendedAtMillis ?: read.atMillis,
            timeZone = zone.id,
            channels = read.lineup.channels.map { channel -> channel.copy(slots = channel.slots.filter { it.endMillis > from }) },
        )
    }

    /** The slot on [channelId] starting at [startMillis] as the TVs show it, from `guide.db`: the spot's check. */
    suspend fun slot(channelId: String, startMillis: Long): GuideSlot? = reader.slot(channelId, startMillis)

    suspend fun choices(): RuleChoices = editor.choices()

    suspend fun create(draft: ChannelDraft): Created {
        val id = editor.create(draft)
        return Created(id, refresh())
    }

    suspend fun update(id: String, draft: ChannelDraft): Changed = edit { editor.update(id, draft) }

    /** Rename, renumber, on/off and move, in that order; absent fields stay. */
    suspend fun patch(id: String, patch: ChannelPatch): Changed = edit {
        patch.name?.let { editor.rename(id, it) }
        patch.number?.let { editor.renumber(id, it) }
        patch.enabled?.let { editor.setEnabled(id, it) }
        patch.index?.let { editor.move(id, it) }
    }

    suspend fun duplicate(id: String): Created {
        val copy = editor.duplicate(id)
        return Created(copy, refresh())
    }

    suspend fun delete(id: String): Changed = edit { editor.delete(id) }

    /** The next 24 hours [draft] would play (the station schedules 48, but a preview shows a day). */
    suspend fun preview(draft: ChannelDraft): Preview {
        val until = clock() + PREVIEW_MILLIS
        return Preview(editor.preview(draft).filter { it.startMillis < until }.map(StationSlot::of))
    }

    suspend fun remake(): Changed = edit { editor.remakeStarterLineup() }

    /** Runs [block], then reads the lineup again, even when it failed part way (a PATCH whose rename worked). */
    private suspend fun edit(block: suspend () -> Unit): Changed {
        try {
            block()
        } finally {
            refresh()
        }
        return Changed(versions.current)
    }

    private companion object {
        const val PREVIEW_MILLIS = 24 * 60 * 60_000L
    }
}
