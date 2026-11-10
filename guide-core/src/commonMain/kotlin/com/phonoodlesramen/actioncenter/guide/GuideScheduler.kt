package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.plex.cache.GuidePoolEntry
import java.time.Instant
import java.time.ZoneId

/**
 * Writes a channel's schedule ahead, one program at a time (design: scheduling engine):
 * the next title from the channel's order goes at the schedule's end, led in by trailers
 * when it's a film. It only ever appends: a slot, once written, never changes.
 *
 * **Lead-ins (G21).** An episode starts as the one before it ends. A film starts on the first
 * 5-minute mark at least [MIN_LEAD_MILLIS] after it, and the gap is trailers: the film's own
 * first, then one of another film on the channel when the gap is [TWO_TRAILERS_MILLIS] or
 * more. The cache knows a trailer but not its length (most run 2 to 2½ minutes), so each
 * trailer gets an even share of the gap; the player shows a short countdown if one ends
 * early and cuts it at the slot's end if it runs long. A film with no trailer to show starts
 * as the program before it ends. There are no Up next cards: the gaps before 2.7's half and
 * quarter hours were blank cards no one sat through. A channel's start times and filler
 * ([ChannelEntity.alignmentMinutes], [ChannelEntity.filler]) are no longer read.
 */
internal class GuideScheduler(private val library: GuideLibrary) {
    /** The new slots and the channel with its cursor moved on: saved together. */
    class Extension(val channel: ChannelEntity, val slots: List<SlotEntity>)

    /** A program's lead-in: the trailer slots before it, and when it starts. */
    class LeadIn(val slots: List<SlotEntity>, val start: Long)

    /**
     * Schedules [channel] until its schedule reaches [until]. It carries on from its end, or
     * from the current 5-minute mark if that's in the past (nothing is written for time
     * that's gone). Null when there's nothing to schedule.
     */
    suspend fun extend(channel: ChannelEntity, lastSlotEnd: Long?, now: Long, until: Long, zone: ZoneId): Extension? {
        val rule = ChannelRule.fromJson(channel.rule)
        val cursor = ChannelCursor.fromJson(channel.cursor)
        val draw = Draw(library, rule, Instant.ofEpochMilli(now).atZone(zone).year, channel.seed, cursor)
        var start = maxOf(channel.scheduledUntil ?: Long.MIN_VALUE, lastSlotEnd ?: Long.MIN_VALUE, GuideTime.floor(now, START_MINUTES, zone))
        val slots = mutableListOf<SlotEntity>()
        var current = cursor.pending?.let { draw.pending(it) } ?: draw.next()
        var placed = 0
        while (current != null && start < until && placed < MAX_PROGRAMS) {
            start = place(channel.id, start, current, zone, draw, slots)
            current = draw.next()
            placed++
        }
        if (placed == 0) return null
        return Extension(
            channel.copy(cursor = draw.cursor(pending = current?.ratingKey).toJson(), scheduledUntil = start, extendedAt = now),
            slots,
        )
    }

    /**
     * Lays [programs], in their order, from [from] with G21's lead-ins: a schedule written
     * before G21, with its Up next cards, again from the end of the program on now. The
     * cursor stays as it is, so the channel carries on where that schedule ended.
     */
    suspend fun relay(channel: ChannelEntity, programs: List<Program>, from: Long, now: Long, zone: ZoneId): Extension {
        val rule = ChannelRule.fromJson(channel.rule)
        val draw = Draw(library, rule, Instant.ofEpochMilli(now).atZone(zone).year, channel.seed, ChannelCursor.fromJson(channel.cursor))
        val slots = mutableListOf<SlotEntity>()
        var start = from
        for (program in programs) start = place(channel.id, start, program, zone, draw, slots)
        return Extension(channel.copy(scheduledUntil = start, extendedAt = now), slots)
    }

    /** Adds [program] and its lead-in at [start] to [slots]; returns where it ends. */
    private suspend fun place(channelId: String, start: Long, program: Program, zone: ZoneId, draw: Draw, slots: MutableList<SlotEntity>): Long {
        val lead = leadIn(channelId, start, program, zone) { exclude, at -> draw.trailer(exclude, at) }
        slots += lead.slots
        val end = lead.start + program.durationMs
        slots += program.slot(channelId, lead.start, end)
        return end
    }

    companion object {
        /** Films start on the clock's 5-minute marks (G21). */
        const val START_MINUTES = 5

        /** A film's trailers get at least this long, so most play whole. */
        const val MIN_LEAD_MILLIS = 2 * 60_000L

        /** A gap this long has room for two trailers. */
        const val TWO_TRAILERS_MILLIS = 5 * 60_000L

        /** Even 15-minute programs fill a day in 96: a guard, never reached. */
        private const val MAX_PROGRAMS = 500

        /**
         * How [next] follows whatever ends at [end] (G21). [extra] finds another film's
         * trailer on the channel, never one of the titles it's given, chosen by the time.
         */
        suspend fun leadIn(
            channelId: String,
            end: Long,
            next: Program,
            zone: ZoneId,
            extra: suspend (exclude: Set<String>, at: Long) -> Program? = { _, _ -> null },
        ): LeadIn {
            if (next.showKey != null) return LeadIn(emptyList(), end)
            val start = GuideTime.ceil(end + MIN_LEAD_MILLIS, START_MINUTES, zone)
            val wanted = if (start - end >= TWO_TRAILERS_MILLIS) 2 else 1
            val films = mutableListOf<Program>()
            if (next.trailerKey != null) films += next
            while (films.size < wanted) {
                films += extra(films.mapTo(mutableSetOf(next.ratingKey)) { it.ratingKey }, end) ?: break
            }
            if (films.isEmpty()) return LeadIn(emptyList(), end)
            val share = (start - end) / films.size
            val slots = films.mapIndexed { i, film ->
                val from = end + i * share
                SlotEntity(
                    channelId, from, if (i == films.lastIndex) start else from + share, SlotEntity.TRAILER, film.trailerKey,
                    "Trailer: ${film.title}", episode = null, showKey = null, art = film.art, contentRating = null, summary = null,
                )
            }
            return LeadIn(slots, start)
        }
    }
}

/** A title ready to place: its length, and what its slot shows, copied from the cache. */
internal class Program(
    val ratingKey: String,
    val durationMs: Long,
    val title: String,
    val episode: String?,
    val showKey: String?,
    val art: String?,
    val contentRating: String?,
    val summary: String?,
    /** A movie's trailer extra, for the filler before it. */
    val trailerKey: String?,
) {
    fun slot(channelId: String, start: Long, end: Long) =
        SlotEntity(channelId, start, end, SlotEntity.PROGRAM, ratingKey, title, episode, showKey, art, contentRating, summary)
}

/**
 * A channel's order (the rule's [ChannelRule.Order]) from its cursor. Everything is ranked
 * by [GuideSeeds.rank] of the channel's seed, so the same library gives the same picks; a
 * title added later takes its ranked place in the current round.
 */
private class Draw(
    private val library: GuideLibrary,
    private val rule: ChannelRule,
    private val year: Int,
    private val seed: Long,
    cursor: ChannelCursor,
) {
    private var round = cursor.round
    private val played = LinkedHashSet(cursor.played)
    private var last = cursor.last
    private val positions = cursor.positions.toMutableMap()
    private var loadedPool: List<GuidePoolEntry>? = null
    private var loadedShows: List<String>? = null
    private var loadedTrailers: List<String>? = null

    /** The program drawn last time, if the rule still has it. */
    suspend fun pending(ratingKey: String): Program? = library.entry(rule, year, ratingKey)?.let { details(it) }

    /** The next program, or null when the rule matches nothing. */
    suspend fun next(): Program? {
        repeat(MAX_TRIES) {
            val entry = nextEntry() ?: return null
            // A title deleted since the pool was read is skipped.
            details(entry)?.let { return it }
        }
        return null
    }

    fun cursor(pending: String?) = ChannelCursor(round, played.toList(), last, positions.toMap(), pending)

    /**
     * Another film on the channel with a trailer, not one of [exclude], for a lead-in at
     * [at] (G21). The pick follows the seed and the minute, so the same schedule gets the
     * same trailers.
     */
    suspend fun trailer(exclude: Set<String>, at: Long): Program? {
        val films = loadedTrailers ?: library.trailerFilms(rule, year).also { loadedTrailers = it }
        val turn = (at / 60_000L).toInt()
        val pick = films.filter { it !in exclude }.minWithOrNull(compareBy({ GuideSeeds.rank(seed, turn, it) }, { it })) ?: return null
        return library.entry(rule, year, pick)?.let { details(it) }?.takeIf { it.trailerKey != null }
    }

    private suspend fun nextEntry(): GuidePoolEntry? = when (rule.order) {
        ChannelRule.Order.SHUFFLE -> shuffled()
        ChannelRule.Order.IN_ORDER -> inOrder()
        ChannelRule.Order.ROTATE_SHOWS -> rotated()
    }

    /** A shuffle bag: titles not yet played this round, in the round's ranked order. */
    private suspend fun shuffled(): GuidePoolEntry? {
        val pool = loadedPool ?: library.pool(rule, year).also { loadedPool = it }
        if (pool.isEmpty()) return null
        var candidates = pool.filter { it.ratingKey !in played }
        if (candidates.isEmpty()) {
            round++
            played.clear()
            // A new round never opens with the title that closed the last one.
            candidates = pool.filter { it.ratingKey != last }.ifEmpty { pool }
        }
        val pick = candidates.minWith(compareBy({ GuideSeeds.rank(seed, round, it.ratingKey) }, { it.ratingKey }))
        played += pick.ratingKey
        last = pick.ratingKey
        return pick
    }

    /** Episodes in order, show by show (the rule's shows in its order), then from the start again. */
    private suspend fun inOrder(): GuidePoolEntry? {
        val pool = loadedPool ?: library.pool(rule, year).sortedWith(episodeOrder).also { loadedPool = it }
        if (pool.isEmpty()) return null
        val from = last?.let { show ->
            positions[show]?.let { GuidePoolEntry(it.key, ChannelRule.EPISODE, 0, show, it.season, it.episode) }
        }
        val pick = from?.let { position -> pool.firstOrNull { episodeOrder.compare(it, position) > 0 } } ?: pool.first()
        val show = pick.showKey.orEmpty()
        positions[show] = EpisodePosition(pick.season, pick.episode, pick.ratingKey)
        last = show
        return pick
    }

    /** One episode from each show in turn (the round's ranked order), each show's in order. */
    private suspend fun rotated(): GuidePoolEntry? {
        val shows = loadedShows ?: library.shows(rule, year).also { loadedShows = it }
        if (shows.isEmpty()) return null
        repeat(shows.size + 1) {
            var candidates = shows.filter { it !in played }
            if (candidates.isEmpty()) {
                round++
                played.clear()
                candidates = shows.filter { it != last }.ifEmpty { shows }
            }
            val show = candidates.minWith(compareBy({ GuideSeeds.rank(seed, round, it) }, { it }))
            played += show
            val position = positions[show]
            // After a show's last episode, it starts again from its first.
            val episode = library.episodeAfter(rule, year, show, position)
                ?: position?.let { library.episodeAfter(rule, year, show, null) }
            if (episode != null) {
                positions[show] = EpisodePosition(episode.season, episode.episode, episode.ratingKey)
                last = show
                return episode
            }
        }
        return null
    }

    private val episodeOrder = compareBy<GuidePoolEntry>(
        { entry -> rule.shows.indexOf(entry.showKey).let { if (it < 0) Int.MAX_VALUE else it } },
        { it.showKey.orEmpty() },
        { it.season ?: GuideLibrary.UNNUMBERED },
        { it.episode ?: GuideLibrary.UNNUMBERED },
        { it.ratingKey },
    )

    private suspend fun details(entry: GuidePoolEntry): Program? {
        val item = library.item(entry.ratingKey)?.takeIf { it.partKey != null } ?: return null
        if (item.type != ChannelRule.EPISODE) {
            return Program(
                item.ratingKey, entry.durationMs, item.title, episode = null, showKey = null, art = item.art ?: item.thumb,
                contentRating = item.contentRating, summary = item.summary, trailerKey = item.trailerKey,
            )
        }
        val show = item.showKey?.let { library.item(it) }
        return Program(
            item.ratingKey, entry.durationMs,
            title = item.showTitle ?: show?.title ?: item.title,
            episode = episodeLabel(item.season, item.episode, item.title),
            showKey = item.showKey,
            // As Up Next shows an episode: its own still, else the show's art.
            art = item.thumb ?: show?.art ?: item.art,
            contentRating = item.contentRating ?: show?.contentRating,
            summary = item.summary ?: show?.summary,
            trailerKey = null,
        )
    }

    private companion object {
        const val MAX_TRIES = 20
    }
}

/** "S2 E5 · Paper Moons"; just the title when the episode isn't numbered. */
internal fun episodeLabel(season: Int?, episode: Int?, title: String): String =
    if (season != null && episode != null) "S$season E$episode · $title" else title

/** Clock arithmetic on the local clock, so "the half hour" is the viewer's. */
internal object GuideTime {
    /** The last multiple of [stepMinutes] at or before [millis]. */
    fun floor(millis: Long, stepMinutes: Int, zone: ZoneId): Long {
        val step = stepMinutes * 60_000L
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(millis)).totalSeconds * 1_000L
        return Math.floorDiv(millis + offset, step) * step - offset
    }

    /** The first multiple of [stepMinutes] at or after [millis]. */
    fun ceil(millis: Long, stepMinutes: Int, zone: ZoneId): Long {
        val floor = floor(millis, stepMinutes, zone)
        return if (floor == millis) millis else floor + stepMinutes * 60_000L
    }
}

/** Deterministic randomness: SplitMix64, so a seed and a ratingKey always give the same rank. */
internal object GuideSeeds {
    private val GOLDEN = 0x9E3779B97F4A7C15UL.toLong()

    /** A channel's seed, from its id. */
    fun seedFor(channelId: String): Long = mix(channelId.hashCode().toLong())

    /** Where [key] falls in [round] of a shuffle with [seed]. */
    fun rank(seed: Long, round: Int, key: String): Long = mix(seed xor mix(round.toLong()) xor (key.hashCode().toLong() * GOLDEN))

    fun mix(value: Long): Long {
        var z = value + GOLDEN
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return z xor (z ushr 31)
    }
}
