package com.phonoodlesramen.actioncenter.ui.wallpaper.styles

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.phonoodlesramen.actioncenter.media.MediaKind
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.LocalWallpaperSources
import com.phonoodlesramen.actioncenter.ui.wallpaper.WallpaperScene
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.LoopPlayer
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.VideoLoopFiles
import com.phonoodlesramen.actioncenter.ui.wallpaper.motion.VideoLoopSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creative (L1): A muted, looping video from the chosen folder, paused whenever the home isn't showing.
 *
 * - **The file:** Settings' choice in the folder, else the folder's first video. No video
 *   or no storage grant: nothing is drawn, so the Midnight background shows.
 * - **The player:** Media3 ExoPlayer (Q4), muted with its audio track switched off (no
 *   audio focus, no audio decoder), `REPEAT_MODE_ONE` for a gapless loop, zoomed to fill
 *   the screen. It exists only between the home's ON_START and ON_STOP.
 * - **The surface:** a `SurfaceView` behind the window, not a `TextureView`, so the
 *   hardware decoder's frames go straight to the display compositor (and the crop is the
 *   compositor's, `VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING`). The dim scrim and the
 *   cards draw over it as usual.
 * - **Never holds the screen on:** no wake lock (`WAKE_MODE_NONE`; Media3 1.11 holds one by
 *   default) and no `keepScreenOn`, so the Shield's screensaver still comes on.
 */
@Composable
fun VideoLoopWallpaper(scene: WallpaperScene, modifier: Modifier = Modifier) {
    val sources = LocalWallpaperSources.current
    val folder = scene.prefs.folder
    val chosen = scene.prefs.video
    // Listing a folder is disk work: off the main thread, once per folder and choice.
    val file by produceState<File?>(null, sources, folder, chosen) {
        value = withContext(Dispatchers.IO) { VideoLoopFiles.choose(chosen, sources.files(folder, MediaKind.VIDEO)) }
    }
    val video = file
    if (video == null || LocalInspectionMode.current) {
        Box(modifier)
    } else {
        VideoLoopSurface(video, modifier)
    }
}

@Composable
private fun VideoLoopSurface(file: File, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Until a frame is on screen the surface is empty: the background covers it, then fades away.
    var playing by remember { mutableStateOf(false) }
    val session = remember {
        lateinit var session: VideoLoopSession<SurfaceView>
        session = VideoLoopSession(
            open = { video ->
                playing = false
                ExoLoopPlayer(
                    context = context.applicationContext,
                    file = video,
                    onFirstFrame = { playing = true },
                    // Not from inside the player's own callback, and only for the current player.
                    onError = { failed -> ExoLoopPlayer.post { if (session.player === failed) session.failed() } },
                )
            },
        )
        session
    }
    SideEffect { session.file = file }
    DisposableEffect(lifecycle, session) {
        // Added while already started, the observer is sent ON_START straight away.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.start()
                Lifecycle.Event.ON_STOP -> {
                    session.stop()
                    playing = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.stop()
        }
    }
    val shutter by animateFloatAsState(if (playing) 0f else 1f, tween(SHUTTER_MILLIS), label = "shutter")
    Box(modifier) {
        AndroidView(
            factory = { SurfaceView(it) },
            modifier = Modifier.fillMaxSize().drawWithContent {
                drawContent()
                if (shutter > 0f) drawRect(Palette.Background, alpha = shutter)
            },
            update = { session.surface = it },
            onRelease = { session.surface = null },
        )
    }
}

/**
 * ExoPlayer set up for a wallpaper: muted, looping, cropped to fill, small buffers (the
 * file is local), and nothing that wakes the device or changes the TV's refresh rate.
 */
@OptIn(UnstableApi::class)
private class ExoLoopPlayer(
    context: Context,
    file: File,
    onFirstFrame: () -> Unit,
    onError: (ExoLoopPlayer) -> Unit,
) : LoopPlayer<SurfaceView> {
    private val player = ExoPlayer.Builder(context)
        .setWakeMode(C.WAKE_MODE_NONE)
        .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, START_BUFFER_MS)
                .setTargetBufferBytes(BUFFER_BYTES)
                .build(),
        )
        .build()
        .apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            // A wallpaper never switches the TV's display mode.
            videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(
                object : Player.Listener {
                    override fun onRenderedFirstFrame() = onFirstFrame()

                    override fun onPlayerError(error: PlaybackException) = onError(this@ExoLoopPlayer)
                },
            )
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            playWhenReady = true
            prepare()
        }

    override fun attach(surface: SurfaceView?) = player.setVideoSurfaceView(surface)

    override fun release() = player.release()

    companion object {
        private val main = Handler(Looper.getMainLooper())

        /** Error handling releases the player, which mustn't happen inside its own callback. */
        fun post(block: () -> Unit) {
            main.post(block)
        }

        // Local files read far faster than any bitrate: a few seconds ahead is enough, and
        // keeps a 4K file from buffering 100+ MB on a 3 GB device.
        const val MIN_BUFFER_MS = 2_000
        const val MAX_BUFFER_MS = 6_000
        const val START_BUFFER_MS = 1_000
        const val BUFFER_BYTES = 16 * 1024 * 1024
    }
}

private const val SHUTTER_MILLIS = 400
