package com.phonoodlesramen.actioncenter.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a hero's logo shares the text column with the reason (Phase 12). Your hero's column
 * is 152 dp at 100% (Row A's 224 less its padding and buttons), so 98 after the eyebrow,
 * meta and paddings; at 115%, about 68.8 (68.75 here, exact in floats). The kids' is 88,
 * and 60 at 115%.
 */
class HeroLogoFitTest {
    private fun yours(budget: Float, needs: Int) = heroLogoFit(budget, max = 60f, min = 36f, reasonLine = 24f, needs = needs)

    @Test
    fun `at 100 percent the logo gets what the reason leaves, up to 60 dp`() {
        assertEquals(HeroLogoFit(50f, 2), yours(98f, needs = 2))
        assertEquals(HeroLogoFit(60f, 1), yours(98f, needs = 1))
        assertEquals("room to spare: 60 and both lines", HeroLogoFit(60f, 2), yours(140f, needs = 2))
    }

    @Test
    fun `at 115 percent the reason gives up its second line before the logo shrinks under the title's line`() {
        assertEquals(HeroLogoFit(44.75f, 1), yours(68.75f, needs = 2))
        assertEquals(HeroLogoFit(44.75f, 1), yours(68.75f, needs = 1))
    }

    @Test
    fun `with no room for the title's line and a line of reason, the title shows as before`() {
        // 115% with the Undo or offline note (24 dp) under the text.
        assertNull(yours(44.75f, needs = 2))
        assertNull(yours(59.9f, needs = 1))
        assertEquals(HeroLogoFit(36f, 1), yours(60f, needs = 1))
    }

    @Test
    fun `the kids' logo is up to 72 dp`() {
        val kids = { budget: Float, needs: Int -> heroLogoFit(budget, max = 72f, min = 32f, reasonLine = 24f, needs = needs) }
        assertEquals(HeroLogoFit(64f, 1), kids(88f, 1))
        assertEquals(HeroLogoFit(72f, 2), kids(130f, 2))
        assertEquals(HeroLogoFit(36f, 1), kids(60f, 2))
    }
}
