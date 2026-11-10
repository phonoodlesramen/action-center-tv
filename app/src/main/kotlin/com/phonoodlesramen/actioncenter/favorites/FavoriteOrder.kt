package com.phonoodlesramen.actioncenter.favorites

import com.phonoodlesramen.actioncenter.guide.GuideChannel

/**
 * Favorite channels' rules (Phase 13, C3, C4). Pure, so they're tested without Compose.
 *
 * A profile's favorites are channel ids in its order. Only the channels a lineup shows take
 * part: an off channel, one that isn't kid-safe for a kid, or one deleted since stays in the
 * list (an off one comes back in place when turned on) until the list is next saved, when a
 * deleted one leaves it ([pruned]).
 */
object FavoriteOrder {
    /** [channels] (in number order) with the favorites first, in their order, then the rest as they were. */
    fun <T> ordered(channels: List<T>, favorites: List<String>, id: (T) -> String): List<T> {
        if (favorites.isEmpty()) return channels
        val byId = channels.associateBy(id)
        val first = favorites.distinct().mapNotNull { byId[it] }
        val leading = first.mapTo(HashSet(), id)
        return first + channels.filter { id(it) !in leading }
    }

    fun ordered(channels: List<GuideChannel>, favorites: List<String>): List<GuideChannel> = ordered(channels, favorites, GuideChannel::id)

    /** How many of [channels] are favorites: once [ordered], the first this many. */
    fun leading(channels: List<GuideChannel>, favorites: List<String>): Int {
        if (favorites.isEmpty()) return 0
        val set = favorites.toSet()
        return channels.count { it.id in set }
    }

    /** [favorites] with [channelId] added at the end, or taken out. */
    fun toggled(favorites: List<String>, channelId: String, favorite: Boolean): List<String> = when {
        !favorite -> favorites.filter { it != channelId }
        channelId in favorites -> favorites
        else -> favorites + channelId
    }

    /**
     * Move (C3): [channelId] swapped with the next favorite [step] away (-1 left, +1 right)
     * among those [shown] in the row. The ones not shown (off, or hidden from a kid) keep their
     * places in the list. Null at either end: it goes no further.
     */
    fun moved(favorites: List<String>, shown: Set<String>, channelId: String, step: Int): List<String>? {
        val places = favorites.indices.filter { favorites[it] in shown }
        val at = places.indexOfFirst { favorites[it] == channelId }
        if (at < 0) return null
        val to = places.getOrNull(at + step) ?: return null
        return favorites.toMutableList().apply {
            this[places[at]] = favorites[to]
            this[to] = channelId
        }
    }

    /**
     * [favorites] without channels that are gone from the lineup, as it's saved. [known] is
     * every channel there is, off ones too. With none known (the lineup not read yet), nothing
     * goes.
     */
    fun pruned(favorites: List<String>, known: Collection<String>): List<String> {
        val distinct = favorites.distinct()
        if (known.isEmpty()) return distinct
        val set = known.toSet()
        return distinct.filter { it in set }
    }
}
