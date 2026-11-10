package com.phonoodlesramen.actioncenter.wallpaper

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperRepositoryTest {
    private fun TestScope.repository(): WallpaperRepository {
        val file = File(Files.createTempDirectory("wallpaper").toFile(), "wallpaper.preferences_pb")
        return WallpaperRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    @Test
    fun `starts on Midnight with solid cards, so nothing changes until a style is picked`() = runTest {
        assertEquals(WallpaperPrefs(), repository().prefs.first())
        assertEquals(WallpaperStyle.MIDNIGHT, WallpaperPrefs().style)
        assertEquals(CardStyle.SOLID, WallpaperPrefs().cardStyle)
    }

    @Test
    fun `every field round-trips, and clearing the image goes back to a slideshow`() = runTest {
        val repo = repository()
        val chosen = WallpaperPrefs(
            style = WallpaperStyle.YOUR_IMAGES,
            dim = 0.3f,
            blur = 0.2f,
            imageBlur = 0.4f,
            speed = 0.9f,
            folder = "Pictures/Wallpapers/Wallpaper images",
            image = "vortex.jpg",
            video = "loop.mp4",
            slideshowMinutes = 10,
            fit = ImageFit.FIT,
            slowPan = false,
            cardStyle = CardStyle.GLASS,
            nowShowing = true,
            nowShowingMinutes = 3,
        )

        repo.update { chosen }
        assertEquals(chosen, repo.prefs.first())

        repo.update { it.copy(image = null) }
        assertEquals(null, repo.prefs.first().image)
    }

    @Test
    fun `out-of-range values are kept in range`() = runTest {
        val repo = repository()
        repo.update { it.copy(dim = 2f, blur = -1f, imageBlur = 3f, speed = 5f, slideshowMinutes = 0, nowShowingMinutes = -2) }

        val stored = repo.prefs.first()
        assertEquals(WallpaperPrefs.MAX_DIM, stored.dim)
        assertEquals(0f, stored.blur)
        assertEquals(1f, stored.imageBlur)
        assertEquals(1f, stored.speed)
        assertEquals(1, stored.slideshowMinutes)
        assertEquals(1, stored.nowShowingMinutes)
    }
}
