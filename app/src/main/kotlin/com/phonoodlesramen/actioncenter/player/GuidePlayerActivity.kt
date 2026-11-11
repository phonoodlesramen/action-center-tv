package com.phonoodlesramen.actioncenter.player

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import coil3.SingletonImageLoader
import com.phonoodlesramen.actioncenter.ActionCenterApp
import com.phonoodlesramen.actioncenter.AppContainer
import com.phonoodlesramen.actioncenter.apps.CustomBanners
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.favorites.FavoriteChange
import com.phonoodlesramen.actioncenter.favorites.FavoriteText
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.KidGuide
import com.phonoodlesramen.actioncenter.handoff.Handoffs
import com.phonoodlesramen.actioncenter.handoff.IntentSpec
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.plex.PlexStreams
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.settings.Settings
import com.phonoodlesramen.actioncenter.ui.Toast
import com.phonoodlesramen.actioncenter.ui.ToastMessage
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import com.phonoodlesramen.actioncenter.ui.art.PlexArtUrls
import com.phonoodlesramen.actioncenter.ui.player.GuidePlayerScreen
import com.phonoodlesramen.actioncenter.ui.player.KeepWatching
import com.phonoodlesramen.actioncenter.ui.player.PlayerActions
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * Tunes in to a guide channel (G5): full screen, in the home's own task (`singleTop`), so
 * Back returns to wherever it was opened (the Live row, the full guide or the Back-to card)
 * and Home closes it, as HomeActivity is `singleTask` (U10).
 *
 * - **Keys** (over the picture nothing is focusable, so the activity reads them): Up and
 *   Down change channel (previous and next in lineup order, wrapping; Channel up/down too),
 *   Info shows the banner, OK shows it with its control row (P2), Left/Right open the mini
 *   guide (G12), holding OK or Menu opens the options, Play/pause pauses and carries on
 *   (G11), ⏪ and ⏩ skip, or scrub while held (P1), and Back finishes. The mini guide takes
 *   Up, Down and OK while it's open; the control row, the sheets and the details take the
 *   D-pad by Compose focus. Up and Down still surf with the row up.
 * - **Leaving (P3):** anything that stops it but Back (Home, Plezy, the screensaver) holds
 *   the picture where it is and saves that spot ([SpotStore]) for the home's Back-to card.
 *   Back leaves nothing saved: a spot this player saved earlier is taken back. Any tune-in
 *   to another channel forgets the spot.
 * - **The player** exists only between onStart and onStop, so no decoder or buffer is held
 *   while the home or another app is in front. It reports no playback to Plex: no timeline
 *   and no session (G6).
 * - **Guide viewing in Plex (Phase 11, amending G6):** a program watched to its credits, ¾
 *   of it this sitting, is marked watched for the profile's Plex user ([GuideTuner] counts,
 *   [GuidePlexWrites] writes, in the background).
 * - **Watch in Plezy or Plex (Phase 11):** the options' first row and the details' button
 *   hand the program off where the picture is ([keepWatching]): a resume point written first
 *   (or watched, past the credits), at most 2 s, then the app opens on it.
 * - **The credits card (K5):** OK picks it (its next episode, handed off from where Plex has
 *   it, which is the start for an episode not begun); Back takes it away.
 * - **Favorites (Phase 13):** the mini guide lists the profile's first; the options add the
 *   channel on screen to them or remove it, and a toast says so. Up and Down still surf by number.
 * - **The screen** is kept on only while video plays (G10): not while paused, and not on
 *   the cards, so the Shield's screensaver can come on.
 * - **Memory:** Coil's memory cache is emptied as it opens: the video needs the room more
 *   than the home's art does on a 2-3 GB Shield.
 * - **Never a crash:** everything that can fail ends as an off-air card ([GuideTuner]) or a toast.
 */
class GuidePlayerActivity : ComponentActivity() {
    private val container: AppContainer by lazy { (application as ActionCenterApp).container }
    private val parts: GuidePlayerParts by lazy {
        (application as? GuidePlayerParts.Provider)?.guidePlayerParts() ?: GuidePlayerParts.of(container)
    }
    private lateinit var tuner: GuideTuner
    private lateinit var views: VideoViews

    /** The wall clock for what's on screen, a tick a second while the screen is started. */
    private var now by mutableLongStateOf(0L)

    private var toast by mutableStateOf<ToastMessage?>(null)

    /** The linked server's machine id, for Watch in Plezy (D11); null leaves it out. */
    private var serverId by mutableStateOf<String?>(null)

    /** Settings › Plex › Play in (Phase 11): where Watch in and the credits card go. */
    private var playIn by mutableStateOf(PlayIn.PLEZY)

    /** OK went down with nothing open: its release shows the banner, unless holding it opened the options first. */
    private var okDown = false

    /** Holding OK opened the options: that press's repeats and release are swallowed, so they can't pick a row. */
    private var okHeld = false

    /** The ⏪ or ⏩ that went down over the picture, until it's let go (P1). */
    private var seekKey: Int? = null

    /** Back is closing the player: it saves no spot (P3). */
    private var leavingByBack = false

    /** The spot this player saved when it last stopped, so Back can take it back (P3). */
    private var savedSpot: SavedSpot? = null

    @VisibleForTesting
    internal val actions = object : PlayerActions {
        override fun startOver() = tuner.startOver()
        override fun backToLive() = tuner.backToLive()
        override fun playPause() = tuner.playPause()
        override fun skip(forward: Boolean) = tuner.skip(forward)
        override fun hideControls() = tuner.hideControls()
        override fun openTracks(kind: TrackKind) = tuner.openTracks(kind)
        override fun chooseTrack(kind: TrackKind, streamId: String?) = tuner.chooseTrack(kind, streamId)
        override fun openDetails() = tuner.openDetails()
        override fun closeOverlay() = tuner.closeOverlay()

        override fun keepWatching(slot: GuideSlot): KeepWatching? {
            val server = serverId ?: return null
            if (!GuideTuning.canStartOver(slot)) return null
            val key = slot.itemKey ?: return null
            val app = playIn
            return KeepWatching(app) { branch(app, server, key, slot) }
        }

        override fun dismissCreditsCard() = tuner.dismissCreditsCard()

        override fun playIn(): PlayIn = playIn

        override fun setFavorite(favorite: Boolean) {
            val channel = tuner.state.value.channel ?: return
            lifecycleScope.launch {
                toast = when (val change = parts.setFavorite(channel.id, favorite)) {
                    FavoriteChange.Saved -> ToastMessage(FavoriteText.changed(channel.name, favorite))
                    is FavoriteChange.Refused -> ToastMessage(change.sentence, caution = true)
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SingletonImageLoader.get(this).memoryCache?.clear()
        now = parts.clock()
        views = VideoViews(SurfaceView(this), SubtitleView(this))
        tuner = GuideTuner(
            lineup = parts.lineup,
            sources = parts.sources,
            openVideo = { events -> parts.openVideo(applicationContext, views, events) },
            clock = parts.clock,
            streams = parts.streams,
            credits = parts.credits,
            nextEpisode = parts.nextEpisode,
            // K1, K2: off this activity's scope, which Plezy or Home may end at once.
            onWatched = { slot -> slot.itemKey?.let { key -> parts.background.launch { parts.plexWrites.watched(key) } } },
            favorites = parts.favorites,
        )
        // Below every overlay's own Back (Compose registers those later, so they come first).
        onBackPressedDispatcher.addCallback(this) { leave() }
        tuner.select(intent.getStringExtra(EXTRA_CHANNEL), intent.getBooleanExtra(EXTRA_START_OVER, false), spotOf(intent))
        setContent {
            val state by tuner.state.collectAsState()
            val look by parts.look.collectAsState(initial = Settings())
            val access by parts.access.collectAsState(initial = null)
            val artUrls = remember(access) { PlexArtUrls(access) }
            ActionCenterTheme(accent = Palette.Accents.getOrElse(look.accentIndex) { Palette.DefaultAccent }, layoutScale = look.layoutScale) {
                CompositionLocalProvider(LocalArtUrls provides artUrls, LocalCustomBanners provides parts.banners) {
                    GuidePlayerScreen(state, now = { now }, actions = actions) {
                        // Sized to the file's shape, so a wide film is letterboxed rather than stretched;
                        // its subtitles sit on the picture, as the file places them.
                        val frame = Modifier.align(Alignment.Center).aspectRatio(state.videoAspect ?: WIDESCREEN)
                        AndroidView(factory = { views.surface }, modifier = frame)
                        AndroidView(factory = { views.subtitles }, modifier = frame)
                    }
                    Toast(toast, onDone = { toast = null })
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { tuner.state.map { it.playing }.distinctUntilChanged().collect(::keepScreenOn) }
                launch { parts.serverId.collect { serverId = it } }
                launch { parts.playIn.collect { playIn = it } }
                // Another channel playing forgets a spot saved on any other (P3).
                launch {
                    tuner.state.mapNotNull { it.channel?.id }.distinctUntilChanged().collect { id ->
                        parts.background.launch { parts.spots.channelPlays(id) }
                    }
                }
                while (true) {
                    now = parts.clock()
                    delay(SECOND_MILLIS - now % SECOND_MILLIS)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        tuner.start()
    }

    override fun onStop() {
        tuner.stop()
        if (!leavingByBack) saveSpot()
        keepScreenOn(false)
        okDown = false
        okHeld = false
        seekKey = null
        super.onStop()
    }

    /**
     * Stopped by anything but Back (P3): the picture the tuner held is the spot to come back
     * to. Saved outside this activity's scope, since Home ends the activity at once.
     */
    private fun saveSpot() {
        val state = tuner.state.value
        val spot = SavedSpot.of(state.channel, state.behind, parts.clock()) ?: return
        savedSpot = spot
        parts.background.launch { parts.spots.save(spot) }
    }

    /** Back over the picture: the player closes and leaves no spot, taking back one it saved when it was last stopped (P3). */
    private fun leave() {
        leavingByBack = true
        savedSpot?.let { saved -> parts.background.launch { parts.spots.clear(only = saved) } }
        savedSpot = null
        finish()
    }

    override fun onDestroy() {
        tuner.close()
        super.onDestroy()
    }

    /** `singleTop`: tuning in again from the home (or the guide) while the player is in front. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tuner.select(intent.getStringExtra(EXTRA_CHANNEL), intent.getBooleanExtra(EXTRA_START_OVER, false), spotOf(intent))
    }

    /**
     * Every key comes here first, before Compose: the held OK that opened the options must
     * never reach the sheet's row that took focus meanwhile (tv-material clicks on a release
     * alone), and nothing under a sheet may surf.
     *
     * Lint's RestrictedApi is suppressed: `Activity.dispatchKeyEvent` is public platform API,
     * flagged only because androidx's `ComponentActivity` overrides it on the way and is
     * restricted to its own library group.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (okHeld && code in OK_KEYS) {
            if (event.action == KeyEvent.ACTION_UP) okHeld = false
            return true
        }
        if (code in MEDIA_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) media(code)
            return true
        }
        if (code in SEEK_KEYS) {
            // Over the picture only: under a sheet or the mini guide they do nothing.
            if (tuner.state.value.overlay == Overlay.None) seek(event)
            return true
        }
        return when (tuner.state.value.overlay) {
            Overlay.None -> onPicture(event) || super.dispatchKeyEvent(event)
            is Overlay.MiniGuide -> onMiniGuide(event) || super.dispatchKeyEvent(event)
            // The sheets and the details take the D-pad themselves; nothing gets past them to surf.
            else -> {
                if (event.action == KeyEvent.ACTION_DOWN) tuner.keepOverlay()
                super.dispatchKeyEvent(event) || code in PLAYER_KEYS
            }
        }
    }

    /**
     * A key over the picture, nothing open. True when it's the player's; false hands it to
     * Compose: with the control row up (P2), Left/Right move along it and OK presses the
     * control that has focus.
     */
    private fun onPicture(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        val controls = tuner.state.value.showsControls
        when (event.keyCode) {
            // Up is Channel up, as in Android's own Live Channels. The mini guide's Up moves up its list instead.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (down) tuner.surf(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (down) tuner.surf(-1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                controls -> {
                    if (down) tuner.keepControls()
                    return false
                }
                first -> tuner.openMiniGuide()
            }
            in OK_KEYS -> when {
                first -> {
                    okDown = true
                    if (controls) return false
                }
                // Held (the system repeats a held key once it's a long press): the options.
                down -> if (okDown) {
                    okDown = false
                    okHeld = true
                    tuner.openOptions()
                }
                else -> {
                    val tapped = okDown
                    okDown = false
                    when {
                        !tapped -> Unit
                        // The press is the focused control's: tv-material clicks on the release.
                        controls -> {
                            tuner.keepControls()
                            return false
                        }
                        // The credits card (K5): a tap picks it; holding still opens the options.
                        tuner.state.value.creditsCard != null -> takeCreditsCard()
                        else -> tuner.showControls()
                    }
                }
            }
            KeyEvent.KEYCODE_INFO -> if (first) tuner.showBanner()
            KeyEvent.KEYCODE_MENU -> if (first) tuner.openOptions()
            else -> return false
        }
        return true
    }

    /** A key in the mini guide (G12): Up and Down move, OK tunes in; nothing surfs until then. */
    private fun onMiniGuide(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (down) tuner.moveMiniGuide(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> if (down) tuner.moveMiniGuide(+1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) tuner.keepOverlay()
            in OK_KEYS -> if (first) tuner.chooseMiniGuide()
            KeyEvent.KEYCODE_INFO -> if (first) {
                tuner.closeOverlay()
                tuner.showBanner()
            }
            KeyEvent.KEYCODE_MENU -> if (first) tuner.openOptions()
            else -> return false
        }
        return true
    }

    private fun media(code: Int) = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> tuner.playPause()
        KeyEvent.KEYCODE_MEDIA_PLAY -> tuner.resume()
        else -> tuner.pause()
    }

    /**
     * ⏪ or ⏩ over the picture (P1). A tap skips when it's let go. Held, the system repeats
     * the key: each repeat moves the banner's marker by how long it's been held (the event's
     * own times), and letting go jumps to it. Only the release ever seeks.
     */
    private fun seek(event: KeyEvent) {
        val forward = event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        when {
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> seekKey = event.keyCode
            event.action == KeyEvent.ACTION_DOWN -> if (seekKey == event.keyCode) tuner.scrub(forward, event.eventTime - event.downTime)
            event.action == KeyEvent.ACTION_UP -> {
                if (seekKey == event.keyCode) {
                    if (tuner.state.value.scrub != null) tuner.endScrub() else tuner.skip(forward)
                }
                seekKey = null
            }
        }
    }

    /**
     * Watch in Plezy or Plex (Phase 11 plan, 2): [slot]'s title handed off where the picture
     * is, by the frame on screen ([ResumeWrite]): under a minute in, nothing is written; before
     * the watched point, Plex's resume point; past it, watched, so it opens from the start.
     * The write is waited for 2 s at most; when it doesn't land, the hand-off goes ahead with
     * a toast that shows over the app ("Plezy starts where Plex last had it"). Plezy's link
     * names the profile's Plex user (K3).
     */
    private fun branch(app: PlayIn, serverId: String, ratingKey: String, slot: GuideSlot) {
        val write = ResumeWrite.of(tuner.branchPoint(slot))
        tuner.closeOverlay()
        if (write == ResumeWrite.Watched) tuner.markCounted(slot)
        lifecycleScope.launch {
            val branch = parts.plexWrites.branch(ratingKey, write)
            if (!branch.written) {
                // The player goes behind the app at once, so this toast is the system's.
                android.widget.Toast.makeText(applicationContext, "${app.label} starts where Plex last had it", android.widget.Toast.LENGTH_LONG).show()
            }
            handOff({ Handoffs.play(app, serverId, ratingKey, branch.profile) }, slot.title)
        }
    }

    /** OK on the credits card (K5): the next episode, handed off from where Plex has it (the start, for one not begun). */
    private fun takeCreditsCard() {
        val card = tuner.takeCreditsCard() ?: return
        val server = serverId ?: return
        val app = playIn
        lifecycleScope.launch {
            val branch = parts.plexWrites.branch(card.next.ratingKey, ResumeWrite.Nothing)
            handOff({ Handoffs.play(app, server, card.next.ratingKey, branch.profile) }, card.next.title)
        }
    }

    /**
     * Starts the first of [candidates] something on the Shield can open (D11), as the home's
     * Play does. The player stays underneath, so Back from Plezy returns here.
     */
    private fun handOff(candidates: () -> List<IntentSpec>, title: String) {
        tuner.closeOverlay()
        val intent = try {
            parts.resolve(candidates())
        } catch (_: IllegalArgumentException) {
            null
        }
        if (intent == null) {
            toast = ToastMessage("Nothing on this Shield can open $title")
            return
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast = ToastMessage("Couldn't open $title")
        } catch (_: SecurityException) {
            toast = ToastMessage("Not allowed to open $title")
        }
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        /** The guide channel's id ([com.phonoodlesramen.actioncenter.guide.GuideChannel.id]). */
        const val EXTRA_CHANNEL = "channel"

        /** True: the program on now from its start, behind live (G11). */
        const val EXTRA_START_OVER = "start_over"

        /** A saved spot to open, paused (P3): its slot's start, its item, the position in its file, and when it was saved. */
        const val EXTRA_SPOT_START = "spot_start"
        const val EXTRA_SPOT_KEY = "spot_key"
        const val EXTRA_SPOT_POSITION = "spot_position"
        const val EXTRA_SPOT_SAVED_AT = "spot_saved_at"

        private const val SECOND_MILLIS = 1_000L
        private const val WIDESCREEN = 16f / 9f

        private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        private val MEDIA_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
        private val SEEK_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

        /** The remote's keys the player uses: under a sheet they never reach the picture. */
        private val PLAYER_KEYS = OK_KEYS + setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
        )

        /** Start it without NEW_TASK, so it stays in the home's task and Home closes it. */
        fun intent(context: Context, channelId: String, startOver: Boolean = false): Intent =
            Intent(context, GuidePlayerActivity::class.java).putExtra(EXTRA_CHANNEL, channelId).putExtra(EXTRA_START_OVER, startOver)

        /** The player on [spot]'s channel, held on its frame behind live (P3, the Back-to card's Resume). */
        fun resume(context: Context, spot: SavedSpot): Intent = intent(context, spot.channelId)
            .putExtra(EXTRA_SPOT_START, spot.slotStartMillis)
            .putExtra(EXTRA_SPOT_KEY, spot.itemKey)
            .putExtra(EXTRA_SPOT_POSITION, spot.positionMs)
            .putExtra(EXTRA_SPOT_SAVED_AT, spot.savedAtMillis)

        /** The spot [intent] asks to resume, or null for live or Start over. */
        fun spotOf(intent: Intent): SavedSpot? {
            val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return null
            val key = intent.getStringExtra(EXTRA_SPOT_KEY) ?: return null
            if (!intent.hasExtra(EXTRA_SPOT_START) || !intent.hasExtra(EXTRA_SPOT_POSITION)) return null
            return SavedSpot(
                channelId = channel,
                slotStartMillis = intent.getLongExtra(EXTRA_SPOT_START, 0),
                itemKey = key,
                positionMs = intent.getLongExtra(EXTRA_SPOT_POSITION, 0),
                savedAtMillis = intent.getLongExtra(EXTRA_SPOT_SAVED_AT, 0),
            )
        }
    }
}

/**
 * What the player is built from: the app's container on the Shield. Tests run the activity
 * under an app that implements [Provider], with a lineup they control and a fake player.
 */
class GuidePlayerParts(
    val lineup: Flow<GuideCardState>,
    val sources: SlotSources,
    /** The linked server, for the Up next card's art. */
    val access: Flow<ServerAccess?>,
    /** Settings › Look: the accent and the layout scale. */
    val look: Flow<Settings>,
    val openVideo: (Context, VideoViews, GuideVideo.Events) -> GuideVideo,
    val clock: () -> Long = System::currentTimeMillis,
    /** Each program's audio and subtitle streams from Plex (G13). */
    val streams: SlotStreams = SlotStreams.None,
    /** The linked server's machine id, for Watch in Plezy (D11); while it's null the options and the details leave it out. */
    val serverId: Flow<String?> = flowOf(null),
    /** The first of a handoff's intents that something on the Shield can open, or null. */
    val resolve: (List<IntentSpec>) -> Intent? = { null },
    /** Where the player was left (P3): saved when it stops, but not by Back. */
    val spots: SpotStore = SpotStore.None,
    /** Where saving and forgetting the spot, and counting a program watched, run: they outlive the activity, which Home ends at once. */
    val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Settings › Plex › Play in, as it stands on this TV (Phase 11, K4). */
    val playIn: Flow<PlayIn> = flowOf(PlayIn.PLEZY),
    /** Each program's credits marker (Phase 11): the watched point and the credits card. */
    val credits: SlotCredits = SlotCredits.None,
    /** An episode's next one, from the cache (K5). */
    val nextEpisode: suspend (itemKey: String) -> NextEpisode? = { null },
    /** Guide viewing and the branch's writes to Plex, for the profile's Plex user (Phase 11, K3). */
    val plexWrites: GuidePlexWrites = GuidePlexWrites.None,
    /** The profile's favorite channels, in its order (Phase 13): the mini guide's first. */
    val favorites: Flow<List<String>> = flowOf(emptyList()),
    /** The options' Add to favorites or Remove (C2). */
    val setFavorite: suspend (channelId: String, favorite: Boolean) -> FavoriteChange = { _, _ -> FavoriteChange.Saved },
    /** The Icons folder, for the channels' own icons (I3). */
    val banners: CustomBanners = CustomBanners.None,
) {
    fun interface Provider {
        fun guidePlayerParts(): GuidePlayerParts
    }

    companion object {
        /** The Shield's parts. */
        fun of(container: AppContainer) = GuidePlayerParts(
            // K8: a kid's channel up/down and mini guide stay inside their ceiling.
            lineup = combine(container.guide.card, container.currentCeiling, KidGuide::of),
            sources = PlexSlotSources(container.playables, container.plexAccess),
            access = container.plexAccess,
            look = container.settings.settings,
            openVideo = ::ExoGuideVideo,
            streams = PlexSlotStreams(PlexStreams(container.http, container.playables.headers), container.plexAccess),
            serverId = container.plexServerId,
            resolve = { specs -> container.intentProbe.firstResolvable(specs)?.let { container.intentResolver.toIntent(it) } },
            spots = container.spots,
            background = container.appScope,
            playIn = container.playIn,
            credits = container.slotCredits,
            // K4: a kid's card never offers an episode above their ceiling.
            nextEpisode = { key -> container.nextEpisodes.after(key, container.currentCeiling.first()) },
            plexWrites = container.guidePlexWrites,
            favorites = container.channelFavorites.current,
            setFavorite = container.channelFavorites::set,
            banners = container.customBanners,
        )
    }
}
