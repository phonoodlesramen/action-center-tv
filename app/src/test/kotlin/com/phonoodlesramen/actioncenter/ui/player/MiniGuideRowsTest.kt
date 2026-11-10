package com.phonoodlesramen.actioncenter.ui.player

import com.phonoodlesramen.actioncenter.guide.GuideChannel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which channels the mini guide's strip shows (G12). */
class MiniGuideRowsTest {
    private fun lineup(count: Int) = (1..count).map { GuideChannel("c$it", it, "Channel $it", null, emptyList()) }

    private fun shown(lineup: List<GuideChannel>, focused: String) = MiniGuideRows.around(lineup, focused).map { it.id }

    @Test
    fun `the picked channel sits in the middle, two either side, in lineup order`() {
        assertEquals(listOf("c2", "c3", "c4", "c5", "c6"), shown(lineup(8), "c4"))
    }

    @Test
    fun `at either end it wraps round, as surfing does`() {
        assertEquals(listOf("c7", "c8", "c1", "c2", "c3"), shown(lineup(8), "c1"))
        assertEquals(listOf("c6", "c7", "c8", "c1", "c2"), shown(lineup(8), "c8"))
    }

    @Test
    fun `a short lineup shows whole, in order`() {
        assertEquals(listOf("c1", "c2", "c3"), shown(lineup(3), "c3"))
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), shown(lineup(5), "c1"))
    }

    @Test
    fun `a channel that's gone shows the strip from the first`() {
        assertEquals(listOf("c5", "c6", "c1", "c2", "c3"), shown(lineup(6), "gone"))
    }
}
