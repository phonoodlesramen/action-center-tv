package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.plex.PlexStream

/** Records what the tuner asks of the player; tests report back through [events], as ExoPlayer would. */
class FakeGuideVideo(val events: GuideVideo.Events) : GuideVideo {
    /** "play <uri> @<startMs>", "prepare <uri>", "pause", "resume", "seek @<ms>" and "track <kind> <id>", in order. Stops aren't listed. */
    val calls = mutableListOf<String>()
    var stops = 0
    var released = false

    /** The streams handed over with each play, in order. */
    val streams = mutableListOf<List<PlexStream>?>()

    /** What [positionMs] answers: null, as a player that can't say. */
    var position: Long? = null

    /** Thrown by the next [play], as a broken player would. */
    var failPlay: RuntimeException? = null

    override fun play(source: PlaybackSource, startMs: Long, streams: List<PlexStream>?) {
        failPlay?.let {
            failPlay = null
            throw it
        }
        calls += "play ${source.uri} @$startMs"
        this.streams += streams
    }

    override fun prepareNext(source: PlaybackSource, streams: List<PlexStream>?) {
        calls += "prepare ${source.uri}"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun resume() {
        calls += "resume"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek @$positionMs"
    }

    override fun positionMs(): Long? = position

    override fun selectTrack(kind: TrackKind, streamId: String?) {
        calls += "track $kind $streamId"
    }

    override fun stop() {
        stops++
    }

    override fun release() {
        released = true
    }

    companion object {
        /** Where the fake sources say a key's file is. */
        fun uri(key: String) = "https://10-0-0-5.abc.plex.direct:32400/library/parts/$key/file.mkv"

        fun play(key: String, startMs: Long) = "play ${uri(key)} @$startMs"

        fun prepare(key: String) = "prepare ${uri(key)}"

        fun seek(positionMs: Long) = "seek @$positionMs"
    }
}

/** Every key has a file, except those in [missing]; [durations] gives some of them a length. */
class FakeSlotSources(
    val missing: MutableMap<String, SlotSource> = mutableMapOf(),
    val durations: MutableMap<String, Long> = mutableMapOf(),
) : SlotSources {
    /** Every key looked up, in order. */
    val lookups = mutableListOf<String>()

    override suspend fun find(itemKey: String): SlotSource {
        lookups += itemKey
        return missing[itemKey] ?: SlotSource.Found(PlaybackSource(FakeGuideVideo.uri(itemKey), mapOf("X-Plex-Token" to "secret"), durations[itemKey]))
    }
}

/** Each key's streams from [byKey] (none for the rest); every lookup is recorded. */
class FakeSlotStreams(val byKey: MutableMap<String, List<PlexStream>> = mutableMapOf()) : SlotStreams {
    val lookups = mutableListOf<String>()

    override suspend fun find(itemKey: String): List<PlexStream>? {
        lookups += itemKey
        return byKey[itemKey]
    }
}

/** A Plex stream for tests: [type] 2 audio or 3 subtitles. */
fun stream(
    id: String,
    type: Int,
    index: Int?,
    language: String? = "eng",
    title: String? = null,
    selected: Boolean = false,
    forced: Boolean = false,
    key: String? = null,
    codec: String? = null,
) = PlexStream(
    id = id, type = type, index = index, languageCode = language, language = null, displayTitle = title, extendedDisplayTitle = null,
    selected = selected, default = false, forced = forced, codec = codec, channels = null, key = key,
)
