package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.plex.cache.PlexDatabase
import com.phonoodlesramen.actioncenter.safety.summary
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * [GuideEditor] over `guide.db` and the Plex cache (2.7): Settings › Plex Guide on the TV
 * and the phone editor both change channels through it.
 *
 * - **One change at a time:** each runs inside [GuideEngine.edit], so it never meets an
 *   extension halfway.
 * - **What's scheduled stays (G16):** only [update] rewrites a schedule, and only that
 *   channel's, when its rule, start times or filler change ([GuideEngine.Editing.rewrite]).
 * - **The preview is what plays:** a new or edited channel's seed comes from its rule, so
 *   it plays its titles in the order [preview] showed.
 * - **Never a crash:** a change that can't be made throws [EditRejected] with a sentence to
 *   show; anything else that goes wrong is logged (never a title) and becomes one too.
 */
class RoomGuideEditor(
    private val guide: GuideDatabase,
    plex: PlexDatabase,
    private val engine: GuideEngine,
    /** What's on now in [lineup]; the engine's own clock places programs. */
    private val clock: () -> Long = engine.clock,
    /** The TVs log it under [GuideEngine.LOG_TAG], as the engine. Channel numbers, never a title. */
    private val log: (String) -> Unit,
) : GuideEditor {
    private val dao = guide.dao()
    private val library = GuideLibrary(plex.guideLibrary())
    private val looks = ChannelLooks(plex)

    /** Re-read as `guide.db` changes, and each minute, so what's on now moves on. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val lineup: Flow<List<EditableChannel>> = combine(
        guide.invalidationTracker.createFlow(*GuideDatabase.TABLES, emitInitialState = true),
        flow {
            while (true) {
                emit(Unit)
                delay(MINUTE)
            }
        },
    ) { _, _ -> }
        .conflate()
        .mapLatest { read() }
        .filterNotNull()
        .distinctUntilChanged()
        .flowOn(engine.io)
        .catch { log("guide editor: lineup not read: ${it.summary()}") }

    override suspend fun choices(): RuleChoices = attempt("read your library") { withContext(engine.io) { library.choices() } }

    override suspend fun rename(id: String, name: String) = change("rename the channel") {
        val channel = find(id)
        val named = checkName(name)
        if (named != channel.name) dao.updateChannel(channel.copy(name = named))
    }

    override suspend fun renumber(id: String, number: Int) = change("renumber the channel") {
        val channel = find(id)
        if (number !in 1..MAX_NUMBER) throw EditRejected("Channel numbers go from 1 to $MAX_NUMBER.")
        val taken = dao.channels().firstOrNull { it.number == number && it.id != id }
        if (taken != null) throw EditRejected("Channel $number is taken by ${taken.name}.")
        if (number != channel.number) dao.updateChannel(channel.copy(number = number))
    }

    override suspend fun move(id: String, index: Int) = change("move the channel") {
        val channels = dao.channels().toMutableList()
        val channel = channels.firstOrNull { it.id == id } ?: throw gone()
        channels.remove(channel)
        channels.add(index.coerceIn(0, channels.size), channel)
        val numbers = numbersAfterMove(channels.map { it.number }, channels.indexOf(channel))
        dao.updateChannels(channels.zip(numbers).mapNotNull { (it, number) -> if (it.number == number) null else it.copy(number = number) })
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = change(if (enabled) "turn the channel on" else "turn the channel off") {
        val channel = find(id)
        if (channel.enabled != enabled) dao.updateChannel(channel.copy(enabled = enabled))
    }

    override suspend fun create(draft: ChannelDraft): String = change("make the channel") {
        val checked = checked(draft)
        val channels = dao.channels()
        val channel = ChannelEntity(
            id = newId(channels),
            number = nextNumber(channels),
            name = checked.name,
            rule = checked.rule.toJson(),
            seed = seedFor(checked.rule),
            alignmentMinutes = checked.alignmentMinutes,
            cursor = ChannelCursor().toJson(),
            filler = checked.filler,
            icon = checked.icon,
        )
        add(channel)
        log("guide editor: channel ${channel.number} made")
        channel.id
    }

    override suspend fun update(id: String, draft: ChannelDraft) = change("save the channel") {
        val channel = find(id)
        val checked = checked(draft)
        val sameSchedule = channel.ruleOrNull() == checked.rule &&
            channel.alignmentMinutes == checked.alignmentMinutes &&
            channel.filler == checked.filler
        if (sameSchedule) {
            // A new name or icon needs no new schedule.
            if (checked.name != channel.name || checked.icon != channel.icon) dao.updateChannel(channel.copy(name = checked.name, icon = checked.icon))
        } else {
            rewrite(
                channel.copy(
                    name = checked.name,
                    rule = checked.rule.toJson(),
                    seed = seedFor(checked.rule),
                    alignmentMinutes = checked.alignmentMinutes,
                    filler = checked.filler,
                    icon = checked.icon,
                ),
            )
        }
    }

    override suspend fun duplicate(id: String): String = change("copy the channel") {
        val channels = dao.channels()
        val source = channels.firstOrNull { it.id == id } ?: throw gone()
        val copyId = newId(channels)
        val copy = source.copy(
            id = copyId,
            number = nextNumber(channels),
            name = copyName(source.name, channels),
            seed = GuideSeeds.seedFor(copyId),
            cursor = ChannelCursor().toJson(),
            scheduledUntil = null,
            extendedAt = null,
        )
        add(copy)
        log("guide editor: channel ${source.number} copied to ${copy.number}")
        copyId
    }

    override suspend fun delete(id: String) = change("delete the channel") {
        val channel = find(id)
        dao.removeChannel(id)
        log("guide editor: channel ${channel.number} deleted")
    }

    override suspend fun preview(draft: ChannelDraft): List<GuideSlot> = attempt("preview the channel") {
        val checked = checked(draft)
        engine.preview(
            ChannelEntity(
                id = PREVIEW_ID,
                number = 0,
                name = checked.name,
                rule = checked.rule.toJson(),
                seed = seedFor(checked.rule),
                alignmentMinutes = checked.alignmentMinutes,
                cursor = ChannelCursor().toJson(),
                filler = checked.filler,
            ),
        )
    }

    override suspend fun remakeStarterLineup() = change("remake the lineup") { remake() }

    /** Every channel, off ones too, in number order, each with what's on now. Null when it can't be read. */
    private suspend fun read(): List<EditableChannel>? = try {
        val now = clock()
        val around = dao.slotsBetween(now, now + ON_NOW_REACH_MILLIS).groupBy { it.channelId }
        dao.channels().map { channel ->
            val rule = channel.ruleOrNull()
            val look = looks.of(channel.name, rule, channel.icon)
            EditableChannel(
                id = channel.id,
                number = channel.number,
                enabled = channel.enabled,
                // A rule that can't be read shows as the default; saving over it fixes it.
                draft = ChannelDraft(channel.name, rule ?: ChannelRule(), channel.alignmentMinutes, channel.filler, channel.icon),
                scheduledUntil = channel.scheduledUntil,
                onNow = onNow(around[channel.id].orEmpty(), now),
                glyph = look.glyph,
                logo = look.logo,
            )
        }
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        log("guide editor: lineup not read: ${e.summary()}")
        null
    }

    /** The program on now; in filler, the program it leads to ("Up next: Big Atlas"). */
    private fun onNow(slots: List<SlotEntity>, now: Long): String? {
        val current = slots.firstOrNull { now >= it.startMillis && now < it.endMillis } ?: return null
        if (current.kind == SlotEntity.PROGRAM) return current.title
        val next = slots.firstOrNull { it.kind == SlotEntity.PROGRAM && it.startMillis >= current.endMillis }
        return if (next == null) "Up next" else "Up next: ${next.title}"
    }

    /** A change, under the engine's lock. */
    private suspend fun <T> change(what: String, block: suspend GuideEngine.Editing.() -> T): T = attempt(what) { engine.edit(block) }

    /**
     * Runs [block]: an [EditRejected] passes through, and any other failure is logged and
     * becomes "Couldn't [what]. Try again." A cancellation is rethrown when it's the
     * caller's own; otherwise it's Room's, from a closed database.
     */
    private suspend fun <T> attempt(what: String, block: suspend () -> T): T = try {
        block()
    } catch (e: EditRejected) {
        throw e
    } catch (e: Exception) {
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        log("guide editor: couldn't $what: ${e.summary()}")
        throw EditRejected("Couldn't $what. Try again.")
    }

    private suspend fun find(id: String): ChannelEntity = dao.channel(id) ?: throw gone()

    private fun gone() = EditRejected("That channel isn't in the lineup anymore.")

    /** [draft] with its name tidied and an unknown icon dropped, or the reason it can't be saved. */
    private fun checked(draft: ChannelDraft): ChannelDraft {
        val rule = draft.rule
        if (rule.types.any { it != ChannelRule.MOVIE && it != ChannelRule.EPISODE }) {
            throw EditRejected("A channel plays movies, episodes or both.")
        }
        val min = rule.minMinutes
        val max = rule.maxMinutes
        if ((min != null && min < 1) || (max != null && max < 1)) throw EditRejected("A length limit is at least 1 minute.")
        if (min != null && max != null && min > max) throw EditRejected("The shortest length is longer than the longest.")
        if ((rule.minAgeYears ?: 0) < 0) throw EditRejected("A title's age can't be less than 0 years.")
        val alignment = draft.alignmentMinutes
        if (alignment !in 1..60 || 60 % alignment != 0) {
            throw EditRejected("Programs start every 15 or 30 minutes, or another number of minutes that divides an hour.")
        }
        if (draft.filler != ChannelEntity.FILLER_TRAILERS && draft.filler != ChannelEntity.FILLER_UP_NEXT) {
            throw EditRejected("Between programs a channel shows trailers or an Up next card.")
        }
        // A glyph this build doesn't know (a newer station's) is Automatic here.
        return draft.copy(name = checkName(draft.name), icon = draft.icon?.takeIf { it in ChannelGlyphs.ALL })
    }

    /** A name on one line, with no space around it. */
    private fun checkName(name: String): String {
        val tidy = name.trim().replace(WHITESPACE, " ")
        if (tidy.isEmpty()) throw EditRejected("Give the channel a name.")
        if (tidy.length > MAX_NAME) throw EditRejected("A channel name can be up to $MAX_NAME characters.")
        return tidy
    }

    /** After the last channel's number, or the first number free if that's past 9999. */
    private fun nextNumber(channels: List<ChannelEntity>): Int {
        val after = (channels.maxOfOrNull { it.number } ?: 0) + 1
        if (after <= MAX_NUMBER) return after
        val taken = channels.mapTo(HashSet()) { it.number }
        return (1..MAX_NUMBER).firstOrNull { it !in taken } ?: throw EditRejected("Every channel number from 1 to $MAX_NUMBER is taken.")
    }

    private fun newId(channels: List<ChannelEntity>): String {
        val ids = channels.mapTo(HashSet()) { it.id }
        while (true) {
            val id = "channel-${UUID.randomUUID().toString().take(8)}"
            if (id !in ids) return id
        }
    }

    /** "Movies 2", or the next number free. */
    private fun copyName(name: String, channels: List<ChannelEntity>): String {
        val names = channels.mapTo(HashSet()) { it.name }
        var n = 2
        while ("$name $n" in names) n++
        return "$name $n"
    }

    private fun ChannelEntity.ruleOrNull(): ChannelRule? = runCatching { ChannelRule.fromJson(rule) }.getOrNull()

    /** The same rule always gives the same seed, so a preview and the channel it saves agree. */
    private fun seedFor(rule: ChannelRule): Long = GuideSeeds.seedFor(rule.toJson())

    companion object {
        const val MAX_NUMBER = 9999

        /**
         * The lineup's numbers once the channel at [moved] has moved there: it keeps its
         * number if that still fits between its neighbours, or takes the first number after
         * the channel before it, and the channels after it keep theirs unless they must move
         * up to stay in order. So a number set by hand survives a move whenever it can. Past
         * [MAX_NUMBER], everything is numbered 1 to n.
         */
        internal fun numbersAfterMove(numbers: List<Int>, moved: Int): List<Int> {
            val before = numbers.getOrNull(moved - 1) ?: 0
            val after = numbers.getOrNull(moved + 1) ?: Int.MAX_VALUE
            if (numbers[moved] in (before + 1) until after) return numbers
            val result = numbers.toMutableList()
            result[moved] = before + 1
            for (i in moved + 1 until result.size) {
                if (result[i] > result[i - 1]) break
                result[i] = result[i - 1] + 1
            }
            return if (result.last() > MAX_NUMBER) List(result.size) { it + 1 } else result
        }
        const val MAX_NAME = 80

        private const val MINUTE = 60_000L

        /** Far enough ahead to reach the program after any filler: start times are at most an hour apart. */
        private const val ON_NOW_REACH_MILLIS = 61 * MINUTE

        private const val PREVIEW_ID = "preview"
        private val WHITESPACE = Regex("\\s+")
    }
}
