package com.phonoodlesramen.actioncenter.ui.home

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.key
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.Season
import com.phonoodlesramen.actioncenter.home.LensCardState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The kids' home's pure parts (K7, K9, R1): My apps, the lens card's eyebrow, the hero's fit and the greeting. */
class KidLayoutTest {
    private fun app(name: String) = LaunchableApp("com.example.$name", "com.example.$name.Main", name)

    private val plezy = app("Plezy")
    private val netflix = app("Netflix")
    private val disney = app("Disney+")
    private val kids = app("YouTube Kids")
    private val minecraft = app("Minecraft")

    @Test
    fun `My apps are the allowlist alone, habit first, then A to Z`() {
        val all = listOf(disney, minecraft, netflix, plezy, kids)
        val allow = setOf(plezy.key, kids.key, disney.key, minecraft.key)
        assertEquals(listOf(plezy, kids, disney, minecraft), myApps(launchpad = listOf(plezy, netflix, kids), allApps = all, allow = allow))
        assertEquals(emptyList<LaunchableApp>(), myApps(listOf(plezy), all, allow = emptySet()))
        // An allowed app that isn't installed isn't there.
        assertEquals(listOf(plezy), myApps(emptyList(), listOf(plezy, netflix), setOf(plezy.key, "com.example.Gone/com.example.Gone.Main")))
    }

    private fun pick(id: String) = LensPick(id, id, "2017 · PG", "A year ago", seen = true)

    @Test
    fun `the kids' lens card calls Throwback Remember when, and every other lens by its name`() {
        assertEquals("Remember when", kidLensTitle(LensCardState(Lens.THROWBACK, pick("paddington"), season = null)))
        assertEquals("Comfort rewatch", kidLensTitle(LensCardState(Lens.COMFORT, pick("moana"), season = null)))
        assertEquals("For the family", kidLensTitle(LensCardState(Lens.FAMILY, pick("totoro"), season = null)))
        assertEquals("Halloween", kidLensTitle(LensCardState(Lens.SEASONAL, pick("hotel"), season = Season.HALLOWEEN)))
    }

    /**
     * At 100% the hero's text column is 142 dp (Row A's 214 less its padding and buttons), so
     * 88 after the eyebrow and meta; at 115%, 60.
     */
    private fun fit(budget: Float, largeFits: Boolean, smallFits: Boolean = true) =
        kidHeroFit(budget, largeFits, smallFits, largeLine = 40f, smallLine = 32f, reasonLine = 24f)

    @Test
    fun `the kid title is 32 sp when it fits, with two lines of reason when there's room`() {
        assertEquals(KidHeroFit(large = true, titleLines = 1, reasonLines = 2), fit(88f, largeFits = true))
        assertEquals(KidHeroFit(large = true, titleLines = 1, reasonLines = 1), fit(70f, largeFits = true))
    }

    @Test
    fun `a long title, or 115 percent's bigger text, takes headlineSmall`() {
        assertEquals(KidHeroFit(large = false, titleLines = 1, reasonLines = 2), fit(88f, largeFits = false))
        // 115%: a kid title and a line of reason don't fit, a small one does.
        assertEquals(KidHeroFit(large = false, titleLines = 1, reasonLines = 1), fit(58f, largeFits = true))
        // Too long for one small line: two, when there's room for them and a reason line.
        assertEquals(KidHeroFit(large = false, titleLines = 2, reasonLines = 1), fit(88f, largeFits = false, smallFits = false))
        assertEquals(KidHeroFit(large = false, titleLines = 1, reasonLines = 1), fit(60f, largeFits = false, smallFits = false))
    }

    @Test
    fun `with a logo, the title waits in its box on one line, in the kid style when the box holds it`() {
        val at100 = heroLogoFit(88f, max = 72f, min = 32f, reasonLine = 24f, needs = 2)!!
        assertEquals(HeroLogoFit(40f, 2), at100)
        assertEquals(KidHeroFit(large = true, titleLines = 1, reasonLines = 2), kidHeroFit(88f, true, true, 40f, 32f, 24f, logo = at100))
        // 115%: a 36 dp box holds a small title's line, not a kid one's; the reason keeps one line.
        val at115 = heroLogoFit(60f, max = 72f, min = 32f, reasonLine = 24f, needs = 2)!!
        assertEquals(HeroLogoFit(36f, 1), at115)
        assertEquals(KidHeroFit(large = false, titleLines = 1, reasonLines = 1), kidHeroFit(60f, true, true, 40f, 32f, 24f, logo = at115))
        assertEquals(
            "a long title too: one line, ellipsized",
            KidHeroFit(large = false, titleLines = 1, reasonLines = 2),
            kidHeroFit(88f, largeFits = false, smallFits = false, 40f, 32f, 24f, logo = at100),
        )
    }

    @Test
    fun `the greeting is Hi and their name`() {
        assertEquals("Hi Maya!", kidGreeting("Maya"))
        assertEquals("Hi!", kidGreeting(" "))
    }
}
