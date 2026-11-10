package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.PlexStream
import com.phonoodlesramen.actioncenter.plex.PlexStreams
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** A guide slot's file, or why it can't play: each reason has its own off-air card. */
sealed interface SlotSource {
    data class Found(val source: PlaybackSource) : SlotSource

    /** Plex is linked, but the title has no file (gone from the library, or no part). */
    data object NoFile : SlotSource

    /** Plex isn't linked, or its sign-in is no longer valid. */
    data object NotLinked : SlotSource

    /** The server couldn't be reached for a title the cache has no file for. */
    data object Offline : SlotSource
}

/** Finds a slot's file by its Plex key: the linked server on the Shield, a fake in tests. */
fun interface SlotSources {
    /** Never throws, except to cancel. */
    suspend fun find(itemKey: String): SlotSource
}

/** A slot's audio and subtitle streams by its Plex key (G13): the linked server on the Shield, a fake in tests. */
fun interface SlotStreams {
    /** Null when they can't be read: Media3 then chooses on its own. Never throws, except to cancel. */
    suspend fun find(itemKey: String): List<PlexStream>?

    companion object {
        /** No streams: Media3's own choice for every file (previews and tests that don't care). */
        val None = SlotStreams { null }
    }
}

/** [PlexStreams] from the linked server; any failure is "unknown", never an error on screen. */
class PlexSlotStreams(private val reader: PlexStreams, private val access: Flow<ServerAccess?>) : SlotStreams {
    override suspend fun find(itemKey: String): List<PlexStream>? = try {
        access.first()?.let { reader.streams(it, itemKey) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** [PlexPlayables] with the reason a title can't play, for the guide's player. */
class PlexSlotSources(private val playables: PlexPlayables, private val access: Flow<ServerAccess?>) : SlotSources {
    override suspend fun find(itemKey: String): SlotSource = try {
        playables.source(itemKey)?.let(SlotSource::Found)
            ?: if (access.first() == null) SlotSource.NotLinked else SlotSource.NoFile
    } catch (e: CancellationException) {
        throw e
    } catch (_: PlexException.Unauthorized) {
        SlotSource.NotLinked
    } catch (_: IOException) {
        // PlexException.Unreachable and friends.
        SlotSource.Offline
    } catch (_: Exception) {
        SlotSource.NoFile
    }
}

/**
 * Where a program's credits start, millis into its file (Phase 11): Plex's first credits
 * marker, for the watched point and the credits card. The linked server on the Shield, a
 * fake in tests.
 */
fun interface SlotCredits {
    /** Null when it has none, or Plex can't say. Never throws, except to cancel. */
    suspend fun find(itemKey: String): Long?

    companion object {
        /** No markers: 90% is the watched point, and the last minute the credits. */
        val None = SlotCredits { null }
    }
}

/** [PlexServerApi.creditsStart] for one title on the linked server; any failure is "none". */
class PlexSlotCredits(private val server: PlexServerApi, private val access: Flow<ServerAccess?>) : SlotCredits {
    override suspend fun find(itemKey: String): Long? = try {
        access.first()?.let { server.creditsStart(it, listOf(itemKey))[itemKey] }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
