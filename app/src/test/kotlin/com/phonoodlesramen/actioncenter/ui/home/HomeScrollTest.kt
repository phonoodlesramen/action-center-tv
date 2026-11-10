package com.phonoodlesramen.actioncenter.ui.home

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.ui.home.rows.MarginBringIntoViewSpec
import com.phonoodlesramen.actioncenter.ui.home.rows.appsRowOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the home scrolls (spec §3), in dp at 100% on the 960×540 canvas: the dashboard
 * ends at 483, Row A starts at 87 and Row B's gap at 311. Each row is 16 above, a 16 dp
 * label, 8, then 90 dp cards: 130 in all.
 */
class HomeScrollTest {
    private val geometry = DashboardGeometry(widthPx = 960, viewportPx = 540)
    private val stops = geometry.stops(rowLead = 40f, spaceAbove = 16f, cardHeight = 90f, bottomRoom = 8f)

    /** Row n's cards (0 is the first row below the dashboard). */
    private fun row(n: Int): Pair<Float, Float> {
        val top = 483f + n * 130f + 40f
        return top to top + 90f
    }

    private fun target(card: Pair<Float, Float>) = homeScrollTarget(card.first, card.second, 540f, stops)

    @Test
    fun `the dashboard leaves 57 dp for the first row to peek into`() {
        assertEquals(27, geometry.top)
        assertEquals(456, geometry.content)
        assertEquals(483, geometry.bottom)
        assertEquals(listOf(87f, 311f), stops.dashboard)
    }

    @Test
    fun `anything in the dashboard scrolls back to the top`() {
        assertEquals(0f, homeScrollTarget(355f, 442f, 540f, stops))
        assertEquals(0f, homeScrollTarget(27f, 71f, 540f, stops))
    }

    @Test
    fun `the first row brings Row A to the top edge, with room under the cards`() {
        assertEquals(87f, target(row(0)))
        val (_, bottom) = row(0)
        assertEquals(14f, 540f - (bottom - 87f))
    }

    @Test
    fun `the second row shows Row B a gap below the top, and the first row above it`() {
        assertEquals(311f, target(row(1)))
    }

    @Test
    fun `the third row puts the first row's top at the top edge`() {
        assertEquals(483f, target(row(2)))
    }

    @Test
    fun `a whole row asking to be seen lands where its card does`() {
        for (n in 0..4) {
            val (cardTop, bottom) = row(n)
            assertEquals(target(row(n)), homeScrollTarget(cardTop - 24f, bottom, 540f, stops))
        }
    }

    @Test
    fun `further rows put their own top at the top edge`() {
        val (top, _) = row(4)
        assertEquals(top - 40f, target(row(4)))
    }

    @Test
    fun `a row keeps the margin from both ends of the row, moving as little as possible`() {
        val spec = MarginBringIntoViewSpec(margin = 48f)
        // Cards 1-5 fill the 864 dp between the margins: nothing moves.
        assertEquals(0f, spec.calculateScrollDistance(offset = 48f, size = 160f, containerSize = 960f))
        assertEquals(0f, spec.calculateScrollDistance(offset = 752f, size = 160f, containerSize = 960f))
        // Card 6 moves the row one card and gap, so it stays on the grid.
        assertEquals(176f, spec.calculateScrollDistance(offset = 928f, size = 160f, containerSize = 960f))
        // Back to a card under the left margin.
        assertEquals(-176f, spec.calculateScrollDistance(offset = -128f, size = 160f, containerSize = 960f))
    }

    @Test
    fun `the apps row is the launchpad, then every other app A-Z, without hidden apps`() {
        fun app(label: String) = LaunchableApp("pkg.$label", "pkg.$label.Main", label)
        val all = listOf("Disney+", "Kodi", "Netflix", "Plex", "Plezy", "YouTube").map(::app)
        val launchpad = listOf("Plezy", "YouTube", "Netflix").map(::app)
        val hidden = setOf("pkg.Kodi/pkg.Kodi.Main")
        assertEquals(
            listOf("Plezy", "YouTube", "Netflix", "Disney+", "Plex"),
            appsRowOrder(launchpad, all, hidden).map { it.label },
        )
    }
}
