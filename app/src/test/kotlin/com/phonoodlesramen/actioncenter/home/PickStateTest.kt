package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.content.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickStateTest {
    private val picks = listOf(
        Pick("a", "Severance", "", ""),
        Pick("b", "Ocean's Eleven", "", ""),
        Pick("c", "Arrival", "", ""),
    )

    @Test
    fun `not tonight shows the next pick and offers undo for 8 seconds`() {
        val state = PickState().notTonight(picks, nowMillis = 1_000)

        assertEquals("b", state.shownId)
        assertEquals(PickState.Undo(skippedTitle = "Severance", untilMillis = 9_000, previousId = "a"), state.undo)
    }

    @Test
    fun `undo inside the window brings the skipped pick back`() {
        val state = PickState().notTonight(picks, nowMillis = 1_000).undo(nowMillis = 8_999)

        assertEquals(PickState(shownId = "a"), state)
    }

    @Test
    fun `undo after the window only clears it`() {
        val state = PickState().notTonight(picks, nowMillis = 1_000).undo(nowMillis = 9_000)

        assertEquals(PickState(shownId = "b"), state)
    }

    @Test
    fun `the undo window expires after 8 seconds`() {
        val skipped = PickState().notTonight(picks, nowMillis = 1_000)

        assertEquals(skipped, skipped.expire(nowMillis = 8_999))
        assertNull(skipped.expire(nowMillis = 9_000).undo)
    }

    @Test
    fun `skipping the last pick wraps to the first`() {
        val state = PickState(shownId = "c").notTonight(picks, nowMillis = 0)

        assertEquals("a", state.shownId)
    }

    @Test
    fun `with no picks nothing changes`() {
        assertEquals(PickState(), PickState().notTonight(emptyList(), nowMillis = 0))
    }

    @Test
    fun `the shown pick stays when the list is re-ranked, and a pick that's gone falls back to the first`() {
        val skipped = PickState().notTonight(picks, nowMillis = 0)
        // The engine runs again after Not tonight: Severance is gone and the rest move up.
        val reranked = listOf(picks[2], picks[1])

        assertEquals("Ocean's Eleven", skipped.current(reranked)?.title)
        assertEquals("Arrival", PickState(shownId = "gone").current(reranked)?.title)
        assertNull(PickState().current(emptyList()))
    }
}
