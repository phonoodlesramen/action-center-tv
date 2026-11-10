package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.tvprovider.TvProgram

/** A Channels button (L8): one network that tunes YouTube TV in one press. */
data class ChannelButton(
    val id: String,
    val name: String,
    /** What OK opens: an intent URI from YouTube TV's own row, or a `tv.youtube.com` link. */
    val intentUri: String,
    /** A banner from the Icons folder (a file path), or the program's art; null shows the name. */
    val art: String? = null,
    val source: Source = Source.CHANNEL_LIST,
) {
    /** Where a button comes from (Q3): YouTube TV's own row, unless the operator's `channels.json` replaces or adds to it. */
    enum class Source { CHANNEL_LIST, YOUTUBE_TV }
}

/** The rows below the dashboard (L10), above the apps row. Empty rows hide themselves. */
data class HomeRowsState(
    val channels: List<ChannelButton> = emptyList(),
    /** Other apps' Watch Next entries (L9), Plex and Plezy left out, most recent first. */
    val continueInApps: List<TvProgram> = emptyList(),
)
