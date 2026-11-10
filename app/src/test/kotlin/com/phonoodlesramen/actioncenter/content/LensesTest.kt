package com.phonoodlesramen.actioncenter.content

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensesTest {
    // 2026-10-26 is a Monday; 30 October is a Friday, 31 October a Saturday.
    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, month, day, hour, minute)

    private fun pick(id: String) = LensPick(id, id, "meta", "line", seen = false)

    private val picks = mapOf(
        Lens.THROWBACK to listOf(pick("heat"), pick("amelie")),
        Lens.CLASSIC to listOf(pick("jaws"), pick("oceans")),
        Lens.FAMILY to listOf(pick("paddington")),
        Lens.SEASONAL to listOf(pick("hocus")),
        Lens.COMFORT to listOf(pick("budapest")),
    )
    private val all = Lens.entries.toSet()

    private fun ids(slots: List<LensSlot>) = slots.map { it.pick.id }

    /** The same order every pass: Settings order. */
    private fun deck(slots: List<LensSlot>) = LensDeck { slots }

    @Test
    fun `the time of day picks the leading lens`() {
        assertEquals(Lens.THROWBACK, LensSchedule.lead(at(10, 26, 20, 41)))
        assertEquals(Lens.THROWBACK, LensSchedule.lead(at(11, 1, 18)))
        assertEquals(Lens.FAMILY, LensSchedule.lead(at(10, 31, 9)))
        assertEquals(Lens.COMFORT, LensSchedule.lead(at(10, 26, 23, 48)))
        assertEquals(Lens.COMFORT, LensSchedule.lead(at(10, 27, 2)))
        assertEquals(Lens.CLASSIC, LensSchedule.lead(at(10, 26, 14)))
    }

    @Test
    fun `Friday and Saturday evenings are Seasonal in season, else Action`() {
        assertEquals(Lens.SEASONAL, LensSchedule.lead(at(10, 30, 20)))
        assertEquals(Lens.SEASONAL, LensSchedule.lead(at(10, 31, 18)))
        assertEquals(Lens.ACTION, LensSchedule.lead(at(11, 6, 20)))
        assertEquals(Lens.CLASSIC, LensSchedule.lead(at(11, 6, 17, 59)))
    }

    @Test
    fun `seasons are late October and December`() {
        assertNull(Season.at(LocalDate.of(2026, 10, 14)))
        assertEquals(Season.HALLOWEEN, Season.at(LocalDate.of(2026, 10, 15)))
        assertEquals(Season.HALLOWEEN, Season.at(LocalDate.of(2026, 10, 31)))
        assertNull(Season.at(LocalDate.of(2026, 11, 1)))
        assertEquals(Season.HOLIDAYS, Season.at(LocalDate.of(2026, 12, 1)))
        assertEquals(Season.HOLIDAYS, Season.at(LocalDate.of(2026, 12, 31)))
        assertNull(Season.at(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `the rotation starts at the lead and goes round in Settings order`() {
        val slots = LensRotation.slots(picks, all, Lens.FAMILY, Season.HALLOWEEN, tonightsPick = null)

        assertEquals(listOf("paddington", "hocus", "budapest", "heat", "amelie", "jaws", "oceans"), ids(slots))
    }

    @Test
    fun `it never repeats Tonight's Pick`() {
        val slots = LensRotation.slots(picks, all, Lens.CLASSIC, season = null, tonightsPick = "oceans")

        assertEquals(listOf("jaws", "paddington", "budapest", "heat", "amelie"), ids(slots))
    }

    @Test
    fun `lenses that are off, and Seasonal out of season, are skipped`() {
        val slots = LensRotation.slots(picks, all - Lens.THROWBACK - Lens.FAMILY, Lens.THROWBACK, season = null, tonightsPick = null)

        assertEquals(listOf("jaws", "oceans", "budapest"), ids(slots))
    }

    @Test
    fun `Next walks the lens, then runs on into the next one, and wraps`() {
        val slots = LensRotation.slots(picks, setOf(Lens.THROWBACK, Lens.CLASSIC), Lens.THROWBACK, null, null)
        var cursor = LensCursor()

        assertEquals("heat", cursor.current(slots, Lens.THROWBACK)?.pick?.id)
        cursor = cursor.next(deck(slots), Lens.THROWBACK)
        assertEquals("amelie", cursor.current(slots, Lens.THROWBACK)?.pick?.id)
        cursor = cursor.next(deck(slots), Lens.THROWBACK)
        assertEquals(LensSlot(Lens.CLASSIC, pick("jaws")), cursor.current(slots, Lens.THROWBACK))
        cursor = cursor.next(deck(slots), Lens.THROWBACK).next(deck(slots), Lens.THROWBACK)
        assertEquals("heat", cursor.current(slots, Lens.THROWBACK)?.pick?.id)
    }

    @Test
    fun `Another lens jumps to the next lens's first title`() {
        val slots = LensRotation.slots(picks, all, Lens.THROWBACK, null, null)

        val cursor = LensCursor().nextLens(deck(slots), Lens.THROWBACK)

        assertEquals(LensSlot(Lens.CLASSIC, pick("jaws")), cursor.current(slots, Lens.THROWBACK))
        assertEquals(Lens.FAMILY, cursor.nextLens(deck(slots), Lens.THROWBACK).current(slots, Lens.THROWBACK)?.lens)
    }

    @Test
    fun `a lens picked by hand lasts until the time moves to another lead`() {
        val evening = LensRotation.slots(picks, all, Lens.THROWBACK, null, null)
        val chosen = LensCursor().nextLens(deck(evening), Lens.THROWBACK)
        assertEquals(Lens.CLASSIC, chosen.current(evening, Lens.THROWBACK)?.lens)

        val lateNight = LensRotation.slots(picks, all, Lens.COMFORT, null, null)

        assertEquals("budapest", chosen.current(lateNight, Lens.COMFORT)?.pick?.id)
    }

    @Test
    fun `when the shown title drops out (it became Tonight's Pick), the lens stays`() {
        val before = LensRotation.slots(picks, all, Lens.CLASSIC, null, tonightsPick = null)
        val onOceans = LensCursor().next(deck(before), Lens.CLASSIC)
        assertEquals("oceans", onOceans.current(before, Lens.CLASSIC)?.pick?.id)

        val after = LensRotation.slots(picks, all, Lens.CLASSIC, null, tonightsPick = "oceans")

        assertEquals("jaws", onOceans.current(after, Lens.CLASSIC)?.pick?.id)
    }

    private fun lenses(slots: List<LensSlot>) = slots.map { it.lens }

    /** Many titles, unevenly spread, so a shuffle has work to do. */
    private val many = mapOf(
        Lens.THROWBACK to (1..4).map { pick("throwback$it") },
        Lens.CLASSIC to (1..8).map { pick("classic$it") },
        Lens.FAMILY to (1..3).map { pick("family$it") },
        Lens.HIDDEN_GEM to (1..5).map { pick("gem$it") },
    )

    @Test
    fun `Shuffle shows every title once, the lead's best first, and never one lens twice in a row`() {
        val inOrder = LensRotation.slots(many, all, Lens.THROWBACK, null, null)
        for (seed in 0L..200L) {
            val mixed = LensRotation.slots(many, all, Lens.THROWBACK, null, null, shuffle = seed)

            assertEquals(inOrder.toSet(), mixed.toSet())
            assertEquals(inOrder.size, mixed.size)
            assertEquals("throwback1", mixed.first().pick.id)
            assertTrue("seed $seed: ${lenses(mixed)}", lenses(mixed).zipWithNext().none { (a, b) -> a == b })
            // A lens keeps its own order, best first.
            many.forEach { (lens, picks) -> assertEquals(picks.map { it.id }, mixed.filter { it.lens == lens }.map { it.pick.id }) }
        }
    }

    @Test
    fun `the same seed mixes the same way, and another seed differently`() {
        val one = LensRotation.slots(many, all, Lens.THROWBACK, null, null, shuffle = 7)
        val again = LensRotation.slots(many, all, Lens.THROWBACK, null, null, shuffle = 7)
        val orders = (0L..20L).map { ids(LensRotation.slots(many, all, Lens.THROWBACK, null, null, shuffle = it)) }.toSet()

        assertEquals(ids(one), ids(again))
        assertTrue("got ${orders.size} orders", orders.size > 10)
    }

    @Test
    fun `when one lens has more than the rest together, its titles meet only at the end`() {
        val lopsided = mapOf(Lens.CLASSIC to (1..6).map { pick("classic$it") }, Lens.FAMILY to listOf(pick("paddington")))
        val mixed = LensRotation.slots(lopsided, all, Lens.FAMILY, null, null, shuffle = 3)

        assertEquals(listOf(Lens.FAMILY, Lens.CLASSIC), lenses(mixed).take(2))
        assertEquals(7, mixed.size)
    }

    @Test
    fun `each pass is a new shuffle, and Next and Back walk across passes`() {
        val day = LocalDate.of(2026, 11, 2)
        val deck = LensDeck { pass -> LensRotation.slots(many, all, Lens.THROWBACK, null, null, LensRotation.seed(day, pass)) }
        val first = deck.slots(0)
        var cursor = LensCursor()
        repeat(first.size - 1) { cursor = cursor.next(deck, Lens.THROWBACK) }
        assertEquals(first.last(), cursor.current(deck, Lens.THROWBACK))

        cursor = cursor.next(deck, Lens.THROWBACK)

        assertEquals(1, cursor.pass)
        assertEquals(deck.slots(1).first(), cursor.current(deck, Lens.THROWBACK))
        assertTrue((1..5).any { ids(deck.slots(it)) != ids(first) })

        cursor = cursor.previous(deck, Lens.THROWBACK)

        assertEquals(0, cursor.pass)
        assertEquals(first.last(), cursor.current(deck, Lens.THROWBACK))
    }

    @Test
    fun `Back from the very first title is the last pass's last`() {
        val slots = LensRotation.slots(picks, all, Lens.THROWBACK, null, null)

        val back = LensCursor().previous(deck(slots), Lens.THROWBACK)

        assertEquals(slots.last(), back.current(deck(slots), Lens.THROWBACK))
        assertEquals("heat", back.next(deck(slots), Lens.THROWBACK).current(slots, Lens.THROWBACK)?.pick?.id)
    }

    @Test
    fun `the speed's five notches, and no rotation while it's off`() {
        assertEquals(listOf(60, 45, 30, 20, 10), (1..5).map { LensPrefs(speed = it).seconds })
        assertEquals(30, LensPrefs().rotateSeconds)
        assertNull(LensPrefs(rotate = false).rotateSeconds)
        assertEquals(60, LensPrefs(speed = -2).seconds)
        assertEquals(10, LensPrefs(speed = 9).seconds)
    }

    @Test
    fun `nothing to show is null, not a crash`() {
        assertNull(LensCursor().current(emptyList(), Lens.THROWBACK))
        assertEquals(LensCursor(), LensCursor().next(deck(emptyList()), Lens.THROWBACK))
    }
}
