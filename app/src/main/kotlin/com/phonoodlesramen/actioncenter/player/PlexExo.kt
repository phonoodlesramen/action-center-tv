package com.phonoodlesramen.actioncenter.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer

/**
 * ExoPlayer for files on the linked Plex server, shared by the guide's player and the
 * Playback check, so the check tests what the channels play: HTTPS straight from Plex,
 * the token in a request header and never in the URL.
 */
@OptIn(UnstableApi::class)
internal object PlexExo {
    const val CONNECT_TIMEOUT_MS = 8_000

    /** Plex's HTTP requests, with [headers] (they carry the token) on every one. */
    fun http(headers: Map<String, String>): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(headers)
        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(CONNECT_TIMEOUT_MS)

    /** A player builder that reads through [http]; each caller adds its own settings. */
    fun builder(context: Context, http: DataSource.Factory): ExoPlayer.Builder =
        ExoPlayer.Builder(context, FfmpegAudioLast(context)).setMediaSourceFactory(DefaultMediaSourceFactory(http))
}

/**
 * Media3's own renderers, then NextLib's FFmpeg audio decoder last (G15): passthrough and
 * the Shield's own decoders keep every format they take, and FFmpeg decodes to PCM only
 * what nothing else can, such as DTS, which this TV doesn't take. No FFmpeg video: the
 * Shield's hardware decodes every picture.
 */
@OptIn(UnstableApi::class)
private class FfmpegAudioLast(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        out += FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
    }
}
