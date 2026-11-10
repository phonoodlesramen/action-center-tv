package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.plex.PlexHeaders
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.cache.PlexDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Where a title's file plays from (G5): the linked server's HTTPS address plus the
 * file's part path, with the token in [headers]. The token never goes in the URL.
 *
 * @param durationMs the file's length, when known: a trailer joined after it's over shows
 *   the Up next card instead of seeking past its end ([GuideTuning.startPosition]).
 */
class PlaybackSource(val uri: String, val headers: Map<String, String>, val durationMs: Long?) {
    /** Never prints [headers]: they carry the token. */
    override fun toString() = "PlaybackSource($uri)"
}

/**
 * Finds a title's file for the guide's player and the Playback check: from the cache
 * (movies and episodes), or from the title's details on the server (a trailer extra,
 * or a title the cache has no file for yet).
 *
 * @param headers who Action Center is on Plex; the player's streams reader ([com.phonoodlesramen.actioncenter.plex.PlexStreams]) uses them too.
 */
class PlexPlayables(
    private val access: Flow<ServerAccess?>,
    private val dao: PlexDao,
    private val server: PlexServerApi,
    val headers: PlexHeaders,
) {
    /**
     * Null when Plex isn't linked or the title has no file. Throws [com.phonoodlesramen.actioncenter.plex.PlexException]
     * when the server can't be reached for a title the cache doesn't know: show it as off air.
     *
     * A title from its details (a trailer: extras aren't cached) brings the length Plex
     * gives there (G20), so a trailer shorter than the time gone in its slot isn't joined
     * past its end.
     */
    suspend fun source(ratingKey: String): PlaybackSource? {
        val linked = access.first() ?: return null
        val cached = dao.item(ratingKey)
        val base = linked.baseUri.trimEnd('/')
        val cachedPart = cached?.partKey
        if (cachedPart != null) return PlaybackSource(base + cachedPart, headers.media(linked.token), cached.durationMs)
        val details = server.metadata(linked, ratingKey) ?: return null
        val partKey = details.media?.partKey ?: return null
        return PlaybackSource(base + partKey, headers.media(linked.token), cached?.durationMs ?: details.durationMs)
    }
}
