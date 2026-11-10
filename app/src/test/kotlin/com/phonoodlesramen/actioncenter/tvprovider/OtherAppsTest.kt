package com.phonoodlesramen.actioncenter.tvprovider

import com.phonoodlesramen.actioncenter.home.ChannelButton
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping behind Channels and Continue in your apps. Rows have the shapes the
 * Shield's provider had on 10/26/2026 (Diagnostics dump); titles and links are made up.
 */
class OtherAppsTest {
    @Test
    fun `Continue in your apps leaves out Plex and Plezy, and entries that open nothing, newest first`() {
        val programs = listOf(
            watchNext(1, DISNEY, "Old movie", engaged = 100),
            watchNext(2, YOUTUBE, "A video", engaged = 300),
            watchNext(3, "com.plexapp.android", "Plex show", engaged = 900),
            watchNext(4, "com.edde746.plezy", "Plezy show", engaged = 800),
            watchNext(5, "tv.plex.app", "Plex HTPC show", engaged = 700),
            watchNext(6, SPOTIFY, "A podcast", engaged = 200, intent = "spotify:episode:sample6?source=continue_watching"),
            watchNext(7, PRIME, "No link", engaged = 1_000, intent = null),
            watchNext(8, PRIME, "Blank link", engaged = 1_000, intent = " "),
            watchNext(9, DISNEY, "Never engaged", engaged = null),
            TvProgram(10, TvProgram.Kind.PREVIEW, DISNEY, "A recommendation", intentUri = "https://www.disneyplus.com/browse/x", lastEngagementMillis = 5_000),
        )

        assertEquals(listOf(2L, 6L, 1L, 9L), OtherApps.watchNext(programs).map { it.id })
    }

    @Test
    fun `YouTube TV's networks become buttons by package and channel, though they're clips and not live`() {
        val provider = TvProviderSnapshot(
            channels = listOf(
                TvChannel(36, YOUTUBE_TV, "YouTube TV", "intent:#Intent;action=android.intent.action.MAIN;package=$YOUTUBE_TV;end"),
                TvChannel(19, DISNEY, "Recommended for you", "https://www.disneyplus.com"),
            ),
            programs = listOf(
                network(1, "ESPN", "aaa"),
                network(2, "FOX Sports 1", "bbb"),
                network(3, "Channel 8", "ccc", thumbnail = "https://example.com/still.jpg"),
                network(4, "No link", null),
                network(5, "espn", "ddd"),
                TvProgram(6, TvProgram.Kind.PREVIEW, DISNEY, "A movie", type = 0, channelId = 19, intentUri = "https://www.disneyplus.com/x"),
                TvProgram(7, TvProgram.Kind.WATCH_NEXT, YOUTUBE_TV, "A recording", intentUri = "https://tv.youtube.com/watch/eee"),
            ),
        )

        val buttons = OtherApps.youTubeTvChannels(provider, banners)

        assertEquals(listOf("ESPN", "FOX Sports 1", "Channel 8"), buttons.map { it.name })
        assertEquals(
            ChannelButton(
                id = "youtube-tv-1",
                name = "ESPN",
                intentUri = "intent://tv.youtube.com/watch/aaa?launch=launcher#Intent;scheme=https;package=$YOUTUBE_TV;end",
                art = File(icons, "ESPN.png").path,
                source = ChannelButton.Source.YOUTUBE_TV,
            ),
            buttons[0],
        )
        assertEquals(File(icons, "Fox Sports.png").path, buttons[1].art)
        assertEquals("no banner: the program's own art", "https://example.com/still.jpg", buttons[2].art)
    }

    @Test
    fun `only YouTube TV's own channel counts once it has others, and without channels every row of its does`() {
        val programs = listOf(network(1, "ESPN", "aaa"), network(2, "A show", "bbb", channel = 40))
        val channels = listOf(TvChannel(36, YOUTUBE_TV, "YouTube TV"), TvChannel(40, YOUTUBE_TV, "Recommended"))

        assertEquals(listOf("ESPN"), OtherApps.youTubeTvChannels(TvProviderSnapshot(channels, programs), null).map { it.name })
        assertEquals(listOf("ESPN", "A show"), OtherApps.youTubeTvChannels(TvProviderSnapshot(emptyList(), programs), null).map { it.name })
    }

    @Test
    fun `the operator's list replaces YouTube TV's networks, or adds to them, taking a same-named network's place`() {
        val youTubeTv = listOf("ESPN", "Channel 8", "PBS").map { ChannelButton("yt-$it", it, "intent://$it", source = ChannelButton.Source.YOUTUBE_TV) }
        val entries = listOf(ChannelListEntry("Food Network", "https://tv.youtube.com/watch/f"), ChannelListEntry("channel 8", "https://tv.youtube.com/watch/8"))
        fun snapshot(list: ChannelList) = OtherAppsSnapshot(
            channelList = list,
            listChannels = OtherApps.listChannels(list, banners),
            youTubeTvChannels = youTubeTv,
        )

        assertEquals(listOf("ESPN", "Channel 8", "PBS"), OtherApps.channels(snapshot(ChannelList.Missing)).map { it.name })
        assertEquals(listOf("ESPN", "Channel 8", "PBS"), OtherApps.channels(snapshot(ChannelList.Loaded(emptyList()))).map { it.name })
        assertEquals(listOf("Food Network", "channel 8"), OtherApps.channels(snapshot(ChannelList.Loaded(entries))).map { it.name })

        val added = OtherApps.channels(snapshot(ChannelList.Loaded(entries, includeYouTubeTv = true)))
        assertEquals(listOf("ESPN", "channel 8", "PBS", "Food Network"), added.map { it.name })
        assertEquals("https://tv.youtube.com/watch/8", added[1].intentUri)
        assertEquals(ChannelButton.Source.CHANNEL_LIST, added[1].source)
    }

    @Test
    fun `list buttons keep the list's order and find their banners`() {
        val list = ChannelList.Loaded(listOf(ChannelListEntry("FS1", "https://tv.youtube.com/watch/a", banner = "Fox Sports"), ChannelListEntry("Channel 8", "https://tv.youtube.com/watch/b")))

        val buttons = OtherApps.listChannels(list, banners)

        assertEquals(listOf("list-0-fs1", "list-1-channel8"), buttons.map { it.id })
        assertEquals(listOf(File(icons, "Fox Sports.png").path, null), buttons.map { it.art })
        assertTrue(OtherApps.listChannels(ChannelList.Problem("bad"), banners).isEmpty())
    }

    @Test
    fun `apps turned off in Settings leave the row`() {
        val snapshot = OtherAppsSnapshot(watchNext = listOf(watchNext(1, DISNEY, "A"), watchNext(2, YOUTUBE, "B"), watchNext(3, DISNEY, "C")))

        val rows = OtherApps.rows(snapshot, ChannelsAndAppsPrefs(hiddenApps = setOf(DISNEY)))

        assertEquals(listOf(2L), rows.continueInApps.map { it.id })
    }

    @Test
    fun `Settings lists every app with something to continue, plus any turned off, by name`() {
        val snapshot = OtherAppsSnapshot(
            canReadTvListings = true,
            watchNext = listOf(watchNext(1, YOUTUBE, "A"), watchNext(2, DISNEY, "B"), watchNext(3, DISNEY, "C")),
            youTubeTvChannels = listOf(ChannelButton("yt-1", "ESPN", "intent://x", source = ChannelButton.Source.YOUTUBE_TV)),
        )
        val labels = AppLabels { mapOf(DISNEY to "Disney+", YOUTUBE to "YouTube", SPOTIFY to "Spotify")[it] }

        val state = OtherApps.settings(snapshot, ChannelsAndAppsPrefs(hiddenApps = setOf(SPOTIFY, "com.example.gone")), labels)

        assertEquals(
            listOf(
                WatchNextApp("com.example.gone", "com.example.gone", 0, shown = false),
                WatchNextApp(DISNEY, "Disney+", 2, shown = true),
                WatchNextApp(SPOTIFY, "Spotify", 0, shown = false),
                WatchNextApp(YOUTUBE, "YouTube", 1, shown = true),
            ),
            state.apps,
        )
        assertEquals(1 to 1, state.channels to state.youTubeTvChannels)
        assertTrue(state.loaded && state.canReadTvListings)
        assertEquals(ChannelsAndAppsState(), OtherApps.settings(null, ChannelsAndAppsPrefs(), labels))
    }

    private val icons = Files.createTempDirectory("Icons").toFile().apply {
        listOf("ESPN.png", "Fox Sports.png", "PBS.png").forEach { File(this, it).writeText("x") }
    }
    private val banners = NetworkBanners(icons.listFiles()!!.toList())

    private fun watchNext(id: Long, pkg: String, title: String, engaged: Long? = id, intent: String? = "https://example.com/play/$id") =
        TvProgram(id, TvProgram.Kind.WATCH_NEXT, pkg, title, intentUri = intent, lastEngagementMillis = engaged)

    /** A network in YouTube TV's row, as the Shield publishes them: a clip, not live. */
    private fun network(id: Long, title: String, video: String?, channel: Long = 36, thumbnail: String? = null) = TvProgram(
        id = id,
        kind = TvProgram.Kind.PREVIEW,
        packageName = YOUTUBE_TV,
        title = title,
        type = 4,
        channelId = channel,
        thumbnailUri = thumbnail,
        intentUri = video?.let { "intent://tv.youtube.com/watch/$it?launch=launcher#Intent;scheme=https;package=$YOUTUBE_TV;end" },
        live = false,
    )

    private companion object {
        const val DISNEY = "com.disney.disneyplus"
        const val YOUTUBE = "com.google.android.youtube.tv"
        const val SPOTIFY = "com.spotify.tv.android"
        const val PRIME = "com.amazon.amazonvideo.livingroom"
    }
}
