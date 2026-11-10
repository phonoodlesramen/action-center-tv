package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.ChannelLooks
import com.phonoodlesramen.actioncenter.guide.EditableChannel
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideDatabase
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.guide.GuideEngine
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.GuideSource
import com.phonoodlesramen.actioncenter.guide.RuleChoices
import com.phonoodlesramen.actioncenter.guide.SlotEntity
import com.phonoodlesramen.actioncenter.guide.station.ChannelPatch
import com.phonoodlesramen.actioncenter.guide.station.Changed
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.player.SpotStore
import com.phonoodlesramen.actioncenter.safety.summary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/**
 * The guide while this TV is paired (Phase 8): the station's lineup and its 48 hours, from
 * this TV's copy in `guide.db` ([GuideStation.refresh]), so the card, the Live row, the full
 * guide and the player read it as they read this TV's own, offline too (N5). What `guide.db`
 * can't hold comes from [GuideCopy]: each channel's ratings for kids ([KidGuide][com.phonoodlesramen.actioncenter.guide.KidGuide]),
 * its logo and the programs off air. Nothing is scheduled here.
 */
class StationGuideSource(
    private val guideDb: GuideDatabase,
    private val copy: Flow<GuideCopy?>,
    /** Each channel's glyph, and a one-show channel's poster when the station sent none, from this TV's Plex cache. */
    private val looks: ChannelLooks = ChannelLooks(null),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) : GuideSource {
    private val dao = guideDb.dao()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val card: Flow<GuideCardState> = combine(
        guideDb.invalidationTracker.createFlow(*GuideDatabase.TABLES, emitInitialState = true),
        copy,
    ) { _, copy -> Read(copy) }
        .conflate()
        .mapLatest { read(it.copy) }
        .filterNotNull()
        .distinctUntilChanged()
        .flowOn(io)
        .catch { log("station guide: not read: ${it.summary()}") }

    /** One read to make: a new value each time, so the same copy is read again after `guide.db` changes. */
    private class Read(val copy: GuideCopy?)

    private suspend fun read(copy: GuideCopy?): GuideCardState? = try {
        when {
            // Paired, before the first copy has come.
            copy == null -> GuideCardState.Building
            else -> lineup(copy)
        }
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        log("station guide: not read: ${e.summary()}")
        null
    }

    private suspend fun lineup(copy: GuideCopy): GuideCardState {
        val now = clock()
        val all = dao.channels()
        // No channels: the station's library hasn't synced, or nothing in it fills one.
        if (all.isEmpty()) return if (copy.synced) GuideCardState.NothingToFill else GuideCardState.NotSetUp
        val on = all.filter { it.enabled }
        if (on.isEmpty()) return GuideCardState.NoneOn
        val ids = on.mapTo(HashSet()) { it.id }
        val slots = dao.slotsFrom(now - GuideEngine.SHOW_PAST_MILLIS).filter { it.channelId in ids }.groupBy { it.channelId }
        val lineup = on.mapNotNull { channel ->
            val own = slots[channel.id]
            if (own.isNullOrEmpty()) return@mapNotNull null
            val extras = copy.channels[channel.id] ?: ChannelExtras()
            val look = looks.of(channel.name, channel.rule)
            GuideChannel(
                id = channel.id,
                number = channel.number,
                name = channel.name,
                logo = extras.logo ?: look.logo,
                glyph = look.glyph,
                slots = own.map { it.toGuideSlot(extras.offAir[it.startMillis]) },
                contentRatings = extras.contentRatings.toSet(),
                unrated = extras.unrated,
            )
        }
        return if (lineup.isEmpty()) GuideCardState.NothingToFill else GuideCardState.Ready(lineup, copy.updatedAtMillis)
    }
}

/** A slot from the copy as the home shows it, with the station's word on whether it plays. */
internal fun SlotEntity.toGuideSlot(offAir: String?) = GuideSlot(
    startMillis = startMillis,
    endMillis = endMillis,
    title = title,
    episode = episode,
    itemKey = itemKey,
    showKey = showKey,
    art = art,
    contentRating = contentRating,
    filler = kind != SlotEntity.PROGRAM,
    offAir = if (kind == SlotEntity.PROGRAM) offAir else null,
    summary = summary,
)

/**
 * [GuideEditor] over the station's API, one call for one (N4): Settings › Plex Guide and
 * the phone editor change the station's channels, and every TV follows. [lineup] is this
 * TV's copy (`guide.db`), which each edit refreshes at once. A refusal is the station's own
 * sentence; not reaching it is "Can't reach the guide station." There's no offline queue.
 */
class StationGuideEditor(private val station: GuideStation, override val lineup: Flow<List<EditableChannel>>) : GuideEditor {
    override suspend fun choices(): RuleChoices = station.edit { choices() }

    override suspend fun rename(id: String, name: String) = change { patch(id, ChannelPatch(name = name)) }

    override suspend fun renumber(id: String, number: Int) = change { patch(id, ChannelPatch(number = number)) }

    override suspend fun move(id: String, index: Int) = change { patch(id, ChannelPatch(index = index)) }

    override suspend fun setEnabled(id: String, enabled: Boolean) = change { patch(id, ChannelPatch(enabled = enabled)) }

    override suspend fun create(draft: ChannelDraft): String = station.edit { create(draft) }.also { station.changed(it.version) }.id

    override suspend fun update(id: String, draft: ChannelDraft) = change { update(id, draft) }

    override suspend fun duplicate(id: String): String = station.edit { duplicate(id) }.also { station.changed(it.version) }.id

    override suspend fun delete(id: String) = change { delete(id) }

    override suspend fun preview(draft: ChannelDraft): List<GuideSlot> = station.edit { preview(draft) }.slots.map { it.toGuideSlot() }

    override suspend fun remakeStarterLineup() = change { remake() }

    private suspend fun change(call: suspend StationClient.() -> Changed) {
        station.changed(station.edit(call).version)
    }
}

/**
 * The house's saved spot (N6) on the station: the latest pause on any TV, kept 4 hours
 * there. It's cached, so the Back-to card shows offline; a change the station hasn't heard
 * is sent first at the next contact. Another TV's spot names its room.
 */
class StationSpots(private val station: GuideStation) : SpotStore {
    override val spot: Flow<SavedSpot?> = station.houseSpot

    override suspend fun save(spot: SavedSpot) = station.saveSpot(spot)

    override suspend fun clear(only: SavedSpot?) = station.forgetSpot(only)

    override suspend fun channelPlays(channelId: String) = station.channelPlayed(channelId)
}

/**
 * The seams the screens read through ([GuideSource], [GuideEditor], [SpotStore]), each the
 * station's while this TV is [paired] and its own otherwise: an unpaired TV behaves exactly
 * as before Phase 8.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideSwitch(private val paired: Flow<Boolean>) {
    fun source(local: GuideSource, station: GuideSource): GuideSource = object : GuideSource {
        override val card: Flow<GuideCardState> = paired.distinctUntilChanged().flatMapLatest { if (it) station.card else local.card }
    }

    fun editor(local: GuideEditor, station: GuideEditor): GuideEditor = object : GuideEditor {
        private suspend fun current() = if (paired.first()) station else local

        override val lineup: Flow<List<EditableChannel>> = paired.distinctUntilChanged().flatMapLatest { if (it) station.lineup else local.lineup }

        override suspend fun choices() = current().choices()
        override suspend fun rename(id: String, name: String) = current().rename(id, name)
        override suspend fun renumber(id: String, number: Int) = current().renumber(id, number)
        override suspend fun move(id: String, index: Int) = current().move(id, index)
        override suspend fun setEnabled(id: String, enabled: Boolean) = current().setEnabled(id, enabled)
        override suspend fun create(draft: ChannelDraft) = current().create(draft)
        override suspend fun update(id: String, draft: ChannelDraft) = current().update(id, draft)
        override suspend fun duplicate(id: String) = current().duplicate(id)
        override suspend fun delete(id: String) = current().delete(id)
        override suspend fun preview(draft: ChannelDraft) = current().preview(draft)
        override suspend fun remakeStarterLineup() = current().remakeStarterLineup()
    }

    fun spots(local: SpotStore, station: SpotStore): SpotStore = object : SpotStore {
        private suspend fun current() = if (paired.first()) station else local

        override val spot: Flow<SavedSpot?> = paired.distinctUntilChanged().flatMapLatest { if (it) station.spot else local.spot }

        override suspend fun save(spot: SavedSpot) = current().save(spot)
        override suspend fun clear(only: SavedSpot?) = current().clear(only)
        override suspend fun channelPlays(channelId: String) = current().channelPlays(channelId)
    }
}
