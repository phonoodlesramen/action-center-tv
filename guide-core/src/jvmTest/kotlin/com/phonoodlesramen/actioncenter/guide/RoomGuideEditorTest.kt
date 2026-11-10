package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.series
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.HOUR
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.MINUTE
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.channel
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [RoomGuideEditor] on an in-memory guide.db and a made-up library: every operation, what
 * it rejects, the G16 rewrite that leaves what's on now, the earlier slots and every other
 * channel alone, and a preview that writes nothing. Real time, so runBlocking.
 */
class RoomGuideEditorTest {
    private val harness = GuideHarness()
    private val editor get() = harness.editor
    private val dao get() = harness.dao

    @After
    fun tearDown() = harness.close()

    /** 20 films from 88 to 145 minutes, comedies and dramas; the dramas have trailers. */
    private val films: List<ItemEntity> = (1..20).map {
        movie(
            "f${it.toString().padStart(2, '0')}", "Film $it", 85 + it * 3,
            genres = listOf(if (it % 2 == 0) "Drama" else "Comedy"),
            year = 1975 + it * 2,
            trailer = if (it % 2 == 0) "trailer-$it" else null,
            rating = if (it % 3 == 0) "R" else "PG-13",
        )
    }

    /** Every film, and the long ones (121 minutes and up), scheduled for the next 24 hours. */
    private suspend fun twoChannels() {
        harness.synced(films)
        harness.channels(channel("movies", ChannelRule()), channel("long", ChannelRule(minMinutes = 120), number = 2))
        harness.engine.extend()
    }

    private suspend fun lineup(predicate: (List<EditableChannel>) -> Boolean = { true }): List<EditableChannel> =
        withTimeout(TIMEOUT) { editor.lineup.first(predicate) }

    private suspend fun rejected(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: EditRejected) {
            return e.message.orEmpty()
        }
        fail("expected the editor to turn it down")
        return ""
    }

    private fun assertContiguous(slots: List<SlotEntity>) =
        slots.zipWithNext { a, b -> assertEquals("contiguous at ${b.startMillis}", a.endMillis, b.startMillis) }

    private fun draft(rule: ChannelRule = ChannelRule(), name: String = "Late Show", alignment: Int = 30, filler: String = ChannelEntity.FILLER_TRAILERS) =
        ChannelDraft(name, rule, alignment, filler)

    // The lineup and choices.

    @Test
    fun `the lineup lists every channel in number order, off ones too, with what's on now`() = runBlocking {
        twoChannels()
        harness.channels(channel("westerns", ChannelRule(genres = listOf("Western")), number = 3))
        editor.setEnabled("long", false)

        val list = lineup { it.size == 3 && !it[1].enabled }
        assertEquals(listOf("movies", "long", "westerns"), list.map { it.id })
        assertEquals(listOf(1, 2, 3), list.map { it.number })
        assertEquals(listOf(true, false, true), list.map { it.enabled })
        assertEquals(ChannelRule(minMinutes = 120), list[1].draft.rule)
        assertEquals("long", list[1].draft.name)
        assertEquals(30, list[1].draft.alignmentMinutes)
        assertEquals(ChannelEntity.FILLER_TRAILERS, list[1].draft.filler)
        assertEquals(dao.channel("movies")!!.scheduledUntil, list[0].scheduledUntil)
        // A lead-in's trailer is on at first (G21): what's on names the film it leads to.
        val now = dao.slotAt("movies", harness.now)!!
        val film = dao.slots("movies").first { it.kind == SlotEntity.PROGRAM && it.endMillis > harness.now }
        assertEquals(if (now.kind == SlotEntity.PROGRAM) now.title else "Up next: ${film.title}", list[0].onNow)
        assertNotNull("an off channel keeps its schedule", list[1].onNow)
        assertNull("nothing matches, so nothing's on", list[2].onNow)
        assertNull(list[2].scheduledUntil)

        // In filler, the program it leads to.
        val filler = dao.slots("movies").first { it.kind != SlotEntity.PROGRAM }
        val next = dao.slots("movies").first { it.kind == SlotEntity.PROGRAM && it.startMillis >= filler.endMillis }
        harness.now = filler.startMillis + 1_000
        assertEquals("Up next: ${next.title}", lineup()[0].onNow)
    }

    @Test
    fun `choices come from the library, as the starter lineup totals genres`() = runBlocking {
        harness.synced(
            films +
                series("ridge", "Ridge Line", listOf("Drama", "Crime"), seasons = 2, perSeason = 10, minutes = 52) +
                series("open", "Open House", listOf("Comedy"), seasons = 1, perSeason = 5, minutes = 22) +
                // A show whose episodes have no file can't be scheduled.
                listOf(Fixtures.show("empty", "Empty Nest"), Fixtures.episode(Fixtures.show("empty", "Empty Nest"), 1, 1, 30, file = false)),
        )

        val choices = editor.choices()
        assertEquals(listOf(Choice("1", "Movies · 20 titles"), Choice("2", "TV · 3 shows")), choices.sections)
        // Dramas are the longer films.
        assertEquals(listOf("Drama", "Comedy"), choices.movieGenres)
        // Ridge Line's 20 episodes count for both its genres.
        assertEquals(listOf("Crime", "Drama", "Comedy"), choices.tvGenres)
        assertEquals(listOf(Choice("open", "Open House"), Choice("ridge", "Ridge Line")), choices.shows)
        assertEquals(listOf(1970, 1980, 1990, 2000, 2010), choices.decades)
        assertEquals(listOf("PG-13", "R", "TV-14"), choices.contentRatings)
        assertEquals("collections wait for Phase 3's cache", emptyList<String>(), choices.collections)
    }

    // Changes that rewrite nothing.

    @Test
    fun `rename, renumber and on or off rewrite no slots`() = runBlocking {
        twoChannels()
        val slots = dao.slotsFrom(Long.MIN_VALUE)

        editor.rename("movies", "  Big\nScreen  ")
        editor.renumber("long", 12)
        editor.setEnabled("long", false)

        val movies = dao.channel("movies")!!
        assertEquals("Big Screen", movies.name)
        assertEquals(12, dao.channel("long")!!.number)
        assertEquals(false, dao.channel("long")!!.enabled)
        assertEquals(slots, dao.slotsFrom(Long.MIN_VALUE))
        val listed = lineup { it.any { channel -> channel.number == 12 && !channel.enabled } }
        assertEquals(listOf("Big Screen", "long"), listed.map { it.draft.name })
    }

    @Test
    fun `a name or number the editor can't take is turned down in a sentence`() = runBlocking {
        twoChannels()

        assertEquals("Give the channel a name.", rejected { editor.rename("movies", "   ") })
        assertEquals("A channel name can be up to 80 characters.", rejected { editor.rename("movies", "x".repeat(81)) })
        assertEquals("Channel 2 is taken by long.", rejected { editor.renumber("movies", 2) })
        assertEquals("Channel numbers go from 1 to 9999.", rejected { editor.renumber("movies", 0) })
        assertEquals("Channel numbers go from 1 to 9999.", rejected { editor.renumber("movies", 10_000) })
        val gone = "That channel isn't in the lineup anymore."
        assertEquals(gone, rejected { editor.rename("nope", "Name") })
        assertEquals(gone, rejected { editor.renumber("nope", 5) })
        assertEquals(gone, rejected { editor.move("nope", 0) })
        assertEquals(gone, rejected { editor.setEnabled("nope", false) })
        assertEquals(gone, rejected { editor.update("nope", draft()) })
        assertEquals(gone, rejected { editor.duplicate("nope") })
        assertEquals(gone, rejected { editor.delete("nope") })
        assertEquals("movies", dao.channel("movies")!!.name)
        assertEquals(1, dao.channel("movies")!!.number)
    }

    @Test
    fun `a moved channel takes the number after the one before it, and the rest keep theirs`() = runBlocking {
        harness.synced(films)
        harness.channels(
            channel("a", ChannelRule(), number = 3),
            channel("b", ChannelRule(), number = 7),
            channel("c", ChannelRule(), number = 20),
            channel("d", ChannelRule(), number = 21),
        )
        harness.engine.extend()
        val slots = dao.slotsFrom(Long.MIN_VALUE)

        editor.move("d", 1)
        assertEquals(listOf("a" to 3, "d" to 4, "b" to 7, "c" to 20), dao.channels().map { it.id to it.number })
        editor.move("a", 99)
        assertEquals(listOf("d" to 4, "b" to 7, "c" to 20, "a" to 21), dao.channels().map { it.id to it.number })
        editor.move("c", -4)
        assertEquals(listOf("c" to 1, "d" to 4, "b" to 7, "a" to 21), dao.channels().map { it.id to it.number })
        assertEquals("moving rewrites no slots", slots, dao.slotsFrom(Long.MIN_VALUE))
    }

    @Test
    fun `channels after a moved one move up only as far as they must`() {
        // 1, 2, 3 and a hand-set 10: the first moves between 2 and 3.
        assertEquals(listOf(2, 3, 4, 10), RoomGuideEditor.numbersAfterMove(listOf(2, 1, 3, 10), moved = 1))
        // To the front of 1, 2, 3: everything moves up one.
        assertEquals(listOf(1, 2, 3), RoomGuideEditor.numbersAfterMove(listOf(3, 1, 2), moved = 0))
        // A number that still fits between its new neighbours stays.
        assertEquals(listOf(3, 12, 20), RoomGuideEditor.numbersAfterMove(listOf(3, 12, 20), moved = 1))
        // Past the last number there is, the lineup is numbered 1 to n.
        assertEquals(listOf(1, 2), RoomGuideEditor.numbersAfterMove(listOf(RoomGuideEditor.MAX_NUMBER, 5), moved = 1))
    }

    // New channels.

    @Test
    fun `a new channel is numbered after the last and scheduled for the next 24 hours`() = runBlocking {
        twoChannels()
        harness.channels(channel("far", ChannelRule(), number = 40))

        val id = editor.create(draft(ChannelRule(genres = listOf("Drama")), name = " Dramas ", filler = ChannelEntity.FILLER_UP_NEXT))
        val channel = dao.channel(id)!!
        assertEquals(41, channel.number)
        assertEquals("Dramas", channel.name)
        assertTrue(channel.enabled)
        assertEquals(ChannelRule(genres = listOf("Drama")), ChannelRule.fromJson(channel.rule))
        assertEquals(ChannelEntity.FILLER_UP_NEXT, channel.filler)
        val slots = dao.slots(id)
        assertNotNull("something's on at once", dao.slotAt(id, harness.now))
        assertTrue(slots.last().endMillis >= harness.now + 24 * HOUR)
        assertEquals(slots.last().endMillis, channel.scheduledUntil)
        assertContiguous(slots)
        val dramas = films.filter { "Drama" in it.genres }.map { it.ratingKey }.toSet()
        assertTrue(slots.filter { it.kind == SlotEntity.PROGRAM }.all { it.itemKey in dramas })
        assertTrue("trailers lead the films in, whatever filler was saved (G21)", slots.any { it.kind == SlotEntity.TRAILER })
    }

    @Test
    fun `a copy is named with the next number free, and scheduled on its own`() = runBlocking {
        twoChannels()
        editor.setEnabled("long", false)

        val first = editor.duplicate("long")
        val second = editor.duplicate("long")
        val copy = dao.channel(first)!!
        assertEquals("long 2", copy.name)
        assertEquals("long 3", dao.channel(second)!!.name)
        assertEquals(listOf(3, 4), listOf(copy.number, dao.channel(second)!!.number))
        val source = dao.channel("long")!!
        assertEquals(source.rule, copy.rule)
        assertEquals(source.alignmentMinutes, copy.alignmentMinutes)
        assertEquals(source.filler, copy.filler)
        assertEquals("a copy of an off channel is off too", false, copy.enabled)
        assertNotEquals(source.seed, copy.seed)
        assertTrue(dao.slots(first).last().endMillis >= harness.now + 24 * HOUR)
    }

    @Test
    fun `deleting a channel takes its slots with it`() = runBlocking {
        twoChannels()
        val long = dao.slots("long")

        editor.delete("movies")
        assertEquals(listOf("long"), dao.channels().map { it.id })
        assertEquals(emptyList<SlotEntity>(), dao.slots("movies"))
        assertEquals(long, dao.slots("long"))
    }

    // G16: saving a channel rewrites only it, from the end of what's on now.

    @Test
    fun `saving a rule rewrites that channel from the end of the program on now, and nothing else`() = runBlocking {
        twoChannels()
        harness.now += 5 * HOUR
        harness.engine.extend()
        // Ten minutes into a program, with earlier slots behind it.
        val program = dao.slots("movies").first { it.kind == SlotEntity.PROGRAM && it.endMillis > harness.now }
        harness.now = maxOf(harness.now, program.startMillis + 10 * MINUTE)
        val now = harness.now
        val before = dao.slots("movies")
        val onNow = dao.slotAt("movies", now)!!
        assertEquals(SlotEntity.PROGRAM, onNow.kind)
        assertTrue(before.first().startMillis < onNow.startMillis)
        val others = dao.channels().filter { it.id != "movies" }
        val otherSlots = dao.slots("long")
        val rule = ChannelRule(maxMinutes = 100)
        val preview = editor.preview(draft(rule))

        editor.update("movies", draft(rule, name = "Short Films"))

        val after = dao.slots("movies")
        assertEquals("the slot on now and the earlier ones stay", before.filter { it.startMillis <= onNow.startMillis }, after.filter { it.startMillis < onNow.endMillis })
        val rewritten = after.filter { it.startMillis >= onNow.endMillis }
        assertEquals(onNow.endMillis, rewritten.first().startMillis)
        assertContiguous(after)
        val programs = rewritten.filter { it.kind == SlotEntity.PROGRAM }
        assertTrue("the new rule's films", programs.all { it.endMillis - it.startMillis <= 100 * MINUTE })
        programs.forEach { assertEquals("on a 5-minute mark", 0L, it.startMillis % (5 * MINUTE)) }
        assertTrue(after.last().endMillis >= now + 24 * HOUR)
        assertEquals(
            "a fresh cursor and the rule's seed: the order the preview showed",
            preview.filter { !it.filler }.map { it.itemKey }.take(6),
            programs.map { it.itemKey }.take(6),
        )

        val channel = dao.channel("movies")!!
        assertEquals("Short Films", channel.name)
        assertEquals(rule, ChannelRule.fromJson(channel.rule))
        assertEquals(after.last().endMillis, channel.scheduledUntil)
        assertEquals("every other channel is untouched", others, dao.channels().filter { it.id != "movies" })
        assertEquals(otherSlots, dao.slots("long"))

        // And it carries on from its new cursor, appending only (old slots are pruned as ever).
        harness.now += 5 * HOUR
        harness.engine.extend()
        val later = dao.slots("movies")
        val kept = after.filter { it.endMillis >= harness.now - GuideEngine.KEEP_PAST_MILLIS }
        assertEquals(kept, later.take(kept.size))
        assertTrue(later.size > kept.size)
    }

    @Test
    fun `in a gap the new rule plays from the current start time`() = runBlocking {
        harness.synced(films)
        harness.channels(channel("westerns", ChannelRule(genres = listOf("Western"))), channel("movies", ChannelRule(), number = 2))
        harness.engine.extend()
        assertNull(dao.slotAt("westerns", harness.now))
        val movies = dao.slots("movies")

        editor.update("westerns", draft(ChannelRule(genres = listOf("Comedy")), name = "Comedies"))

        val slots = dao.slots("westerns")
        assertNotNull("something's on at once", dao.slotAt("westerns", harness.now))
        assertEquals(GuideTime.floor(harness.now, GuideScheduler.START_MINUTES, ZoneOffset.UTC), slots.first().startMillis)
        assertTrue(slots.last().endMillis >= harness.now + 24 * HOUR)
        assertEquals(movies, dao.slots("movies"))
    }

    @Test
    fun `start times or filler from an older page still rewrite, a new name alone rewrites nothing`() = runBlocking {
        twoChannels()
        val before = dao.slots("movies")
        val onNow = dao.slotAt("movies", harness.now)!!

        editor.update("movies", draft(name = "Films"))
        assertEquals("Films", dao.channel("movies")!!.name)
        assertEquals(before, dao.slots("movies"))

        editor.update("movies", draft(name = "Films", filler = ChannelEntity.FILLER_UP_NEXT))
        val upNext = dao.slots("movies")
        assertEquals(before.filter { it.startMillis <= onNow.startMillis }, upNext.filter { it.startMillis < onNow.endMillis })
        assertTrue("trailers all the same (G21)", upNext.filter { it.startMillis >= onNow.endMillis }.any { it.kind == SlotEntity.TRAILER })

        editor.update("movies", draft(name = "Films", alignment = 15, filler = ChannelEntity.FILLER_UP_NEXT))
        val programs = dao.slots("movies").filter { it.startMillis >= onNow.endMillis && it.kind == SlotEntity.PROGRAM }
        assertTrue(programs.isNotEmpty())
        programs.forEach { assertEquals("on a 5-minute mark, whatever was saved", 0L, it.startMillis % (5 * MINUTE)) }
        assertEquals(15, dao.channel("movies")!!.alignmentMinutes)
    }

    @Test
    fun `a draft the editor can't save is turned down, and nothing changes`() = runBlocking {
        twoChannels()
        val channels = dao.channels()
        val slots = dao.slotsFrom(Long.MIN_VALUE)

        assertEquals("Give the channel a name.", rejected { editor.create(draft(name = "")) })
        assertEquals(
            "Programs start every 15 or 30 minutes, or another number of minutes that divides an hour.",
            rejected { editor.update("movies", draft(alignment = 7)) },
        )
        assertEquals(
            "Programs start every 15 or 30 minutes, or another number of minutes that divides an hour.",
            rejected { editor.create(draft(alignment = 0)) },
        )
        assertEquals("The shortest length is longer than the longest.", rejected { editor.create(draft(ChannelRule(minMinutes = 90, maxMinutes = 60))) })
        assertEquals("A length limit is at least 1 minute.", rejected { editor.preview(draft(ChannelRule(maxMinutes = 0))) })
        assertEquals("A channel plays movies, episodes or both.", rejected { editor.create(draft(ChannelRule(types = listOf("song")))) })
        assertEquals("Between programs a channel shows trailers or an Up next card.", rejected { editor.update("movies", draft(filler = "ads")) })
        assertEquals(channels, dao.channels())
        assertEquals(slots, dao.slotsFrom(Long.MIN_VALUE))
    }

    // The preview.

    @Test
    fun `a preview is the next 24 hours from now, writes nothing, and is what the channel plays`() = runBlocking {
        twoChannels()
        val channels = dao.channels()
        val slots = dao.slotsFrom(Long.MIN_VALUE)
        val rule = ChannelRule(genres = listOf("Drama"))

        val preview = editor.preview(draft(rule))
        assertEquals("nothing written", channels, dao.channels())
        assertEquals(slots, dao.slotsFrom(Long.MIN_VALUE))
        assertTrue(preview.first().startMillis <= harness.now && preview.first().endMillis > harness.now)
        assertTrue(preview.last().endMillis >= harness.now + 24 * HOUR)
        assertTrue(preview.any { it.filler && it.itemKey?.startsWith("trailer-") == true })
        assertTrue(preview.filter { !it.filler }.all { it.offAir == null })

        val id = editor.create(draft(rule))
        val made = dao.slots(id).map { Triple(it.startMillis, it.endMillis, it.itemKey) }
        assertEquals(preview.map { Triple(it.startMillis, it.endMillis, it.itemKey) }, made)

        assertEquals("a rule that matches nothing previews nothing", emptyList<GuideSlot>(), editor.preview(draft(ChannelRule(genres = listOf("Western")))))
    }

    // Never a crash.

    @Test
    fun `anything else that goes wrong becomes a sentence too`() = runBlocking {
        twoChannels()
        harness.guide.close()

        assertEquals("Couldn't rename the channel. Try again.", rejected { editor.rename("movies", "Films") })
        assertEquals("Couldn't save the channel. Try again.", rejected { editor.update("movies", draft()) })
        assertEquals("Couldn't remake the lineup. Try again.", rejected { editor.remakeStarterLineup() })
        assertTrue(harness.logs.any { it.startsWith("guide editor: couldn't rename the channel") })
        assertNull("the lineup stops, without throwing", withTimeoutOrNull(2_000) { editor.lineup.firstOrNull() })
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
