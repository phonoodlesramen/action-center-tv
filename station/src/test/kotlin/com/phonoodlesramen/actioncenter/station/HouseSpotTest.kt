package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.station.SpotBody
import com.phonoodlesramen.actioncenter.guide.station.SpotKey
import com.phonoodlesramen.actioncenter.guide.station.StationSpot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The house's spot across two TVs: the rules the TVs keep alone today, now kept once for both. */
class HouseSpotTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = START
    private val livingRoom = PairedDevice("tv-111111111111", "Living room", "SHIELD Android TV", "1", START)
    private val kitchen = PairedDevice("tv-222222222222", "Kitchen", "Google TV Streamer", "2", START)

    /** The schedule the spot is checked against, by channel and start. */
    private val schedule = mutableMapOf<Pair<String, Long>, GuideSlot>()
    private lateinit var versions: Versions
    private lateinit var spot: HouseSpot

    @Before
    fun setUp() {
        versions = Versions(temp.root.toPath().resolve("version"))
        schedule["c-comedy" to SLOT] = program("5101")
        spot = spotStore()
    }

    private fun spotStore() = HouseSpot(temp.root.toPath().resolve("spot.json"), versions, { channel, start -> schedule[channel to start] }, { now })

    private fun program(key: String, filler: Boolean = false, offAir: String? = null) =
        GuideSlot(SLOT, SLOT + 30 * 60_000L, "Made-up Show", "S1 · E1", key, "4800", null, "TV-PG", filler, offAir)

    private fun body(channel: String = "c-comedy", item: String = "5101", position: Long = 600_000L) =
        SpotBody(channel, SLOT, item, position, now)

    @Test
    fun `a spot saved in one room is the house's, with that room`() = runTest {
        val before = versions.current
        val version = spot.put(body(), livingRoom)
        assertEquals(before + 1, version)
        assertEquals(
            StationSpot("c-comedy", SLOT, "5101", 600_000L, START, device = livingRoom.id, room = "Living room"),
            spot.current(),
        )
    }

    @Test
    fun `the latest save from any TV wins`() = runTest {
        spot.put(body(position = 100_000L), livingRoom)
        now += 60_000L
        spot.put(body(position = 900_000L), kitchen)
        val current = spot.current()!!
        assertEquals("Kitchen", current.room)
        assertEquals(900_000L, current.positionMs)
    }

    @Test
    fun `another channel played forgets it only on the TV that saved it`() = runTest {
        spot.put(body(), livingRoom)
        val saved = versions.current

        assertEquals(saved, spot.played("c-drama", kitchen))
        assertEquals("Living room", spot.current()?.room)

        assertEquals(saved, spot.played("c-comedy", livingRoom))
        assertEquals("Living room", spot.current()?.room)

        assertEquals(saved + 1, spot.played("c-drama", livingRoom))
        assertNull(spot.current())
    }

    @Test
    fun `forgetting with only forgets it while it's still that one`() = runTest {
        spot.put(body(), livingRoom)
        val saved = versions.current
        assertEquals(saved, spot.forget(SpotKey("c-comedy", SLOT, "9999")))
        assertEquals(saved, spot.forget(SpotKey("c-drama", SLOT, "5101")))
        assertEquals("Living room", spot.current()?.room)
        assertEquals(saved + 1, spot.forget(SpotKey("c-comedy", SLOT, "5101")))
        assertNull(spot.current())
        assertEquals(saved + 1, spot.forget(null))
    }

    @Test
    fun `forgetting without only forgets any spot, from any TV`() = runTest {
        spot.put(body(), livingRoom)
        spot.forget(null)
        assertNull(spot.current())
    }

    @Test
    fun `it holds for 4 hours`() = runTest {
        spot.put(body(), livingRoom)
        now += HouseSpot.KEEP_MILLIS - 1
        assertEquals("Living room", spot.current()?.room)
        now += 1
        assertNull(spot.current())
        // And it's gone, not just hidden.
        now -= 1
        assertNull(spot.current())
    }

    @Test
    fun `a TV whose clock is far ahead can't keep a spot past 4 hours of the station's`() = runTest {
        spot.put(body().copy(savedAtMillis = now + 24 * 60 * 60_000L), livingRoom)
        assertEquals("Living room", spot.current()?.room)
        now += HouseSpot.KEEP_MILLIS
        assertNull(spot.current())
    }

    @Test
    fun `it holds only while its program is still in the schedule with that item, with a file, and not filler`() = runTest {
        spot.put(body(), livingRoom)
        schedule.remove("c-comedy" to SLOT)
        assertNull(spot.current())
        schedule["c-comedy" to SLOT] = program("6000")
        assertNull(spot.current())
        schedule["c-comedy" to SLOT] = program("5101", offAir = "This title is no longer in your library")
        assertNull(spot.current())
        schedule["c-comedy" to SLOT] = program("5101", filler = true)
        assertNull(spot.current())
        schedule["c-comedy" to SLOT] = program("5101")
        assertEquals("Living room", spot.current()?.room)
    }

    @Test
    fun `it survives a restart`() = runTest {
        spot.put(body(), kitchen)
        assertEquals("Kitchen", spotStore().current()?.room)
    }

    @Test
    fun `a spot that names nothing is refused`() {
        for (bad in listOf(body(channel = " "), body(item = ""), body(position = -1))) {
            try {
                spot.put(bad, livingRoom)
                fail("took $bad")
            } catch (e: ApiException) {
                assertEquals(400, e.status)
            }
        }
    }

    private companion object {
        const val START = 1_793_047_260_000L
        const val SLOT = 1_793_046_600_000L
    }
}
