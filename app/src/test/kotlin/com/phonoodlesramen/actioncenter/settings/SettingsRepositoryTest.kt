package com.phonoodlesramen.actioncenter.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensMoveOn
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.context.ContextSchedule
import com.phonoodlesramen.actioncenter.context.Hours
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.home.HomeLayout
import java.io.File
import java.nio.file.Files
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRepositoryTest {
    private fun TestScope.repository(): SettingsRepository {
        val file = File(Files.createTempDirectory("settings").toFile(), "settings.preferences_pb")
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    @Test
    fun `defaults before anything is saved`() = runTest {
        assertEquals(Settings(), repository().settings.first())
    }

    @Test
    fun `Play in is unset and guide viewing counts, until changed (Phase 11)`() = runTest {
        val repo = repository()

        val defaults = repo.settings.first()
        assertNull(defaults.playIn)
        assertEquals(true, defaults.countGuideViewing)

        repo.setPlayIn(PlayIn.PLEX)
        repo.setCountGuideViewing(false)
        assertEquals(PlayIn.PLEX to false, repo.settings.first().let { it.playIn to it.countGuideViewing })

        repo.setPlayIn(null)
        assertNull("unset again: Plezy if it's installed", repo.settings.first().playIn)
    }

    @Test
    fun `the home is the dashboard until another layout is chosen`() = runTest {
        val repo = repository()
        assertEquals(HomeLayout.DASHBOARD, repo.settings.first().homeLayout)

        repo.setHomeLayout(HomeLayout.MARQUEE)

        assertEquals(HomeLayout.MARQUEE, repo.settings.first().homeLayout)
        assertEquals("a layout that's gone reads as the dashboard", HomeLayout.DASHBOARD, HomeLayout.of("CAROUSEL"))
    }

    @Test
    fun `Search includes streaming apps until switched off (Phase 15)`() = runTest {
        val repo = repository()
        assertEquals(true, repo.settings.first().searchStreaming)

        repo.setSearchStreaming(false)

        assertEquals(false, repo.settings.first().searchStreaming)
    }

    @Test
    fun `the lens card rotates every 30 s, shuffles and moves on after 3 days, until changed (Phase 14)`() = runTest {
        val repo = repository()
        assertEquals(LensPrefs(rotate = true, speed = 3, shuffle = true, moveOn = LensMoveOn.THREE_DAYS), repo.settings.first().lensPrefs)

        repo.setLensRotate(false)
        repo.setLensSpeed(5)
        repo.setLensShuffle(false)
        repo.setLensMoveOn(LensMoveOn.WATCHED)
        assertEquals(LensPrefs(rotate = false, speed = 5, shuffle = false, moveOn = LensMoveOn.WATCHED), repo.settings.first().lensPrefs)

        repo.setLensSpeed(9)
        assertEquals("the notches are 1 to 5", 5, repo.settings.first().lensPrefs.speed)
        repo.setLensSpeed(0)
        assertEquals(1, repo.settings.first().lensPrefs.speed)
    }

    @Test
    fun `last searches are newest first, the same words move up, five are kept, each profile its own (Phase 15)`() = runTest {
        val repo = repository()
        listOf("harbor", "kids movies", "90s action", "lighthouse", "night shift", "Harbor  ").forEach { repo.rememberSearch("owner", it) }
        repo.rememberSearch("kid-maya", "puppies")
        repo.rememberSearch("owner", "   ")

        val recent = repo.settings.first().recentSearches
        assertEquals(listOf("Harbor", "night shift", "lighthouse", "90s action", "kids movies"), recent["owner"])
        assertEquals(listOf("puppies"), recent["kid-maya"])
    }

    @Test
    fun `with nothing chosen, Play in is Plezy when it's installed, else Plex`() {
        assertEquals(PlayIn.PLEZY, PlayIn.of(null, plezyInstalled = true))
        assertEquals(PlayIn.PLEX, PlayIn.of(null, plezyInstalled = false))
        assertEquals("a choice stands", PlayIn.PLEX, PlayIn.of(PlayIn.PLEX, plezyInstalled = true))
        assertEquals(PlayIn.PLEZY, PlayIn.of(PlayIn.PLEZY, plezyInstalled = false))
    }

    @Test
    fun `layout scale is clamped to 90-115 percent`() = runTest {
        val repo = repository()

        repo.setLayoutScale(2.0f)
        assertEquals(1.15f, repo.settings.first().layoutScale)
        repo.setLayoutScale(0.5f)
        assertEquals(0.90f, repo.settings.first().layoutScale)
    }

    @Test
    fun `accent index stays within the five accents`() = runTest {
        val repo = repository()

        repo.setAccent(99)
        assertEquals(4, repo.settings.first().accentIndex)
        repo.setAccent(-3)
        assertEquals(0, repo.settings.first().accentIndex)
    }

    @Test
    fun `name is trimmed and capped`() = runTest {
        val repo = repository()

        repo.setName("   Alex   ")
        assertEquals("Alex", repo.settings.first().name)
        repo.setName("x".repeat(100))
        assertEquals(24, repo.settings.first().name.length)
    }

    @Test
    fun `launches are counted per app`() = runTest {
        val repo = repository()

        repo.recordLaunch("pkg.a/pkg.a.Main")
        repo.recordLaunch("pkg.a/pkg.a.Main")
        repo.recordLaunch("pkg.b/pkg.b.Main")

        assertEquals(mapOf("pkg.a/pkg.a.Main" to 2, "pkg.b/pkg.b.Main" to 1), repo.settings.first().launchCounts)
    }

    @Test
    fun `apps can be hidden from the launchpad and shown again`() = runTest {
        val repo = repository()

        repo.setHidden("pkg.a/pkg.a.Main", hidden = true)
        repo.setHidden("pkg.b/pkg.b.Main", hidden = true)
        repo.setHidden("pkg.a/pkg.a.Main", hidden = false)

        assertEquals(setOf("pkg.b/pkg.b.Main"), repo.settings.first().hiddenApps)
    }

    @Test
    fun `sample content is off by default and can be switched on`() = runTest {
        val repo = repository()

        repo.setShowSampleContent(true)

        assertEquals(true, repo.settings.first().showSampleContent)
    }

    @Test
    fun `lenses start on, turn off and on, and the last one stays on`() = runTest {
        val repo = repository()
        assertEquals(Lens.entries.toSet(), repo.settings.first().enabledLenses)

        repo.setLensEnabled(Lens.HIDDEN_GEM, false)
        assertEquals(setOf(Lens.HIDDEN_GEM), repo.settings.first().disabledLenses)
        repo.setLensEnabled(Lens.HIDDEN_GEM, true)
        assertEquals(emptySet<Lens>(), repo.settings.first().disabledLenses)

        Lens.entries.forEach { repo.setLensEnabled(it, false) }
        assertEquals(setOf(Lens.COMFORT), repo.settings.first().enabledLenses)
    }

    @Test
    fun `the Quick Watch cutoff starts at 10 30 PM and keeps to quarter hours, past midnight too`() = runTest {
        val repo = repository()
        assertEquals(java.time.LocalTime.of(22, 30), repo.settings.first().quickWatchCutoff)

        repo.setQuickWatchCutoff(java.time.LocalTime.of(23, 8))
        assertEquals(java.time.LocalTime.of(23, 15), repo.settings.first().quickWatchCutoff)
        repo.setQuickWatchCutoff(java.time.LocalTime.of(23, 55))
        assertEquals(java.time.LocalTime.MIDNIGHT, repo.settings.first().quickWatchCutoff)
        repo.setQuickWatchCutoff(java.time.LocalTime.of(0, 30))
        assertEquals(java.time.LocalTime.of(0, 30), repo.settings.first().quickWatchCutoff)
    }

    @Test
    fun `a mode's hours are saved in quarter hours, and hours that clash are refused (M1)`() = runTest {
        val repo = repository()
        assertEquals(ContextSchedule.DEFAULT, repo.settings.first().contextSchedule)

        val late = Hours(LocalTime.of(23, 8), LocalTime.of(5, 0))
        assertNull(repo.setModeHours(ContextMode.LATE_NIGHT, late))
        assertEquals(Hours(LocalTime.of(23, 15), LocalTime.of(5, 0)), repo.settings.first().contextSchedule.lateNight)

        val clash = Hours(LocalTime.of(18, 0), LocalTime.of(23, 30))
        assertEquals("Weekend evening would overlap Late night", repo.setModeHours(ContextMode.WEEKEND_EVENING, clash))
        assertEquals(ContextSchedule.DEFAULT.weekendEvening, repo.settings.first().contextSchedule.weekendEvening)
    }

    @Test
    fun `the weekend cutoff and the AI switch are saved (M4, M7)`() = runTest {
        val repo = repository()
        assertEquals(LocalTime.of(23, 30), repo.settings.first().weekendCutoff)
        assertEquals(false, repo.settings.first().aiBlurbs)

        repo.setWeekendCutoff(LocalTime.of(0, 7))
        repo.setAiBlurbs(true)

        assertEquals(LocalTime.of(0, 0), repo.settings.first().weekendCutoff)
        assertEquals(true, repo.settings.first().aiBlurbs)
    }
}
