package com.phonoodlesramen.actioncenter.ui.home.rows

import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a guide channel's card in the Live row shows (plan §6), on the sample lineup at the
 * sample weeknight, 8:41 PM, and later the same night: the schedule never changes, the
 * time moves.
 */
class LiveNowTest {
    private val minute = 60_000L
    private val now = SampleGuide.WEEKNIGHT
    private val lineup = SampleGuide.ready()
    private val clock = LiveClock(SampleHomeState.weeknight, now)

    private fun channel(id: String) = lineup.channels.single { it.id == id }

    private fun at(id: String, millis: Long = now) = LiveNow.of(channel(id), millis)

    @Test
    fun `a program on now has its progress and time left`() {
        val movies = at("movies") as LiveNow.OnNow

        assertEquals("Quiet Field", movies.title)
        assertEquals(11f / 131, movies.progress, 0.001f)
        assertEquals(120, movies.minutesLeft)
        assertEquals("2h left", movies.detail(clock))
    }

    @Test
    fun `an episode shows its time left first, then the episode`() {
        assertEquals("41m left · S3 E1 · Thaw", at("tv-mix").detail(clock))
        assertEquals("Ridge Line", at("tv-mix").title)
    }

    @Test
    fun `filler shows the next program, up next at its start`() {
        // Sci-Fi's 8:31-9:00 is a trailer.
        val scifi = at("scifi") as LiveNow.UpNext

        assertEquals("Orbit of Glass", scifi.title)
        assertEquals("Up next at 9:00", scifi.detail(clock))
        assertEquals(scifi.slot, scifi.program)
    }

    @Test
    fun `off air says when the channel is back, and why`() {
        // Classics' 12:30-2:11 AM program is no longer in the library; a trailer follows it, then 2:30.
        val offAir = at("classics", now + (4 * 60 + 4) * minute) as LiveNow.OffAir

        assertEquals("Off air", offAir.title)
        assertEquals("Back at 2:30", offAir.detail(clock))
        assertEquals("This title is no longer in your library", offAir.why)
        assertEquals(null, offAir.program)
    }

    @Test
    fun `the card follows the minute tick`() {
        assertEquals("1h 59m left", at("movies", now + minute).detail(clock))
        val nine = at("scifi", now + 19 * minute) as LiveNow.OnNow
        assertEquals("Orbit of Glass", nine.title)
        assertEquals(0f, nine.progress, 0.001f)
        assertEquals("2h 22m left", nine.detail(clock))
    }

    @Test
    fun `a gap shows what's next, and past the schedule it's off air`() {
        val a = slot(now - 30 * minute, now - 10 * minute, "A")
        val b = slot(now + 20 * minute, now + 50 * minute, "B")
        val channel = GuideChannel("c", 7, "Gaps", null, listOf(a, b))

        assertEquals(LiveNow.UpNext(b), LiveNow.of(channel, now))
        assertEquals(LiveNow.OffAir(why = null, backMillis = null), LiveNow.of(channel, now + 60 * minute))
        assertEquals("Nothing scheduled yet", LiveNow.of(channel, now + 60 * minute).detail(clock))
    }

    @Test
    fun `filler before a program that's off air skips to the next that plays`() {
        val filler = slot(now - 5 * minute, now + 10 * minute, "Up next", filler = true)
        val gone = slot(now + 10 * minute, now + 40 * minute, "Gone", offAir = "This title is no longer in your library")
        val next = slot(now + 40 * minute, now + 70 * minute, "Next")
        val channel = GuideChannel("c", 7, "Skips", null, listOf(filler, gone, next))

        assertEquals(LiveNow.UpNext(next), LiveNow.of(channel, now))
    }

    @Test
    fun `time left reads as Up Next does, with hours from an hour up`() {
        assertEquals("1m left", timeLeft(1))
        assertEquals("59m left", timeLeft(59))
        assertEquals("1h left", timeLeft(60))
        assertEquals("1h 44m left", timeLeft(104))
    }

    @Test
    fun `times read on the home's own clock, whatever its zone`() {
        assertEquals("9:00", clock.timeOf(now + 19 * minute))
        // The same evening on a clock four hours behind UTC: 8:41 PM there is 00:41 UTC.
        val eastern = LiveClock(SampleHomeState.weeknight, now + 4 * 60 * minute)
        assertEquals("9:00", eastern.timeOf(now + 4 * 60 * minute + 19 * minute))
        assertEquals("12:30", clock.timeOf(now + (3 * 60 + 49) * minute))
    }

    @Test
    fun `every sample channel has something to show all evening`() {
        for (step in 0..(6 * 60) step 7) {
            for (channel in lineup.channels) {
                val shown = LiveNow.of(channel, now + step * minute)
                assertTrue("${channel.name} at +$step min: $shown", shown.detail(clock).isNotBlank())
            }
        }
    }

    private fun slot(start: Long, end: Long, title: String, filler: Boolean = false, offAir: String? = null) =
        GuideSlot(start, end, title, null, "key-$title", null, null, null, filler = filler, offAir = offAir)
}
