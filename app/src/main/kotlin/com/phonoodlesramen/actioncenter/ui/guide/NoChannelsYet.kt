package com.phonoodlesramen.actioncenter.ui.guide

import com.phonoodlesramen.actioncenter.guide.GuideCardState

/**
 * Why the guide has no channels yet, in one wording for the Guide card and the full guide
 * (G20 B). The Live row says nothing of it: without guide channels it's YouTube TV's
 * networks alone, and the Guide card above it explains.
 */
internal enum class NoChannelsYet(
    /** The Guide card's line, under "Your library as TV channels". */
    val card: String,
    /** The full guide's line, under its eyebrow. */
    val guide: String,
) {
    /** Plex isn't connected: the guide is made from the library (G0). */
    NOT_CONNECTED(
        card = "They appear here once Plex is connected",
        guide = "No channels yet: they appear once Plex is connected",
    ),

    /** Plex is linked, and the library hasn't synced yet. */
    NOT_SYNCED(
        card = "They appear here after your library syncs",
        guide = "No channels yet: they appear after your library syncs",
    ),

    /** The library has synced and the lineup is being made. */
    BUILDING(
        card = "They're being made from your library now",
        guide = "No channels yet: they're being made from your library now",
    ),

    /** The library has synced, but nothing in it fills a day of any channel. */
    NOTHING_TO_FILL(
        card = "Not enough in your library to fill a channel yet",
        guide = "No channels yet: not enough in your library to fill a channel",
    ),

    /** Every channel is off, or was deleted, by hand. */
    NONE_ON(
        card = "They're all off in Settings › Plex Guide",
        guide = "No channels yet: they're all off in Settings › Plex Guide",
    ),

    /** A kid, and no channel's rule stays inside their ceiling (K8). */
    NONE_FOR_KIDS(
        card = "None of them are for your age yet",
        guide = "No channels yet: none of them are for your age",
    ),
    ;

    /** [guide]'s reason as a sentence of its own: "They appear after your library syncs". */
    val sentence: String get() = guide.removePrefix(PREFIX).replaceFirstChar(Char::uppercaseChar)

    companion object {
        private const val PREFIX = "No channels yet: "

        /**
         * What [state] waits on; null when there are channels to show. [plexLinked] is the
         * home's: it tells a library that hasn't synced from Plex not being connected. A
         * lineup of no channels reads as not set up.
         */
        fun of(state: GuideCardState, plexLinked: Boolean): NoChannelsYet? = when (state) {
            is GuideCardState.Ready -> if (state.channels.isEmpty()) notSetUp(plexLinked) else null
            GuideCardState.NotSetUp -> notSetUp(plexLinked)
            GuideCardState.Building -> BUILDING
            GuideCardState.NothingToFill -> NOTHING_TO_FILL
            GuideCardState.NoneOn -> NONE_ON
            GuideCardState.NoneForKids -> NONE_FOR_KIDS
        }

        private fun notSetUp(plexLinked: Boolean) = if (plexLinked) NOT_SYNCED else NOT_CONNECTED
    }
}
