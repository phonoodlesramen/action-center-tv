package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.content.HomeContent
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensDeck
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.content.QuickItem
import com.phonoodlesramen.actioncenter.content.Season
import com.phonoodlesramen.actioncenter.content.UpNextItem
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.player.BackTo

/** How a strip card's badge is coloured: the accent, the warm lenses, live red, or the cool lenses. */
enum class BadgeTone { ACCENT, WARM, LIVE, COOL, PLAIN }

/** The small label at a strip card's top left (spec §4, Marquee). */
data class StripBadge(val text: String, val tone: BadgeTone = BadgeTone.PLAIN)

/**
 * A card in Marquee's "For tonight" strip (spec §4, Marquee). Resting on one fills the screen
 * with its art and puts its title, reason and buttons above the strip.
 */
sealed interface StripCard {
    /** Unique in the strip and steady while the card stands for the same kind of thing, so focus follows it. */
    val key: String

    /** The title's id: its placeholder art until Plex's arrives, and what the strip never shows twice. */
    val artId: String

    /** A Plex art path for the backdrop and the card; null draws the placeholder. */
    val image: String?
    val title: String

    /** A Plex logo path (Phase 12): it takes the title's place over the backdrop. */
    val logo: String? get() = null
    val eyebrow: String
    val meta: String

    /** Why it's here [REQ]. */
    val reason: String
    val badge: StripBadge

    /** The line under the card's title. */
    val caption: String

    /** How far through it is, 0..1, for the bar along the card's foot; null draws none. */
    val progress: Float? get() = null

    /** Tonight's Pick, with its reason and Not tonight. */
    data class Tonight(val pick: Pick) : StripCard {
        override val key = KEY_PICK
        override val artId get() = pick.id
        override val image get() = pick.image
        override val title get() = pick.title
        override val logo get() = pick.logo
        override val eyebrow get() = "Tonight's pick"
        override val meta get() = pick.meta
        override val reason get() = pick.reason
        override val badge get() = StripBadge("Pick", BadgeTone.ACCENT)
        override val caption get() = shortMeta(pick.meta)
    }

    /** A lens title: the lens card's ([key] "lens"), or the next lens's ("lens2"). */
    data class LensTitle(val state: LensCardState, override val key: String = KEY_LENS) : StripCard {
        override val artId get() = state.pick.id
        override val image get() = state.pick.image
        override val title get() = state.pick.title
        override val logo get() = state.pick.logo
        override val eyebrow get() = state.title
        override val meta get() = state.pick.meta
        override val reason get() = state.pick.line
        override val badge get() = StripBadge(state.moreLike?.let { "More like this" } ?: state.season?.title ?: state.lens.title, toneOf(state.lens, state.moreLike))
        override val caption get() = state.moreLike?.let { "More like $it" } ?: "${state.season?.title ?: state.lens.title} lens"
    }

    /** An Up Next episode: OK resumes it. */
    data class UpNext(val item: UpNextItem) : StripCard {
        override val key get() = "up:${item.id}"
        override val artId get() = item.id
        override val image get() = item.image
        override val title get() = item.title
        override val eyebrow get() = "Up next"
        override val meta get() = "${item.episode} · ${item.timeLeft}"
        override val reason get() = "Pick up where you left off"
        override val badge get() = StripBadge("Up next")
        override val caption get() = "${item.episode} · ${item.timeLeft}"
        override val progress get() = item.progress.coerceIn(0f, 1f)
    }

    /** A Quick Watch title: short enough to finish before the cutoff. */
    data class Quick(val item: QuickItem, val cutoff: String) : StripCard {
        override val key get() = "quick:${item.id}"
        override val artId get() = item.id
        override val image get() = item.image
        override val title get() = item.title
        override val eyebrow get() = "Quick Watch · fits before $cutoff"
        override val meta get() = item.meta
        override val reason get() = "Short enough to finish before $cutoff"
        override val badge get() = StripBadge("Quick Watch")
        override val caption get() = "Fits before $cutoff"
    }

    /** What's on now on a Plex Guide channel: your first favorite with a program on, else the lineup's first. */
    data class Live(val channel: GuideChannel, val slot: GuideSlot, val nowMillis: Long, val favorite: Boolean) : StripCard {
        override val key = KEY_LIVE
        override val artId get() = slot.itemKey ?: channel.id
        override val image get() = slot.art
        override val title get() = slot.title
        override val eyebrow get() = "On now · ${channel.label}"
        override val meta get() = listOfNotNull(slot.episode, "${slot.minutesLeftAt(nowMillis)} min left").joinToString(" · ")
        override val reason get() = if (favorite) "On your favorite channel" else "On now on the Plex Guide"
        override val badge get() = StripBadge("Live", BadgeTone.LIVE)
        override val caption get() = "Live on ${channel.label}"
        override val progress get() = slot.progressAt(nowMillis)
    }

    /** The guide player's saved spot while it holds (P4): OK resumes it, paused, behind live. */
    data class Paused(val backTo: BackTo) : StripCard {
        override val key = KEY_LIVE
        override val artId get() = backTo.slot.itemKey ?: backTo.channel.id
        override val image get() = backTo.slot.art
        override val title get() = backTo.slot.title
        override val eyebrow get() = "Back to ${backTo.channel.label}"
        override val meta get() = listOfNotNull(backTo.slot.episode, "Paused").joinToString(" · ")
        override val reason get() = "Right where you paused it"
        override val badge get() = StripBadge("Paused", BadgeTone.LIVE)
        override val caption get() = "Paused on ${backTo.channel.label}"
        override val progress get() = backTo.progress
    }

    companion object {
        const val KEY_PICK = "pick"
        const val KEY_LENS = "lens"
        const val KEY_MORE_LENS = "lens2"
        const val KEY_LIVE = "live"

        private val GuideChannel.label get() = "$number $name"

        /** A caption's room: the meta's leading parts while they fit ("Season 2 · 2 episodes left · TV-MA" is "Season 2"). */
        private const val CAPTION_CHARS = 22

        internal fun shortMeta(meta: String): String {
            val parts = meta.split(" · ")
            val kept = parts.indices.reversed().map { parts.take(it + 1).joinToString(" · ") }.firstOrNull { it.length <= CAPTION_CHARS }
            return kept ?: parts.first()
        }

        private fun toneOf(lens: Lens, moreLike: String?): BadgeTone = when {
            moreLike != null -> BadgeTone.PLAIN
            lens == Lens.THROWBACK || lens == Lens.COMFORT || lens == Lens.SEASONAL -> BadgeTone.WARM
            lens == Lens.HIDDEN_GEM || lens == Lens.CLASSIC -> BadgeTone.COOL
            else -> BadgeTone.PLAIN
        }
    }
}

/**
 * Marquee's "For tonight" strip (spec §4, Marquee): six cards in a mixed order. Each [Slot] of
 * the order takes its first title; the rest of Up Next and Quick Watch fill what's left. A title
 * never shows twice. Pure, so it's unit-tested.
 */
object TonightStrip {
    const val SIZE = 6

    /** A place in the strip's order. */
    enum class Slot { PICK, LENS, UP_NEXT, QUICK_WATCH, LIVE, MORE_LENS }

    /** The pick, the lens card, Up Next, Quick Watch, what's on live, and the next lens. */
    val DEFAULT_ORDER = listOf(Slot.PICK, Slot.LENS, Slot.UP_NEXT, Slot.QUICK_WATCH, Slot.LIVE, Slot.MORE_LENS)

    fun build(
        pick: Pick?,
        lens: LensCardState?,
        content: HomeContent?,
        live: StripCard? = null,
        moreLens: LensCardState? = null,
        order: List<Slot> = DEFAULT_ORDER,
    ): List<StripCard> {
        val upNext = content?.upNext.orEmpty().map(StripCard::UpNext)
        val quick = content?.quickWatch.orEmpty().map { StripCard.Quick(it, content?.quickWatchCutoff.orEmpty()) }
        val led = order.mapNotNull { slot ->
            when (slot) {
                Slot.PICK -> pick?.let(StripCard::Tonight)
                Slot.LENS -> lens?.let { StripCard.LensTitle(it) }
                Slot.UP_NEXT -> upNext.firstOrNull()
                Slot.QUICK_WATCH -> quick.firstOrNull()
                Slot.LIVE -> live
                Slot.MORE_LENS -> moreLens?.let { StripCard.LensTitle(it, StripCard.KEY_MORE_LENS) }
            }
        }
        // The rest of Up Next and Quick Watch, taking turns, fill the strip.
        val rest = (0 until maxOf(upNext.size, quick.size)).flatMap { i -> listOfNotNull(upNext.getOrNull(i), quick.getOrNull(i)) }
        return (led + rest).distinctBy { it.key }.distinctBy { it.artId }.take(SIZE)
    }

    /**
     * The strip's live card: the guide player's spot while it holds ([backTo], P4), else what's
     * on now on your first favorite channel with a program on, else on the lineup's first. Null
     * without a lineup, or with nothing on but filler and gaps.
     */
    fun live(guide: GuideCardState, favorites: List<String>, nowMillis: Long, backTo: BackTo? = null): StripCard? {
        if (backTo != null) return StripCard.Paused(backTo)
        val channels = (guide as? GuideCardState.Ready)?.channels ?: return null
        val byId = channels.associateBy { it.id }
        val ordered = favorites.mapNotNull(byId::get) + channels.filter { it.id !in favorites }
        return ordered.firstNotNullOfOrNull { channel ->
            channel.slotAt(nowMillis)?.takeIf { !it.filler && it.offAir == null }?.let { StripCard.Live(channel, it, nowMillis, channel.id in favorites) }
        }
    }

    /**
     * The next lens's title after the lens card's ([shown]), for the strip's last lens place: the
     * first in the rotation from another lens that isn't the card's title or Tonight's Pick.
     */
    fun moreLens(deck: LensDeck, shown: LensCardState?, pickId: String?, season: Season?): LensCardState? =
        deck.slots(0).firstOrNull { it.lens != shown?.lens && it.pick.id != shown?.pick?.id && it.pick.id != pickId }
            ?.let { LensCardState(it.lens, it.pick, if (it.lens == Lens.SEASONAL) season else null) }
}
