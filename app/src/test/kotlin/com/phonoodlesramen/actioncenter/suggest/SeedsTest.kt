package com.phonoodlesramen.actioncenter.suggest

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SeedsTest {
    /** Plex-like keys: consecutive numbers, the case a plain string hash got wrong. */
    private val keys = (4101..4110).map(Int::toString)

    private fun order(day: LocalDate) = keys.sortedBy { Seeds.tieBreak(it, day) }

    @Test
    fun `ties keep one order all day and reshuffle the next`() {
        val today = LocalDate.of(2026, 10, 27)

        assertEquals(order(today), order(today))
        assertNotEquals(order(today), order(today.plusDays(1)))
        assertNotEquals(order(today.plusDays(1)), order(today.plusDays(2)))
    }
}
