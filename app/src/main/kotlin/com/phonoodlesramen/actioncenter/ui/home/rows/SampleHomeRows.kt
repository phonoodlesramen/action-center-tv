package com.phonoodlesramen.actioncenter.ui.home.rows

import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.home.HomeRowsState
import com.phonoodlesramen.actioncenter.tvprovider.TvProgram

/** Made-up Channels and Continue in your apps rows for previews and screenshot tests. */
internal object SampleHomeRows {
    private const val DISNEY = "com.disney.disneyplus"
    private const val YOUTUBE = "com.google.android.youtube.tv"
    private const val PRIME = "com.amazon.amazonvideo.livingroom"
    private const val SPOTIFY = "com.spotify.tv.android"

    /** The sample home's evening, 26 October 2026, in epoch millis. */
    private const val EVENING = 1_793_043_660_000L
    private const val HOUR = 3_600_000L

    val channels = listOf("ESPN", "FOX Sports 1", "PBS", "NBC", "CNN", "Food Network", "FX").mapIndexed { index, name ->
        ChannelButton(id = "sample-$index", name = name, intentUri = "https://tv.youtube.com/watch/sample$index")
    }

    val continueInApps = listOf(
        watchNext(1, DISNEY, "Bluey", season = "3", episode = "14", episodeTitle = "Sleepytime", minutes = 7 to 4, hoursAgo = 1),
        watchNext(2, YOUTUBE, "Restoring a 1970s Sony Trinitron", minutes = 31 to 12, hoursAgo = 2),
        watchNext(3, PRIME, "The Boys", season = "4", episode = "3", episodeTitle = "We'll Keep the Red Flag Flying Here", minutes = 62 to 40, hoursAgo = 20),
        watchNext(4, DISNEY, "Moana 2", minutes = 100 to 22, hoursAgo = 26),
        watchNext(5, SPOTIFY, "Hidden Brain", episodeTitle = "The Science of Sleep", minutes = 50 to 18, hoursAgo = 30),
        watchNext(6, DISNEY, "Andor", season = "2", episode = "4", episodeTitle = "Ever Been to Ghorman?", minutes = 0 to 0, hoursAgo = 48),
        watchNext(7, YOUTUBE, "Building a Plex server on a budget", minutes = 18 to 15, hoursAgo = 72),
    )

    val rows = HomeRowsState(channels = channels, continueInApps = continueInApps)

    /** The four apps' names; no icons, so badges show initials like they do before an icon loads. */
    val badges = object : AppBadges {
        private val names = mapOf(DISNEY to "Disney+", YOUTUBE to "YouTube", PRIME to "Prime Video", SPOTIFY to "Spotify")

        override fun name(packageName: String) = names[packageName] ?: packageName
    }

    private fun watchNext(
        id: Long,
        packageName: String,
        title: String,
        season: String? = null,
        episode: String? = null,
        episodeTitle: String? = null,
        minutes: Pair<Int, Int>,
        hoursAgo: Int,
    ) = TvProgram(
        id = id,
        kind = TvProgram.Kind.WATCH_NEXT,
        packageName = packageName,
        title = title,
        episodeTitle = episodeTitle,
        season = season,
        episode = episode,
        intentUri = "https://example.com/sample/$id",
        durationMillis = minutes.first * 60_000L,
        positionMillis = minutes.second * 60_000L,
        lastEngagementMillis = EVENING - hoursAgo * HOUR,
    )
}
