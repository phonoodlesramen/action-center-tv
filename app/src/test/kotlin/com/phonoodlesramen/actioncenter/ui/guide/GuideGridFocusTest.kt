package com.phonoodlesramen.actioncenter.ui.guide

import com.phonoodlesramen.actioncenter.guide.GuideChannel
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The full guide's paging and focus by time (plan §5), on the sample lineup at 8:41 PM. */
class GuideGridFocusTest {
    private val now = SampleGuide.WEEKNIGHT
    private val channels = SampleGuide.ready().channels
    private val lineup = GuideLineup(channels, now, ZoneOffset.UTC)

    /** 26 October 2026 (+[days]) at [hour]:[minute] UTC, as the sample. */
    private fun at(hour: Int, minute: Int, days: Long = 0) =
        LocalDateTime.of(2026, 10, 26, hour, minute).plusDays(days).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun channel(id: String) = channels.first { it.id == id }

    private fun GuideFocus.go(vararg moves: GuideMove, on: GuideLineup = lineup) = moves.fold(this) { focus, move -> on.move(focus, move) }

    private fun GuideFocus.repeat(move: GuideMove, times: Int) = (1..times).fold(this) { focus, _ -> lineup.move(focus, move) }

    private fun assertOn(focus: GuideFocus, channelId: String, start: Long) {
        assertEquals(channelId, focus.channelId)
        assertEquals(start, focus.slotStart)
    }

    @Test
    fun `opens at now on the first channel, in the half hour's window`() {
        val focus = lineup.open(now, null)
        assertOn(focus, "movies", channel("movies").slotAt(now)!!.startMillis)
        assertEquals(at(20, 30), focus.windowStart)
        assertEquals(now, focus.anchor)
        assertEquals(0, focus.topRow)
    }

    @Test
    fun `opens on the channel it's asked for`() {
        val focus = lineup.open(now, "comedy")
        assertOn(focus, "comedy", channel("comedy").slotAt(now)!!.startMillis)
    }

    @Test
    fun `Up and Down keep the time, not the column`() {
        // Right to the trailer at 10:41 pages the window to 10:30, and 10:41 is the time to keep.
        val start = lineup.open(now, null).go(GuideMove.RIGHT)
        assertEquals(at(22, 41), start.anchor)
        var focus = start
        for (id in listOf("scifi", "tv-mix", "classics", "comedy", "marathon-ridge-line")) {
            focus = lineup.move(focus, GuideMove.DOWN)
            assertOn(focus, id, channel(id).slotAt(at(22, 41))!!.startMillis)
            assertEquals(at(22, 41), focus.anchor)
        }
        // Sci-Fi's film started at 9:00, long before the window: going back up still lands on 10:41.
        focus = focus.repeat(GuideMove.UP, 5)
        assertEquals(start, focus)
    }

    @Test
    fun `Right moves along the row, and past the window's end pages forward`() {
        val open = lineup.open(now, null)
        val trailer = lineup.move(open, GuideMove.RIGHT)
        assertOn(trailer, "movies", at(22, 41))
        assertEquals(at(22, 30), trailer.windowStart)

        // The next film starts inside the new window: no page.
        val film = lineup.move(trailer, GuideMove.RIGHT)
        assertOn(film, "movies", at(23, 0))
        assertEquals(at(22, 30), film.windowStart)
        assertEquals(at(23, 0), film.anchor)
    }

    @Test
    fun `Left before the window's start pages back so the program ends at its right`() {
        val back = lineup.move(lineup.open(now, null), GuideMove.LEFT)
        // The trailer before Quiet Field, 8:06-8:30: the window becomes 7:00-8:30.
        assertOn(back, "movies", at(20, 6))
        assertEquals(at(19, 0), back.windowStart)
        assertEquals(at(20, 6), back.anchor)
    }

    @Test
    fun `Left stops at the earliest slot, about now minus 2 hours`() {
        assertEquals(at(18, 30), lineup.firstWindow)
        val first = lineup.open(now, null).repeat(GuideMove.LEFT, 20)
        assertOn(first, "movies", channel("movies").slots.first().startMillis)
        assertEquals(at(18, 30), first.windowStart)
        assertEquals(first, lineup.move(first, GuideMove.LEFT))
    }

    @Test
    fun `Right stops at the end of the 24 hours`() {
        // The half hour after 8:41 PM tomorrow is 9:00 PM: the last window is 7:30-9:00 PM.
        assertEquals(at(19, 30, days = 1), lineup.lastWindow)
        val last = lineup.open(now, null).repeat(GuideMove.RIGHT, 100)
        assertEquals(at(19, 30, days = 1), last.windowStart)
        assertTrue(last.slotStart < at(21, 0, days = 1))
        assertEquals(last, lineup.move(last, GuideMove.RIGHT))
        // The sample runs to 9:41 PM tomorrow: there is a next slot, it's just past the 24 hours.
        val movies = channel("movies").slots
        assertTrue(movies[movies.indexOfFirst { it.startMillis == last.slotStart } + 1].startMillis >= at(21, 0, days = 1))
    }

    @Test
    fun `at the end of a channel's schedule, Right pages on to show the rest of its last program`() {
        val last = channel("movies").slotAt(now)!!.copy(endMillis = at(1, 0, days = 1))
        val short = GuideLineup(listOf(channel("movies").copy(slots = listOf(last))), now, ZoneOffset.UTC)
        val paged = short.move(short.open(now, null), GuideMove.RIGHT)
        assertOn(paged, "movies", last.startMillis)
        assertEquals(at(23, 30), paged.windowStart)
        assertEquals(at(23, 30), paged.anchor)
        assertEquals(paged, short.move(paged, GuideMove.RIGHT))
    }

    @Test
    fun `the rows scroll a channel at a time past the bottom and the top`() {
        val twelve = channels + channels.map { it.copy(id = "${it.id}-2", number = it.number + 6) }
        val many = GuideLineup(twelve, now, ZoneOffset.UTC)
        var focus = many.open(now, null)
        repeat(7) { focus = many.move(focus, GuideMove.DOWN) }
        assertEquals("scifi-2", focus.channelId)
        assertEquals(2, focus.topRow)
        repeat(6) { focus = many.move(focus, GuideMove.UP) }
        assertEquals("scifi", focus.channelId)
        assertEquals(1, focus.topRow)
    }

    @Test
    fun `a channel with nothing in the window is skipped`() {
        val withGap = channels.take(1) + GuideChannel("empty", 9, "Empty", null, emptyList()) + channels.drop(1)
        val gapped = GuideLineup(withGap, now, ZoneOffset.UTC)
        val down = gapped.move(gapped.open(now, null), GuideMove.DOWN)
        assertEquals("scifi", down.channelId)
    }

    @Test
    fun `opening on a channel deep in the lineup scrolls it into view`() {
        val twelve = channels + channels.map { it.copy(id = "${it.id}-2", number = it.number + 6) }
        val focus = GuideLineup(twelve, now, ZoneOffset.UTC).open(now, "comedy-2")
        assertEquals("comedy-2", focus.channelId)
        assertEquals(6, focus.topRow)
    }

    @Test
    fun `a program that leaves the lineup hands focus to the same time on the same row`() {
        val focus = lineup.open(now, "tv-mix").go(GuideMove.RIGHT)
        val gone = channels.map { channel -> channel.copy(slots = channel.slots.filterNot { it.startMillis == focus.slotStart }) }
        val resolved = GuideLineup(gone, now, ZoneOffset.UTC).resolve(focus)
        assertEquals("tv-mix", resolved.channelId)
        assertEquals(focus.windowStart, resolved.windowStart)
        // The nearest program to the kept time, now that its own slot is gone.
        assertTrue(resolved.slotStart != focus.slotStart)
    }

    @Test
    fun `the past goes back no further than 2 hours, however much the cache keeps`() {
        val older = SampleGuide.ready(now = now - 3 * 3_600_000L).channels
        val lineup = GuideLineup(older, now, ZoneOffset.UTC)
        assertEquals(at(18, 30), lineup.firstWindow)
    }

    @Test
    fun `nothing to focus without slots`() {
        val empty = GuideLineup(listOf(GuideChannel("empty", 1, "Empty", null, emptyList())), now, ZoneOffset.UTC)
        val focus = empty.open(now, null)
        assertNull(focus.channelId)
        assertEquals(focus, empty.move(focus, GuideMove.DOWN))
        assertNull(empty.focused(focus))
    }

    @Test
    fun `monograms take the first letters of two words`() {
        assertEquals("M", monogram("Movies"))
        assertEquals("SF", monogram("Sci-Fi"))
        assertEquals("RL", monogram("Ridge Line Marathon"))
        assertEquals("#", monogram(" "))
    }
}
