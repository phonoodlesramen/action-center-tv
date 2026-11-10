package com.phonoodlesramen.actioncenter.guide

import com.phonoodlesramen.actioncenter.guide.ChannelRule.Companion.EPISODE
import com.phonoodlesramen.actioncenter.guide.Fixtures.movie
import com.phonoodlesramen.actioncenter.guide.Fixtures.series
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.HOUR
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.MINUTE
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.WEEKNIGHT
import com.phonoodlesramen.actioncenter.guide.GuideHarness.Companion.channel
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scheduler: lead-ins (G21), seeds, the orders, and a schedule that only grows at its end. */
class GuideSchedulerTest {
    private val harness = GuideHarness()

    @After
    fun tearDown() = harness.close()

    /** 20 films from 85 to 142 minutes; every other one has a trailer. */
    private val films: List<ItemEntity> = (1..20).map {
        movie("f${it.toString().padStart(2, '0')}", "Film $it", 85 + it * 3, trailer = if (it % 2 == 0) "trailer-$it" else null)
    }
    private val moviesChannel = channel("movies", ChannelRule())

    private fun assertContiguous(slots: List<SlotEntity>) =
        slots.zipWithNext { a, b -> assertEquals("contiguous at ${b.startMillis}", a.endMillis, b.startMillis) }

    @Test
    fun `films start on a 5-minute mark at least 2 minutes after the last, with trailers between`() = runTest {
        harness.synced(films)
        harness.channels(moviesChannel)
        harness.engine.extend()

        val slots = harness.dao.slots("movies")
        assertEquals("from the current 5-minute mark", WEEKNIGHT - MINUTE, slots.first().startMillis)
        assertTrue("24 hours ahead", slots.last().endMillis >= WEEKNIGHT + 24 * HOUR)
        assertContiguous(slots)
        val lengths = films.associate { it.ratingKey to it.durationMs }
        val programs = slots.filter { it.kind == SlotEntity.PROGRAM }
        for (program in programs) {
            assertEquals(0L, program.startMillis % (5 * MINUTE))
            assertEquals(lengths[program.itemKey], program.endMillis - program.startMillis)
        }
        programs.zipWithNext { a, b ->
            assertEquals(GuideTime.ceil(a.endMillis + GuideScheduler.MIN_LEAD_MILLIS, 5, ZoneOffset.UTC), b.startMillis)
        }
        assertEquals("trailers and nothing else between films", setOf(SlotEntity.TRAILER), slots.filter { it.kind != SlotEntity.PROGRAM }.map { it.kind }.toSet())
        assertEquals(slots.last().endMillis, harness.dao.channels().single().scheduledUntil)
    }

    @Test
    fun `a film's own trailer leads it in, and other films' trailers fill the rest`() = runTest {
        harness.synced(films)
        harness.channels(moviesChannel)
        harness.engine.extend()

        val slots = harness.dao.slots("movies")
        val byKey = films.associateBy { it.ratingKey }
        val withTrailers = films.filter { it.trailerKey != null }.associateBy { it.trailerKey }
        slots.forEachIndexed { i, slot ->
            if (slot.kind != SlotEntity.PROGRAM) return@forEachIndexed
            val film = byKey.getValue(slot.itemKey!!)
            val lead = slots.subList(0, i).takeLastWhile { it.kind == SlotEntity.TRAILER }
            assertTrue("one or two trailers before ${film.title}", lead.size in 1..2)
            if (film.trailerKey != null) assertEquals(film.trailerKey, lead.first().itemKey)
            lead.forEach { trailer ->
                val of = withTrailers.getValue(trailer.itemKey)
                assertEquals("Trailer: ${of.title}", trailer.title)
            }
            assertEquals("never the same trailer twice in a row", lead.map { it.itemKey }.distinct(), lead.map { it.itemKey })
        }
    }

    @Test
    fun `a lead-in is the film's trailer, another film's when there's room, or nothing`() = runTest {
        // 8:41 PM UTC.
        val end = WEEKNIGHT
        fun film(key: String, title: String, trailer: String?) = Program(key, 90 * MINUTE, title, null, null, "/art/$key", "PG", null, trailer)
        val other = film("other", "Glass Orchard", "t2")
        val asked = mutableListOf<Set<String>>()
        suspend fun leadIn(at: Long, next: Program, extra: Program?) = GuideScheduler.leadIn("c", at, next, ZoneOffset.UTC) { exclude, _ ->
            asked += exclude
            extra
        }
        fun GuideScheduler.LeadIn.shape() = slots.map { Triple(it.itemKey, it.endMillis - it.startMillis, it.title) }

        // 8:41 + 2 is 8:43, so the film starts at 8:45: 4 minutes, one trailer.
        val one = leadIn(end, film("next", "Paper Harbor", "t1"), other)
        assertEquals(end + 4 * MINUTE, one.start)
        assertEquals(listOf(Triple("t1", 4 * MINUTE, "Trailer: Paper Harbor")), one.shape())
        assertEquals("/art/next", one.slots.single().art)

        // 8:44 + 2 is 8:46, so 8:50: 6 minutes, two trailers of 3.
        val two = leadIn(end + 3 * MINUTE, film("next", "Paper Harbor", "t1"), other)
        assertEquals(end + 9 * MINUTE, two.start)
        assertEquals(listOf(Triple("t1", 3 * MINUTE, "Trailer: Paper Harbor"), Triple("t2", 3 * MINUTE, "Trailer: Glass Orchard")), two.shape())
        assertTrue("another film's trailer is never this film's", "next" in asked.last())

        // No trailer of its own: another film's fills the gap.
        assertEquals(listOf("t2"), leadIn(end, film("next", "Paper Harbor", null), other).slots.map { it.itemKey })

        // Nothing to show: the film starts straight away.
        val none = leadIn(end, film("next", "Paper Harbor", null), null)
        assertEquals(end, none.start)
        assertTrue(none.slots.isEmpty())

        // An episode runs straight on.
        val episode = Program("e2", 22 * MINUTE, "Paper Rockets", "S1 E2 · Lift", "toon", null, "TV-Y7", null, null)
        val straight = leadIn(end, episode, other)
        assertEquals(end, straight.start)
        assertTrue(straight.slots.isEmpty())
    }

    @Test
    fun `start times and filler saved before G21 aren't read any more`() = runTest {
        harness.synced(films)
        harness.channels(moviesChannel.copy(filler = ChannelEntity.FILLER_UP_NEXT, alignmentMinutes = 30))
        harness.engine.extend()

        val slots = harness.dao.slots("movies")
        assertTrue(slots.none { it.kind == SlotEntity.UP_NEXT })
        assertTrue(slots.any { it.kind == SlotEntity.TRAILER })
        assertTrue(slots.filter { it.kind == SlotEntity.PROGRAM }.any { it.startMillis % (30 * MINUTE) != 0L })
    }

    @Test
    fun `a schedule that ends between start times leads in with trailers`() = runTest {
        harness.synced(films)
        // As a rewrite leaves it (G16): 8:48 PM, after the program on now.
        harness.channels(moviesChannel.copy(scheduledUntil = WEEKNIGHT + 7 * MINUTE))
        harness.engine.extend()

        val slots = harness.dao.slots("movies")
        assertEquals(WEEKNIGHT + 7 * MINUTE, slots.first().startMillis)
        assertEquals(SlotEntity.TRAILER, slots.first().kind)
        assertEquals("8:50 PM", WEEKNIGHT + 9 * MINUTE, slots.first { it.kind == SlotEntity.PROGRAM }.startMillis)
        assertContiguous(slots)
    }

    @Test
    fun `episodes run straight on, with nothing between them`() = runTest {
        harness.synced(series("toon", "Paper Rockets", listOf("Animation"), seasons = 10, perSeason = 15, minutes = 11))
        harness.channels(channel("toons", ChannelRule(types = listOf(EPISODE), shows = listOf("toon"), order = ChannelRule.Order.IN_ORDER), alignment = 15))
        harness.engine.extend()

        val slots = harness.dao.slots("toons")
        assertContiguous(slots)
        assertEquals(WEEKNIGHT - MINUTE, slots.first().startMillis)
        assertTrue(slots.all { it.kind == SlotEntity.PROGRAM })
        // 8:40 PM, every 11 minutes, until a program starts past 8:41 PM tomorrow.
        assertEquals(131, slots.size)
    }

    @Test
    fun `a schedule with Up next cards to come is laid again from the end of the program on now`() = runTest {
        harness.synced(films)
        val halfPast = WEEKNIGHT - 11 * MINUTE
        fun program(key: String, start: Long) = films.first { it.ratingKey == key }.let {
            SlotEntity("movies", start, start + it.durationMs!!, SlotEntity.PROGRAM, key, it.title, null, null, null, null, null)
        }
        fun upNext(start: Long, end: Long) = SlotEntity("movies", start, end, SlotEntity.UP_NEXT, null, "Up next", null, null, null, null, null)
        // As 2.7 wrote it: films on the half hour, Up next cards between, f04 drawn for the last card.
        val onNow = program("f01", halfPast)
        val second = program("f02", halfPast + 90 * MINUTE)
        val third = program("f03", halfPast + 210 * MINUTE)
        val old = listOf(onNow, upNext(onNow.endMillis, second.startMillis), second, upNext(second.endMillis, third.startMillis), third, upNext(third.endMillis, halfPast + 330 * MINUTE))
        harness.channels(
            moviesChannel.copy(
                scheduledUntil = old.last().endMillis,
                cursor = ChannelCursor(played = listOf("f01", "f02", "f03", "f04"), last = "f04", pending = "f04").toJson(),
            ),
        )
        harness.dao.insertSlots(old)

        harness.engine.extend()

        val slots = harness.dao.slots("movies")
        assertEquals("the program on now stays as it was", onNow, slots.first())
        assertTrue(slots.none { it.kind == SlotEntity.UP_NEXT })
        assertContiguous(slots)
        assertEquals(
            "the same programs in the same order, then the cursor's next",
            listOf("f01", "f02", "f03", "f04"),
            slots.filter { it.kind == SlotEntity.PROGRAM }.take(4).map { it.itemKey },
        )
        assertTrue(slots.last().endMillis >= WEEKNIGHT + 24 * HOUR)
        assertTrue(harness.logs.any { it.startsWith("guide: channel 1 laid again with trailers") })
    }

    @Test
    fun `the half hour is the local clock's`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val at = WEEKNIGHT
        val floor = GuideTime.floor(at, 30, kolkata)
        assertEquals(0, java.time.Instant.ofEpochMilli(floor).atZone(kolkata).minute % 30)
        assertEquals(floor + 30 * MINUTE, GuideTime.ceil(at, 30, kolkata))
        assertEquals(at - 11 * MINUTE, GuideTime.floor(at, 30, ZoneOffset.UTC))
        assertEquals(at - 11 * MINUTE, GuideTime.ceil(at - 11 * MINUTE, 30, ZoneOffset.UTC))
    }

    @Test
    fun `the same library and seed give the same schedule`() = runTest {
        harness.synced(films)
        harness.channels(moviesChannel)
        harness.engine.extend()

        GuideHarness(plex = harness.plex).use { other ->
            other.channels(moviesChannel)
            other.engine.extend()
            assertEquals(harness.dao.slots("movies"), other.dao.slots("movies"))
        }
        GuideHarness(plex = harness.plex).use { other ->
            other.channels(moviesChannel.copy(seed = moviesChannel.seed + 1))
            other.engine.extend()
            assertNotEquals("another seed, another order", harness.programs("movies").map { it.itemKey }, other.programs("movies").map { it.itemKey })
        }
    }

    @Test
    fun `the schedule grows at its end and never rewrites what's scheduled`() = runTest {
        harness.synced(films)
        harness.channels(moviesChannel)
        harness.engine.extend()
        val first = harness.dao.slots("movies")
        val pending = ChannelCursor.fromJson(harness.dao.channels().single().cursor).pending

        harness.engine.extend()
        assertEquals("already covered: nothing changes", first, harness.dao.slots("movies"))

        harness.now += 5 * HOUR
        harness.engine.extend()
        val second = harness.dao.slots("movies")
        assertEquals("what was scheduled is still there, unchanged", first, second.take(first.size))
        assertTrue(second.size > first.size)
        assertTrue(second.last().endMillis >= harness.now + 24 * HOUR)
        assertContiguous(second)
        assertEquals("the program drawn last time comes first", pending, harness.programs("movies").first { it.startMillis >= first.last().endMillis }.itemKey)

        harness.now += 7 * HOUR
        harness.engine.extend()
        assertTrue("slots over 6 hours old are pruned", harness.dao.slots("movies").all { it.endMillis >= harness.now - 6 * HOUR })
    }

    @Test
    fun `after days away, the schedule starts again at the current 5-minute mark`() = runTest {
        harness.synced(films)
        harness.channels(moviesChannel)
        harness.engine.extend()

        harness.now += 3 * 24 * HOUR + 7 * MINUTE
        harness.engine.extend()
        val slots = harness.dao.slots("movies")
        assertEquals(harness.now - 3 * MINUTE, slots.first().startMillis)
        assertContiguous(slots)
    }

    @Test
    fun `a shuffle plays every movie before any repeats`() = runTest {
        harness.synced(films)
        harness.channels(moviesChannel)
        // Three rounds of 20 films, about two hours each.
        repeat(8) {
            harness.extendAndRecord("movies")
            harness.now += 20 * HOUR
        }
        val order = harness.seen.values.map { it.itemKey!! }
        assertTrue(order.size >= 60)
        order.chunked(films.size).filter { it.size == films.size }.forEach { round -> assertEquals(films.size, round.toSet().size) }
        assertTrue("never the same film twice in a row", order.zipWithNext().none { (a, b) -> a == b })
    }

    @Test
    fun `a new film joins the shuffle without waiting for the next round`() = runTest {
        harness.synced(films)
        harness.channels(moviesChannel)
        harness.extendAndRecord("movies")
        harness.now += 12 * HOUR
        harness.synced(listOf(movie("f99", "Late Arrival", 100)))
        repeat(2) {
            harness.extendAndRecord("movies")
            harness.now += 20 * HOUR
        }
        val order = harness.seen.values.map { it.itemKey!! }
        assertTrue("f99" in order.take(21))
    }

    @Test
    fun `a marathon plays its show in order and then starts again`() = runTest {
        harness.synced(series("ridge", "Ridge Line", listOf("Drama"), seasons = 2, perSeason = 3, minutes = 50))
        harness.channels(channel("marathon", ChannelRule(types = listOf(EPISODE), shows = listOf("ridge"), order = ChannelRule.Order.IN_ORDER)))
        harness.engine.extend()

        val keys = harness.programs("marathon").map { it.itemKey }
        val season = listOf("ridge-s01e01", "ridge-s01e02", "ridge-s01e03", "ridge-s02e01", "ridge-s02e02", "ridge-s02e03")
        assertEquals(season + season, keys.take(12))
        val first = harness.programs("marathon").first()
        assertEquals("Ridge Line", first.title)
        assertEquals("S1 E1 · Episode 1", first.episode)
        assertEquals("ridge", first.showKey)
    }

    @Test
    fun `rotating shows take turns, each show in order`() = runTest {
        harness.synced(
            series("a", "Night Shift", listOf("Drama"), seasons = 1, perSeason = 20, minutes = 44) +
                series("b", "Ridge Line", listOf("Drama"), seasons = 1, perSeason = 20, minutes = 52) +
                series("c", "Kitchen Table", listOf("Comedy"), seasons = 1, perSeason = 20, minutes = 22),
        )
        harness.channels(channel("mix", ChannelRule(types = listOf(EPISODE), order = ChannelRule.Order.ROTATE_SHOWS)))
        harness.engine.extend()

        val programs = harness.programs("mix")
        programs.chunked(3).filter { it.size == 3 }.forEach { round -> assertEquals(3, round.map { it.showKey }.toSet().size) }
        for (show in listOf("a", "b", "c")) {
            val episodes = programs.filter { it.showKey == show }.map { it.itemKey!! }
            assertEquals(episodes.sorted(), episodes)
            assertEquals("$show-s01e01", episodes.first())
        }
    }

    @Test
    fun `a rule that matches nothing schedules nothing`() = runTest {
        harness.synced(films)
        harness.channels(channel("empty", ChannelRule(genres = listOf("Western"))), channel("movies", ChannelRule(), number = 2))
        harness.engine.extend()

        assertEquals(emptyList<SlotEntity>(), harness.dao.slots("empty"))
        assertTrue(harness.dao.slots("movies").isNotEmpty())
    }

    @Test
    fun `a channel with a broken rule doesn't stop the others`() = runTest {
        harness.synced(films)
        harness.channels(channel("movies", ChannelRule()).copy(rule = "not json", number = 1), channel("films", ChannelRule(), number = 2))
        harness.engine.extend()

        assertTrue(harness.dao.slots("films").isNotEmpty())
        assertTrue(harness.logs.any { "channel 1 not extended" in it })
    }
}
