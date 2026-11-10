package com.phonoodlesramen.actioncenter.guide

/**
 * The Plex Guide for kids (K8, G9): one lineup, of which a kid sees the channels that play
 * only titles inside their ceiling (R2), in the Live row, on the Guide card, in the full
 * guide and in the player's channel up/down and mini guide. Interface only (D12): the
 * channels are still there, just not shown. Pure.
 */
object KidGuide {
    /** Whether [channel] is kid-safe under [ceiling], by what it can play ([GuideChannel.contentRatings]). */
    fun safe(channel: GuideChannel, ceiling: Set<String>): Boolean = safe(channel.contentRatings, channel.unrated, ceiling)

    /**
     * Whether a channel that can play titles rated [contentRatings], and an unrated one when
     * [unrated], is kid-safe under [ceiling]: it can play something, every rating is inside
     * the ceiling, and nothing is unrated (K4). A channel whose rule names ratings counts
     * too, since its titles can only have those.
     */
    fun safe(contentRatings: Collection<String>, unrated: Boolean, ceiling: Set<String>): Boolean =
        !unrated && contentRatings.isNotEmpty() && contentRatings.all { it in ceiling }

    /**
     * [state] as someone with [ceiling] sees it; null is an adult's, who sees it all. A
     * lineup with no kid-safe channel is [GuideCardState.NoneForKids]; the other states
     * (no channels yet, and why) are everyone's.
     */
    fun of(state: GuideCardState, ceiling: Set<String>?): GuideCardState {
        if (ceiling == null || state !is GuideCardState.Ready) return state
        val shown = state.channels.filter { safe(it, ceiling) }
        return when {
            shown.size == state.channels.size -> state
            shown.isEmpty() -> GuideCardState.NoneForKids
            else -> state.copy(channels = shown)
        }
    }
}
