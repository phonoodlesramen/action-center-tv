package com.phonoodlesramen.actioncenter.player

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Playback check's player: ExoPlayer on the Shield, straight from Plex over HTTPS
 * with the token in a header, built as the guide's player is ([PlexExo]). It reports
 * the decoder, the video format (Dolby Vision or PQ/HLG), the audio path (passthrough
 * or decoded to PCM) and the time to the first frame.
 */
@OptIn(UnstableApi::class)
class ExoProbePlayer(
    private val context: Context,
    private val surface: () -> SurfaceView?,
    private val playMillis: Long = 10_000,
    private val timeoutMillis: Long = 20_000,
) : ProbePlayer {
    override suspend fun play(source: PlaybackSource, startMs: Long): ProbeOutcome = withContext(Dispatchers.Main) {
        val firstFrame = CompletableDeferred<Long?>()
        var videoDecoder: String? = null
        var videoFormat: String? = null
        var audioFormat: String? = null
        var audioPath: String? = null
        var error: String? = null
        val player = PlexExo.builder(context, PlexExo.http(source.headers)).build()
        val started = SystemClock.elapsedRealtime()
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoder = decoderName
            }

            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                videoFormat = describeVideo(format)
            }

            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                audioFormat = format.sampleMimeType?.removePrefix("audio/")
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                audioPath = encodingName(audioTrackConfig.encoding)
            }

            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                firstFrame.complete(SystemClock.elapsedRealtime() - started)
            }

            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, playbackError: PlaybackException) {
                error = "${playbackError.errorCodeName}: ${playbackError.message}"
                firstFrame.complete(null)
            }
        })
        try {
            surface()?.let(player::setVideoSurfaceView)
            player.setMediaItem(MediaItem.fromUri(source.uri), startMs)
            player.prepare()
            player.play()
            val millis = withTimeoutOrNull(timeoutMillis) { firstFrame.await() }
            if (millis != null) delay(playMillis)
            ProbeOutcome(
                firstFrameMillis = millis,
                video = listOfNotNull(videoFormat, videoDecoder).joinToString(" · ").ifBlank { null },
                audio = listOfNotNull(audioFormat, audioPath).joinToString(" → ").ifBlank { null },
                error = error ?: if (millis == null) "No picture within ${timeoutMillis / 1_000} s" else null,
            )
        } finally {
            player.release()
        }
    }

    private companion object {
        fun describeVideo(format: Format): String {
            val kind = when {
                format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
                format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                else -> format.sampleMimeType?.removePrefix("video/")
            }
            return listOfNotNull(kind, if (format.width > 0) "${format.width}×${format.height}" else null).joinToString(" ")
        }

        fun encodingName(encoding: Int): String = when (encoding) {
            C.ENCODING_AC3 -> "Dolby Digital passthrough"
            C.ENCODING_E_AC3 -> "DD+ passthrough"
            C.ENCODING_E_AC3_JOC -> "DD+ Atmos passthrough"
            C.ENCODING_DOLBY_TRUEHD -> "TrueHD passthrough"
            C.ENCODING_DTS -> "DTS passthrough"
            C.ENCODING_DTS_HD -> "DTS-HD passthrough"
            else -> "PCM (decoded)"
        }
    }
}
