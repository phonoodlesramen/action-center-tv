package com.phonoodlesramen.actioncenter.ui.guide

import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import com.phonoodlesramen.actioncenter.ui.home.rows.LiveClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Guide card's "Coming up at 9:30" (plan Q3): which two programs, and how it reads. */
class ComingUpTest {
    private val minute = 60_000L
    private val now = SampleGuide.WEEKNIGHT
    private val lineup = SampleGuide.ready().channels
    private val clock = LiveClock(SampleHomeState.weeknight, now)

    private fun ComingUp.names() = programs.map { (channel, slot) -> "${channel.name}: ${slot.title}" }

    @Test
    fun `at 8-41 the next half hour starts two programs`() {
        val comingUp = ComingUp.of(lineup, now)

        assertEquals(now + 19 * minute, comingUp.atMillis)
        assertEquals("Coming up at 9:00", comingUp.heading(clock))
        assertEquals(listOf("Sci-Fi: Orbit of Glass", "Comedy: Open House"), comingUp.names())
        val (channel, slot) = comingUp.programs.first()
        assertEquals("Sci-Fi", comingUp.meta(channel, slot, clock))
    }

    @Test
    fun `a half hour that starts only one program waits for one that starts two, off air left out`() {
        // 11:48 PM: midnight starts only Comedy. At 12:30 TV Mix and Comedy start, and
        // Classics' program there is off air.
        val comingUp = ComingUp.of(lineup, now + (3 * 60 + 7) * minute)

        assertEquals("Coming up at 12:30", comingUp.heading(clock))
        assertEquals(listOf("TV Mix: Kitchen Table", "Comedy: Desk Duty"), comingUp.names())
    }

    @Test
    fun `filler never shows`() {
        val comingUp = ComingUp.of(lineup, now)
        val shown = comingUp.programs.map { it.second }

        assertEquals(listOf(false, false), shown.map { it.filler })
    }

    @Test
    fun `a lineup that never starts two at once gives its soonest two, each with its time`() {
        val one = GuideChannel(
            "movies", 1, "Movies", null,
            listOf(
                slot(now - 30 * minute, now + 19 * minute, "Now"),
                slot(now + 19 * minute, now + 30 * minute, "Up next", filler = true),
                slot(now + 30 * minute, now + 120 * minute, "Later"),
                slot(now + 120 * minute, now + 200 * minute, "Much later"),
            ),
        )
        val comingUp = ComingUp.of(listOf(one), now)

        assertNull(comingUp.atMillis)
        assertEquals("Coming up next", comingUp.heading(clock))
        assertEquals(listOf("Movies: Later", "Movies: Much later"), comingUp.names())
        val (channel, slot) = comingUp.programs.first()
        assertEquals("9:11 · Movies", comingUp.meta(channel, slot, clock))
    }

    @Test
    fun `nothing ahead says so`() {
        val ended = GuideChannel("movies", 1, "Movies", null, listOf(slot(now - 30 * minute, now + 10 * minute, "Last")))

        assertEquals("Nothing scheduled yet", ComingUp.of(listOf(ended), now).heading(clock))
        assertEquals("Nothing scheduled yet", ComingUp.of(emptyList(), now).heading(clock))
    }

    // Favorites first (Phase 13, C5).

    @Test
    fun `a favorite starting at that time comes first, then the rest by number`() {
        val comingUp = ComingUp.of(lineup, now, favorites = listOf("comedy", "tv-mix"))

        assertEquals("Coming up at 9:00", comingUp.heading(clock))
        assertEquals(listOf("Comedy: Open House", "Sci-Fi: Orbit of Glass"), comingUp.names())
        assertEquals(setOf("comedy"), comingUp.favorites)
    }

    @Test
    fun `the soonest time a favorite starts within the hour leads, even after another`() {
        // 9:00 starts Sci-Fi and Comedy, but TV Mix, the favorite, starts Kitchen Table at 9:30.
        val comingUp = ComingUp.of(lineup, now, favorites = listOf("tv-mix"))

        assertEquals("Coming up at 9:30", comingUp.heading(clock))
        assertEquals(listOf("TV Mix: Kitchen Table", "Comedy: Desk Duty"), comingUp.names())
        assertEquals(setOf("tv-mix"), comingUp.favorites)
    }

    @Test
    fun `favorites at the same time keep the profile's order`() {
        val comingUp = ComingUp.of(lineup, now + 30 * minute, favorites = listOf("marathon-ridge-line", "comedy", "tv-mix"))

        assertEquals("Coming up at 9:30", comingUp.heading(clock))
        assertEquals(listOf("Ridge Line Marathon: Ridge Line", "Comedy: Desk Duty"), comingUp.names())
    }

    @Test
    fun `with no favorite starting within the hour, today's rule`() {
        // Movies' film runs past 10:30: nothing of it starts in the hour.
        val comingUp = ComingUp.of(lineup, now, favorites = listOf("movies"))

        assertEquals("Coming up at 9:00", comingUp.heading(clock))
        assertEquals(listOf("Sci-Fi: Orbit of Glass", "Comedy: Open House"), comingUp.names())
        assertEquals(emptySet<String>(), comingUp.favorites)
    }

    @Test
    fun `a favorite alone at its time is joined by the soonest other, each with its time`() {
        val favorite = GuideChannel("fav", 2, "Westerns", null, listOf(slot(now + 10 * minute, now + 60 * minute, "Dry Creek Pass")))
        val other = GuideChannel("other", 1, "Movies", null, listOf(slot(now + 4 * minute, now + 90 * minute, "Paper Harbor")))
        val comingUp = ComingUp.of(listOf(other, favorite), now, favorites = listOf("fav"))

        assertEquals("Coming up next", comingUp.heading(clock))
        assertEquals(listOf("Westerns: Dry Creek Pass", "Movies: Paper Harbor"), comingUp.names())
        val (channel, slot) = comingUp.programs.first()
        assertEquals("8:51 · Westerns", comingUp.meta(channel, slot, clock))
        assertEquals(setOf("fav"), comingUp.favorites)
    }

    private fun slot(start: Long, end: Long, title: String, filler: Boolean = false) =
        GuideSlot(start, end, title, null, "key-$title", null, null, null, filler = filler, offAir = null)
}
