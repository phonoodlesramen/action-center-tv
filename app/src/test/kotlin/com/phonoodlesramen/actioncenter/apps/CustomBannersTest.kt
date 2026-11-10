package com.phonoodlesramen.actioncenter.apps

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.phonoodlesramen.actioncenter.media.SharedFolders
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Custom banners (L7) against a made-up Shield: a typical `/sdcard/Pictures/Icons` (a main
 * folder, some images that aren't app banners, a misspelt name, and a `Flat icons`
 * subfolder) and 20 TV apps installed besides Action Center. The packages are the apps'
 * real ones; the labels are the apps' own names.
 */
class CustomBannersTest {
    private val icons = listOf(
        "AMC Plus.png", "Android Settings.png", "Apple TV.png", "Beach at dusk.gif", "Discovery plus.png", "Disney+.png",
        "ESPN.png", "FX.png", "Fox Sports.png", "Gallery.png", "GeforceNow.png", "Google Chrome.png", "Google TV.png",
        "HBO Max.png", "Live Channes.png", "Netflix.png", "PBS Kids.png", "PBS.png", "Paramount.png", "Plex.png",
        "PrimeVideo.png", "Settings.png", "Showtime.png", "Spotify.png", "Sunset.jpg", "YouTube.png", "Youtube Kids.png",
        "Youtube TV.png", "Youtube TV2.png", "amazon music.png", "blank.png", "hulu.png", "peacocks.png", "sling.png",
    ) + listOf(
        "APPLE.png", "DISNEY+.png", "GOOGLE.png", "HBOMAX.png", "NETFLIX.png", "PLEX.png", "PRIME.png", "SETTINGS.png",
        "SPOTIFY.png", "YOUTUBE.png",
    ).map { "Flat icons/$it" }

    private val shield = icons.map { BannerFile(it, "/sdcard/Pictures/Icons/$it") }

    private fun app(packageName: String, label: String) = LaunchableApp(packageName, "$packageName.Main", label)

    /** The made-up Shield's TV apps (Action Center itself left out), with what should match. */
    private val installed = linkedMapOf(
        app("com.android.tv.settings", "Settings") to "Settings.png",
        app("com.android.gallery3d", "Gallery") to "Gallery.png",
        app("com.android.vending", "Google Play Store") to null,
        app("com.google.android.youtube.tv", "YouTube") to "YouTube.png",
        app("com.google.android.youtube.tvunplugged", "YouTube TV") to "Youtube TV.png",
        app("com.plexapp.android", "Plex") to "Plex.png",
        app("com.spocky.projengmenu", "Projectivy Launcher") to null,
        app("com.google.android.tv", "Live Channels") to "Live Channes.png",
        app("com.nvidia.tegrazone3", "GeForce NOW") to "GeforceNow.png",
        app("com.amazon.amazonvideo.livingroom", "Prime Video") to "PrimeVideo.png",
        app("com.cbs.ott", "Paramount+") to "Paramount.png",
        app("com.disney.disneyplus", "Disney+") to "Disney+.png",
        app("com.edde746.plezy", "Plezy") to null,
        app("com.google.android.play.games", "Google Play Games") to null,
        app("com.google.android.videos", "Google TV") to "Google TV.png",
        app("com.hulu.livingroomplus", "Hulu") to "hulu.png",
        app("com.netflix.ninja", "Netflix") to "Netflix.png",
        app("com.plexapp.mediaserver.smb", "Plex Media Server") to null,
        app("com.spotify.tv.android", "Spotify") to "Spotify.png",
        app("org.xbmc.kodi", "Kodi") to null,
    )

    @Test
    fun `the Shield's apps match the Shield's banners`() {
        assertEquals(44, shield.size)
        val state = CustomBannersState(shield)
        val matched = installed.keys.associate { it.label to state.bannerFor(it)?.name }
        assertEquals(installed.mapKeys { it.key.label }, matched)
        // 14 of the 20 apps get a custom banner; the other 6 keep their own.
        assertEquals(14, matched.values.count { it != null })
    }

    @Test
    fun `names are lower case letters and digits only`() {
        assertEquals("youtubetv", BannerIndex.normalise("Youtube TV"))
        assertEquals("youtubetv", BannerIndex.normalise("YouTube TV"))
        assertEquals("primevideo", BannerIndex.normalise("PrimeVideo"))
        assertEquals("disney", BannerIndex.normalise("Disney+"))
        assertEquals("youtubetv2", BannerIndex.normalise("Youtube TV2"))
    }

    @Test
    fun `an exact name wins over a near one`() {
        val index = BannerIndex(listOf("Youtube TV2.png", "Youtube TV.png").map { BannerFile(it, it) })
        assertEquals("Youtube TV.png", index.match("YouTube TV")?.name)
    }

    @Test
    fun `one typo is forgiven, only for long names and only when one file is that close`() {
        val index = BannerIndex(listOf("Live Channes.png", "Plex.png", "Youtube TV2.png", "Youtube TV3.png").map { BannerFile(it, it) })
        assertEquals("Live Channes.png", index.match("Live Channels")?.name)
        // Short names must match exactly: Plex isn't Plez, and Plezy isn't Plex.
        assertNull(index.match("Plez"))
        assertNull(index.match("Plezy"))
        // Two files one typo from "YouTube TV": neither.
        assertNull(index.match("YouTube TV"))
    }

    @Test
    fun `the main folder wins over Flat icons`() {
        val state = CustomBannersState(shield)
        assertEquals("Netflix.png", state.matchFor(app("com.netflix.ninja", "Netflix"))?.name)
        assertEquals("Disney+.png", state.matchFor(app("com.disney.disneyplus", "Disney+"))?.name)
        // Flat icons alone still match.
        val flat = CustomBannersState(shield.filter { it.inSubfolder })
        assertEquals("Flat icons/NETFLIX.png", flat.matchFor(app("com.netflix.ninja", "Netflix"))?.name)
    }

    @Test
    fun `one edit apart`() {
        assertTrue(BannerIndex.oneEditApart("livechannes", "livechannels"))
        assertTrue(BannerIndex.oneEditApart("netflix", "netflox"))
        assertTrue(BannerIndex.oneEditApart("abc", "abc"))
        assertFalse(BannerIndex.oneEditApart("plex", "plezy"))
        assertFalse(BannerIndex.oneEditApart("youtube", "youtubetv"))
    }

    @Test
    fun `Choose art overrides the match, and a picked file that's gone falls back to it`() {
        val netflix = app("com.netflix.ninja", "Netflix")
        val picked = CustomBannersState(shield, mapOf(netflix.key to BannerChoice.Chosen("Flat icons/NETFLIX.png")))
        assertEquals("Flat icons/NETFLIX.png", picked.bannerFor(netflix)?.name)

        val own = CustomBannersState(shield, mapOf(netflix.key to BannerChoice.OwnBanner))
        assertNull(own.bannerFor(netflix))

        val gone = CustomBannersState(shield, mapOf(netflix.key to BannerChoice.Chosen("Old/netflix.png")))
        assertEquals("Netflix.png", gone.bannerFor(netflix)?.name)
    }

    @Test
    fun `without the storage grant nothing matches and every app keeps its own banner`() {
        val state = CustomBannersState()
        assertTrue(installed.keys.all { state.bannerFor(it) == null })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.stored(permitted: Boolean = true): StoredCustomBanners {
        val sdcard = Files.createTempDirectory("sdcard").toFile()
        listOf("Netflix.png", "Flat icons/NETFLIX.png", "notes.txt").forEach { path ->
            File(sdcard, "${SharedFolders.ICONS}/$path").apply { parentFile!!.mkdirs(); writeText("x") }
        }
        val prefs = File(Files.createTempDirectory("banners").toFile(), "custom_banners.preferences_pb")
        return StoredCustomBanners(
            folders = SharedFolders(sdcard) { permitted },
            store = PreferenceDataStoreFactory.create(scope = backgroundScope) { prefs },
            scope = backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
        )
    }

    @Test
    fun `the folder is listed with Flat icons, images only, by their names in the folder`() = runTest {
        val banners = stored()
        val state = banners.state.first { it.files.isNotEmpty() }
        assertEquals(listOf("Flat icons/NETFLIX.png", "Netflix.png"), state.files.map { it.name })
        assertTrue(state.files.all { File(it.path).isFile })
    }

    @Test
    fun `picks are saved per app and Automatic clears them`() = runTest {
        val banners = stored()
        val netflix = app("com.netflix.ninja", "Netflix")
        val plex = app("com.plexapp.android", "Plex")

        banners.choose(netflix, BannerChoice.Chosen("Flat icons/NETFLIX.png"))
        banners.choose(plex, BannerChoice.OwnBanner)
        val picked = banners.state.first { it.choices.size == 2 }
        assertEquals(BannerChoice.Chosen("Flat icons/NETFLIX.png"), picked.choiceFor(netflix))
        assertEquals(BannerChoice.OwnBanner, picked.choiceFor(plex))

        banners.choose(netflix, BannerChoice.Automatic)
        val cleared = banners.state.first { it.choices.size == 1 }
        assertEquals(BannerChoice.Automatic, cleared.choiceFor(netflix))
        assertEquals("Netflix.png", cleared.bannerFor(netflix)?.name)
    }

    @Test
    fun `without the grant the list is empty and nothing throws`() = runTest {
        val banners = stored(permitted = false)
        banners.refresh()
        assertTrue(banners.state.value.files.isEmpty())
    }
}
