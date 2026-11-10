package com.phonoodlesramen.actioncenter.player

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.SubtitleView
import com.phonoodlesramen.actioncenter.plex.PlexStream

/** The guide's video as [GuideTuner] drives it: ExoPlayer on the Shield, a fake in tests. Main thread only. */
interface GuideVideo {
    /**
     * Plays [source] from [startMs]. A source opened early with [prepareNext] starts at once.
     * [streams] are its audio and subtitle streams from Plex, when known: they pick the
     * tracks Plex chose (G13) and bring Plex's subtitle files. Every file starts on its own
     * defaults, so a choice from the options never carries over (G14).
     */
    fun play(source: PlaybackSource, startMs: Long, streams: List<PlexStream>? = null)

    /** Opens [source] ahead of its slot without playing it, so the switch at the boundary is quick. */
    fun prepareNext(source: PlaybackSource, streams: List<PlexStream>? = null)

    /** Holds the picture where it is (G11), and lets go of any file opened ahead: the schedule waits. */
    fun pause()

    fun resume()

    /**
     * Moves the playing file to [positionMs] (P1), playing or held as it was. Seeking puts
     * the picture behind live, where the schedule waits, so a file opened ahead is let go.
     */
    fun seekTo(positionMs: Long)

    /** Where the playing file is, or null when nothing's open. */
    fun positionMs(): Long?

    /**
     * The options' choice (G14): [streamId]'s track for this file only, or no subtitles at all
     * when it's null. Nothing is saved, and nothing is sent to Plex.
     */
    fun selectTrack(kind: TrackKind, streamId: String?)

    /** Stops and forgets every file: nothing plays or loads. */
    fun stop()

    fun release()

    /** What the player reports back, on the main thread. */
    interface Events {
        /** Frames are moving (true), or stopped for any reason (false). */
        fun onPlaying(playing: Boolean)

        /** The file played to its end, which can be before its slot ends (a short trailer). */
        fun onEnded()

        fun onError(error: VideoError)

        fun onVideoSize(width: Int, height: Int, pixelWidthHeightRatio: Float)

        /** What the options' Audio and Subtitles list for the file playing, each time its tracks or choices change. */
        fun onTracks(menu: TrackMenu)
    }
}

/**
 * Why a file stopped: [reason] is the off-air card it shows, one of [OffAirReason.OFFLINE],
 * [OffAirReason.UNAVAILABLE] or [OffAirReason.CANT_PLAY] ([reasonFor]); [code] is Media3's
 * error code name, for the log only.
 */
data class VideoError(val reason: OffAirReason, val code: String) {
    companion object {
        /**
         * Media3's [PlaybackException] code (1.11), sorted by what a viewer can follow:
         * - **Offline:** the connection to Plex failed or timed out. Tried again.
         * - **Unavailable:** Plex answered, but the file couldn't be read right now: a bad
         *   HTTP status (a NAS drive that's off or restarting), not found, no permission,
         *   and the other IO codes. Tried again.
         * - **Can't play:** the rest: parsing, decoding, audio, DRM, and cleartext HTTP
         *   (a setting on the Shield, which trying again won't change).
         */
        fun reasonFor(errorCode: Int): OffAirReason = when (errorCode) {
            in OFFLINE_CODES -> OffAirReason.OFFLINE
            in UNAVAILABLE_CODES -> OffAirReason.UNAVAILABLE
            else -> OffAirReason.CANT_PLAY
        }

        private val OFFLINE_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

        private val UNAVAILABLE_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
    }
}

/** What the guide's video draws on: the picture, and its subtitles over it (Compose has no subtitle view). */
@OptIn(UnstableApi::class)
class VideoViews(val surface: SurfaceView, val subtitles: SubtitleView)

/**
 * ExoPlayer for a guide channel, on [views], straight from Plex over HTTPS with the token
 * in a header (G5). It never reports to Plex (G6): Media3 sends nothing but range requests
 * for the file, and a track choice isn't sent back either (G14).
 *
 * - **Never runs on:** it pauses at the end of each file, so a short trailer can't start the
 *   next program early. [GuideTuner] switches files at the slot's boundary.
 * - **Holds nothing awake:** no wake lock (`WAKE_MODE_NONE`; Media3 1.11 holds one by
 *   default). The activity keeps the screen on only while video plays (G10).
 * - **Memory:** a 64 MB buffer at most. Media3's default (about 140 MB for video) is too
 *   much next to the home's art on a 2-3 GB Shield; Plex on the LAN refills it quickly.
 * - **Tracks (G13):** the audio and subtitles Plex chose for the file ([TrackMatch]), else
 *   the file's default audio and its forced subtitles only. A file's subtitle tracks are
 *   drawn by media3-ui's [SubtitleView], fed from `onCues`.
 * - **Audio:** passed through where the TV takes it, else decoded (DTS by FFmpeg, [PlexExo]).
 */
@OptIn(UnstableApi::class)
class ExoGuideVideo(
    context: Context,
    private val views: VideoViews,
    private val events: GuideVideo.Events,
    private val log: (String) -> Unit = { Log.i(PlaybackCheck.LOG_TAG, it) },
) : GuideVideo {
    private val http = PlexExo.http(emptyMap())
    private val player: ExoPlayer = PlexExo.builder(context, http)
        .setWakeMode(C.WAKE_MODE_NONE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setPauseAtEndOfMediaItems(true)
        .setLoadControl(DefaultLoadControl.Builder().setTargetBufferBytes(BUFFER_BYTES).build())
        .build()

    /** A file asked to play or opened ahead, with Plex's streams for it; [chosen] once Plex's choice is applied. */
    private class Item(val source: PlaybackSource, val streams: List<PlexStream>?) {
        var chosen = false
    }

    /** The file playing (or paused), whose tracks the options list. */
    private var playing: Item? = null

    /** The file [prepareNext] opened, while it waits for its slot. */
    private var queued: Item? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = events.onPlaying(isPlaying)

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) events.onEnded()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) events.onEnded()
        }

        override fun onPlayerError(error: PlaybackException) =
            events.onError(VideoError(VideoError.reasonFor(error.errorCode), error.errorCodeName))

        override fun onVideoSizeChanged(videoSize: VideoSize) =
            events.onVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)

        override fun onTracksChanged(tracks: Tracks) = tracksChanged()

        override fun onCues(cueGroup: CueGroup) = views.subtitles.setCues(cueGroup.cues)
    }

    init {
        // Subtitles are on again (2.6 turned them off), but a track the file marks default
        // doesn't count: only forced ones show unless Plex or the options pick one (G13).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        views.subtitles.setUserDefaultStyle()
        views.subtitles.setUserDefaultTextSize()
        player.setVideoSurfaceView(views.surface)
        player.addListener(listener)
    }

    override fun play(source: PlaybackSource, startMs: Long, streams: List<PlexStream>?) {
        // Every file comes from the same server, so the headers rarely change; data sources read them as they open.
        http.setDefaultRequestProperties(source.headers)
        val ahead = queued?.takeIf { it.source.uri == source.uri }
        val index = ahead?.let { queuedIndex(source.uri) }
        if (ahead != null && index != null) {
            player.seekTo(index, startMs)
            if (index > 0) player.removeMediaItems(0, index)
            playing = Item(source, ahead.streams)
        } else {
            player.setMediaItem(item(source, streams), startMs)
            player.prepare()
            playing = Item(source, streams)
        }
        queued = null
        // Each program starts on its own defaults (G14): no choice from the options carries over.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        views.subtitles.setCues(emptyList())
        player.playWhenReady = true
    }

    override fun prepareNext(source: PlaybackSource, streams: List<PlexStream>?) {
        http.setDefaultRequestProperties(source.headers)
        val item = item(source, streams)
        val running = player.playWhenReady && player.playerError == null &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (running) {
            // After the file that's playing: Media3 loads it once that file is buffered to its end.
            val after = player.currentMediaItemIndex + 1
            if (player.mediaItemCount > after) player.removeMediaItems(after, player.mediaItemCount)
            player.addMediaItem(item)
        } else {
            // Nothing playing (a card, an error, a file that ended): open it paused.
            player.playWhenReady = false
            player.setMediaItem(item, 0)
            player.prepare()
            playing = null
        }
        queued = Item(source, streams)
    }

    override fun pause() {
        player.playWhenReady = false
        // The schedule waits while paused, so a file opened ahead won't be next after all.
        val after = player.currentMediaItemIndex + 1
        if (player.mediaItemCount > after) player.removeMediaItems(after, player.mediaItemCount)
        queued = null
    }

    override fun resume() {
        player.playWhenReady = true
    }

    override fun seekTo(positionMs: Long) {
        // Behind live the schedule waits, so a file opened ahead won't be next after all.
        val after = player.currentMediaItemIndex + 1
        if (player.mediaItemCount > after) player.removeMediaItems(after, player.mediaItemCount)
        queued = null
        player.seekTo(positionMs)
    }

    override fun positionMs(): Long? = if (player.mediaItemCount == 0) null else player.currentPosition

    override fun selectTrack(kind: TrackKind, streamId: String?) {
        val item = playing ?: return
        val tracks = player.currentTracks
        val parameters = player.trackSelectionParameters.buildUpon()
        if (kind == TrackKind.SUBTITLES && streamId == null) {
            parameters.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val streams = item.streams ?: return
            val type = if (kind == TrackKind.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
            val files = fileTracks(tracks, type)
            val match = if (kind == TrackKind.AUDIO) TrackMatch.audio(streams, files) else TrackMatch.subtitles(streams, files)
            val track = streamId?.let { match?.track(it) }?.takeIf { it.supported } ?: return
            parameters.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(tracks.groups[track.group].mediaTrackGroup, track.track))
        }
        player.trackSelectionParameters = parameters.build()
    }

    override fun stop() {
        queued = null
        playing = null
        player.stop()
        player.clearMediaItems()
        views.subtitles.setCues(emptyList())
    }

    override fun release() {
        queued = null
        playing = null
        player.removeListener(listener)
        player.release()
        views.subtitles.setCues(emptyList())
    }

    /**
     * The playing file's tracks are known or have changed: the first time, Plex's choice is
     * applied (G13) and the match is logged; every time, the options' lists are reported.
     */
    private fun tracksChanged() {
        val item = playing ?: return
        if (player.currentMediaItem?.localConfiguration?.uri?.toString() != item.source.uri) return
        val tracks = player.currentTracks
        if (tracks.groups.isEmpty()) return
        val audio = fileTracks(tracks, C.TRACK_TYPE_AUDIO)
        val text = fileTracks(tracks, C.TRACK_TYPE_TEXT)
        val audioMatch = item.streams?.let { TrackMatch.audio(it, audio) }
        val textMatch = item.streams?.let { TrackMatch.subtitles(it, text) }
        if (!item.chosen) {
            item.chosen = true
            log("player: ${TrackMatch.describe(TrackKind.AUDIO, item.streams, audio, audioMatch)}")
            log("player: ${TrackMatch.describe(TrackKind.SUBTITLES, item.streams, text, textMatch)}")
            choose(tracks, audioMatch?.plexChoice, textMatch?.plexChoice)
        }
        events.onTracks(TrackMenu(audioMatch?.options().orEmpty(), textMatch?.options().orEmpty()))
    }

    /** Plex's choice for the file, where it made one; the rest stays Media3's (default audio, forced subtitles). */
    private fun choose(tracks: Tracks, audio: FileTrack?, subtitles: FileTrack?) {
        if (audio == null && subtitles == null) return
        val parameters = player.trackSelectionParameters.buildUpon()
        audio?.takeIf { it.supported }?.let {
            parameters.setOverrideForType(TrackSelectionOverride(tracks.groups[it.group].mediaTrackGroup, it.track))
        }
        subtitles?.takeIf { it.supported }?.let {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(tracks.groups[it.group].mediaTrackGroup, it.track))
        }
        player.trackSelectionParameters = parameters.build()
    }

    /** Where the file [prepareNext] opened sits in the playlist, if it's [uri] and still usable. */
    private fun queuedIndex(uri: String): Int? {
        if (player.playerError != null) return null
        val last = player.mediaItemCount - 1
        return last.takeIf { it >= 0 && player.getMediaItemAt(it).localConfiguration?.uri?.toString() == uri }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024 * 1024

        /** A Plex subtitle file's path: anything else isn't fetched. */
        val SIDECAR_KEY = Regex("/library/streams/[0-9]+")

        /** The subtitle files Media3 reads, by Plex's codec name. */
        val SIDECAR_TYPES = mapOf(
            "srt" to MimeTypes.APPLICATION_SUBRIP,
            "subrip" to MimeTypes.APPLICATION_SUBRIP,
            "ass" to MimeTypes.TEXT_SSA,
            "ssa" to MimeTypes.TEXT_SSA,
            "vtt" to MimeTypes.TEXT_VTT,
            "webvtt" to MimeTypes.TEXT_VTT,
            "ttml" to MimeTypes.APPLICATION_TTML,
        )

        /** The file's audio or text tracks, in its order. */
        fun fileTracks(tracks: Tracks, type: Int): List<FileTrack> = buildList {
            tracks.groups.forEachIndexed { g, group ->
                if (group.type != type) return@forEachIndexed
                for (t in 0 until group.length) {
                    val format = group.getTrackFormat(t)
                    add(FileTrack(g, t, format.language, format.id, group.isTrackSelected(t), group.isTrackSupported(t)))
                }
            }
        }

        fun item(source: PlaybackSource, streams: List<PlexStream>?): MediaItem =
            MediaItem.Builder().setUri(source.uri).setSubtitleConfigurations(sidecars(source, streams)).build()

        /**
         * Plex's subtitle files next to the title's file (G13), from the same server. Media3
         * reads them through the player's own [DefaultMediaSourceFactory][androidx.media3.exoplayer.source.DefaultMediaSourceFactory],
         * so the token rides in a header as it does for the file, never in their URLs. Each
         * loads only once chosen. Formats Media3 can't read are left out.
         */
        fun sidecars(source: PlaybackSource, streams: List<PlexStream>?): List<MediaItem.SubtitleConfiguration> {
            val file = source.uri.toUri()
            return streams.orEmpty().filter { it.type == PlexStream.SUBTITLES && !it.inVideo }.mapNotNull { stream ->
                val key = stream.key?.takeIf(SIDECAR_KEY::matches) ?: return@mapNotNull null
                val mime = SIDECAR_TYPES[stream.codec?.lowercase()] ?: return@mapNotNull null
                MediaItem.SubtitleConfiguration.Builder(file.buildUpon().encodedPath(key).encodedQuery(null).fragment(null).build())
                    .setId(TrackMatch.sidecarId(stream))
                    .setMimeType(mime)
                    .setLanguage(stream.languageCode)
                    .setSelectionFlags(if (stream.forced) C.SELECTION_FLAG_FORCED else 0)
                    .setLabel(stream.displayTitle)
                    .build()
            }
        }
    }
}
