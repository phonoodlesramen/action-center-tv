package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.station.Favorites
import com.phonoodlesramen.actioncenter.guide.station.ProfileKeys
import java.nio.file.Path
import kotlinx.serialization.Serializable

/**
 * Each profile's favorite channels (Phase 13, C1), kept in the volume (`favorites.json`) by the
 * profile's key (proposal A: `owner`, or `plex:<Plex Home user id>`), so a profile's favorites
 * follow it from TV to TV. A change moves the house's [Versions] on, as a spot change does, so the
 * other TVs hear of it through `changes` at once.
 *
 * The station keeps the list as the TV sent it, in its order. Channels that are off stay in it; a
 * TV leaves out the ones it no longer has.
 */
class FavoritesStore(private val file: Path, private val versions: Versions) {
    @Serializable
    private data class Saved(val profiles: Map<String, List<String>> = emptyMap())

    private val lock = Any()
    private var saved: Saved = try {
        SecretFiles.readJson(file, Saved.serializer())
    } catch (e: IllegalArgumentException) {
        null
    } ?: Saved()

    /** A profile never saved answers version 0, so a TV that kept its own list while the station was 1.1 sends it up. */
    fun get(profileKey: String): Favorites {
        check(profileKey)
        return synchronized(lock) { saved.profiles[profileKey]?.let { Favorites(it, versions.current) } ?: Favorites() }
    }

    /** [channels] become [profileKey]'s favorites, in that order. Returns them with the version that includes them. */
    fun put(profileKey: String, channels: List<String>): Favorites {
        check(profileKey)
        if (channels.size > MAX_CHANNELS) throw ApiException(400, "That's more favorites than the guide station keeps.")
        if (channels.any { it.isBlank() || it.length > MAX_ID }) throw ApiException(400, "Those favorites name a channel that can't be right.")
        val list = channels.distinct()
        synchronized(lock) {
            // An unchanged list leaves the version: nothing for the other TVs to hear.
            if (saved.profiles[profileKey].orEmpty() == list) return Favorites(list, versions.current)
            val profiles = if (list.isEmpty()) saved.profiles - profileKey else saved.profiles + (profileKey to list)
            val next = Saved(profiles)
            SecretFiles.writeJson(file, Saved.serializer(), next)
            saved = next
            return Favorites(list, versions.bump())
        }
    }

    private fun check(profileKey: String) {
        if (profileKey.length > MAX_ID || !ProfileKeys.isValid(profileKey)) {
            throw ApiException(400, "The guide station keeps favorites for the owner and for kids linked to Plex, and that's neither.")
        }
    }

    private companion object {
        const val MAX_CHANNELS = 500
        const val MAX_ID = 200
    }
}
