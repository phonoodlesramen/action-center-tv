package com.phonoodlesramen.actioncenter.player

import android.util.Log
import com.phonoodlesramen.actioncenter.favorites.FavoriteOrder
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideEngine
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.plex.PlexStream
import com.phonoodlesramen.actioncenter.safety.summary
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update as updateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Why the off-air card shows. */
enum class OffAirReason {
    /** The lineup is empty: the library hasn't synced, or nothing fills a channel. */
    NO_CHANNELS,

    /** A gap in the schedule, or past its end. */
    NOTHING_ON,

    /** The slot is marked off air (its file is gone), or Plex has no file for it; the card says why. */
    OFF_AIR,

    /** Plex answered, but couldn't give the program's file right now (its drive is off or restarting, say). Tried again. */
    UNAVAILABLE,

    /** A file the Shield can't play (its format, its decoder, its DRM): only the next program helps. */
    CANT_PLAY,

    /** Plex can't be reached. Tried again. */
    OFFLINE,
    NOT_LINKED,
}

/** What fills the screen under the banner. */
sealed interface Picture {
    /** Opening the file: black, with the banner, so a slow join reads as tuning. */
    data object Tuning : Picture

    data object Video : Picture

    /** Filler without a trailer, or a file that ended before its slot: the next program and a countdown. */
    data object UpNext : Picture

    /**
     * Nothing plays: until the next slot, or until it's tried again ([OffAirReason.OFFLINE],
     * [OffAirReason.UNAVAILABLE]). [detail] is the slot's own reason when it's off air. The
     * player's error code goes to the log, never on screen.
     */
    data class OffAir(val reason: OffAirReason, val detail: String? = null) : Picture
}

/** What's open over the picture, besides the banner and the cards (2.7). */
sealed interface Overlay {
    data object None : Overlay

    /** The mini guide (G12): the lineup around the channel playing, with [focusedId] picked out. */
    data class MiniGuide(val focusedId: String) : Overlay

    /** The options sheet (hold OK or Menu). */
    data object Options : Overlay

    /** The options' Audio or Subtitles list. */
    data class Tracks(val kind: TrackKind) : Overlay

    /** The program's details, over the picture. */
    data object Details : Overlay
}

data class GuidePlayerState(
    val channel: GuideChannel? = null,
    /** What's on screen: the program on now, or the one watched behind live; null in a gap in the schedule. */
    val slot: GuideSlot? = null,
    /** The next program, filler skipped: the banner's "Next" and the cards' countdown. */
    val next: GuideSlot? = null,
    val picture: Picture = Picture.Tuning,
    val banner: Boolean = false,
    /** Frames are moving: the only time the screen is held on (G10). */
    val playing: Boolean = false,
    /** The picture's width over its height, once the file says. */
    val videoAspect: Float? = null,
    /** Started over or paused (G11); null at the live point. */
    val behind: BehindLive? = null,
    /** The lineup in number order: Up and Down surf through it. */
    val lineup: List<GuideChannel> = emptyList(),
    /** The profile's favorite channels, in its order (Phase 13): they lead the mini guide, and the options add or remove the one on screen. */
    val favorites: List<String> = emptyList(),
    val overlay: Overlay = Overlay.None,
    /** The options' Audio and Subtitles for the file playing; empty until its tracks are known. */
    val tracks: TrackMenu = TrackMenu(),
    /** The lineup's state while there are no channels ([OffAirReason.NO_CHANNELS]): the off-air card says what it waits on. */
    val lineupState: GuideCardState? = null,
    /** OK asked for the banner's control row (P2): ⏪ 10, ⏯, ⏩ 30 and Back to live. It goes with the banner. */
    val controls: Boolean = false,
    /** ⏪ or ⏩ is held (P1): the banner's marker shows where letting go will land. */
    val scrub: Scrub? = null,
    /** The credits card (Phase 11, K5): over an episode's credits, its show's next episode, for 15 s. */
    val creditsCard: CreditsCard? = null,
    /** The pick-up card (P5): a program you were watching on this channel and left, for 15 s as it tunes in. */
    val pickUp: PickUpCard? = null,
) {
    /** The program on screen has a file to play from its start (G11): not filler, not off air, not behind a card. */
    val canStartOver: Boolean
        get() = slot != null && GuideTuning.canStartOver(slot) && (picture == Picture.Video || picture == Picture.Tuning)

    /** The program on screen can be sought in (P1): a program with a file whose frames show. Not filler, a card or a gap. */
    val canSeek: Boolean
        get() = slot != null && GuideTuning.canStartOver(slot) && picture == Picture.Video

    /**
     * The control row is on screen (P2): the banner's up with it, over a program with a file
     * (its frames showing, or opening after Back to live), and nothing's open.
     */
    val showsControls: Boolean
        get() = controls && banner && canStartOver && overlay == Overlay.None

    /** The program the options' Details shows: the one on screen, or the next one while filler is on. */
    val detailsSlot: GuideSlot? get() = slot?.takeIf { !it.filler } ?: next

    /** The mini guide's list (C4): the favorites first, in the profile's order, then the rest by number. */
    val miniGuide: List<GuideChannel> get() = FavoriteOrder.ordered(lineup, favorites)

    /** The channel on screen is one of the profile's favorites. */
    val favorite: Boolean get() = channel?.id?.let { it in favorites } == true
}

/**
 * ⏪ or ⏩ held (P1): the marker at [targetMs] in the program's file, moving at ×[speed]
 * from [fromMs], where the picture was when the hold began. Nothing seeks until it's let go.
 */
data class Scrub(val forward: Boolean, val speed: Int, val fromMs: Long, val targetMs: Long)

/**
 * The guide's player without the screen (G5, plan §3): it tunes a channel in at the right
 * point of the program on now, follows the schedule from slot to slot, surfs, and turns
 * every failure into an off-air card. Nothing it does throws: an uncaught exception would
 * put the launcher into safe mode (CrashGuard).
 *
 * - **Tune in:** the slot on now from [GuideTuning.startPosition]. The banner shows.
 * - **Handoff:** 30 s before a slot ends, the next one's file is found and opened
 *   ([GuideVideo.prepareNext]); at the boundary it plays ([GuideTuning.handoff]).
 * - **Surf:** Up/Down change channel at once (the banner follows), but the file opens only
 *   once the presses stop for [GuideTuning.SURF_DEBOUNCE_MILLIS].
 * - **Filler:** a trailer plays its own file; an Up next slot shows the Up next card.
 * - **Errors:** each shows its off-air card ([VideoError.reasonFor]). When Plex can't be
 *   reached, or the file can't be had right now (a NAS drive restarting), the program is
 *   tuned in again every [GuideTuning.RETRY_MILLIS], where it is by then, while it's still
 *   on ([retryLater]). A file the Shield can't play waits for the next program. Filler that
 *   fails shows the Up next card instead; behind live, a failure rejoins live.
 * - **Behind live (G11):** Start over plays the program on now from its start, and
 *   Play/pause pauses ([BehindLive]). The handoff is for live only: behind, the program
 *   plays on past its slot, and when it ends the channel rejoins live, whatever's on now.
 *   Surfing, choosing a channel and Back to live rejoin at once.
 * - **Tracks (G13, G14):** each program's streams are read from Plex once ([SlotStreams])
 *   and handed to the video with its file; a choice from the options is for that file only.
 * - **The mini guide (G12)** and the options are overlays in [GuidePlayerState.overlay]. The
 *   mini guide lists the profile's [favorites] first (Phase 13, C4); surfing stays by number.
 * - **Seeking (P1):** ⏪ and ⏩ tapped skip back 10 s or forward 30 s ([skip]); held, they
 *   move the banner's marker ([scrub]) and letting go jumps there ([endScrub]). Only in a
 *   program whose frames show, between its start and live ([Seeking]). Back from live is
 *   behind live (G11); forward to live is Back to live.
 * - **The control row (P2):** OK puts it on the banner ([showControls]); each press in it
 *   keeps the banner up another 5 s ([keepControls]).
 * - **Stopped (P3):** a program playing with a file is held where it is, behind live and
 *   paused, so coming back, or the home's Back-to card ([SavedSpot]), carries on from there.
 *   [select] with a spot opens it that way.
 * - **Guide viewing in Plex (Phase 11, K1, K2):** per program per sitting, it adds up the
 *   time the program's frames played ([GuideViewing]), at each change of state and every
 *   [GuideViewing.TICK_MILLIS]. Once playback reaches the watched point with ¾ of the
 *   program played, [onWatched] is told, once. Filler, cards and a program without a file
 *   never count. Each program's credits marker is asked of [credits] once, off the main
 *   thread; until it answers, nothing counts.
 * - **The credits card (K5):** in an episode's credits, its show's next episode
 *   ([nextEpisode]) shows for [GuideViewing.CARD_MILLIS], once per program. A channel
 *   change, an overlay or the next program takes it away; [takeCreditsCard] picks it.
 *
 * - **Keeping your place (P5, [PlaceRules]):** leaving a program you were watching (a surf,
 *   another channel chosen, the player stopping) keeps its place for the sitting, and tells
 *   [onLeftOff] when you'd seen it from its start. Back on its channel within 2 minutes, it
 *   carries on from there ([resumePlace]); later, or for the home's place ([kept]), the
 *   pick-up card offers it ([takePickUp]). [placeToKeep] is the place Back keeps.
 *
 * The video exists only between [start] and [stop] (the activity's ON_START and ON_STOP).
 *
 * @param lineup the guide's lineup, from the cache.
 * @param favorites the profile's favorite channels, in its order (Phase 13).
 * @param openVideo makes the player; it reports back through the [GuideVideo.Events] it's given.
 * @param context where everything runs: the main thread on the Shield, a test dispatcher in tests.
 */
class GuideTuner(
    private val lineup: Flow<GuideCardState>,
    private val sources: SlotSources,
    private val openVideo: (GuideVideo.Events) -> GuideVideo,
    context: CoroutineContext = Dispatchers.Main.immediate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(PlaybackCheck.LOG_TAG, it) },
    private val streams: SlotStreams = SlotStreams.None,
    private val credits: SlotCredits = SlotCredits.None,
    private val nextEpisode: suspend (itemKey: String) -> NextEpisode? = { null },
    private val onWatched: (GuideSlot) -> Unit = {},
    private val favorites: Flow<List<String>> = flowOf(emptyList()),
    /** The place the home keeps (P3, P5): back on its channel, the pick-up card offers it. */
    private val kept: Flow<SavedSpot?> = flowOf(null),
    /** Left a program you'd seen from its start, short of its watched point (P5): where Plex's resume point goes. */
    private val onLeftOff: (GuideSlot, Long) -> Unit = { _, _ -> },
) : GuideVideo.Events {
    private val mutableState = MutableStateFlow(GuidePlayerState())
    val state: StateFlow<GuidePlayerState> = mutableState.asStateFlow()

    /** Anything missed below lands here, as an off-air card rather than a crash. */
    private val scope = CoroutineScope(
        context + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            log("player: ${e.summary()}")
            behind = null
            stopVideo()
            picture(Picture.OffAir(OffAirReason.CANT_PLAY))
        },
    )

    private var started = false
    private var channelId: String? = null

    /** Null until the lineup's first value after [start]. */
    private var channels: List<GuideChannel>? = null
    private var video: GuideVideo? = null
    private var lineupJob: Job? = null
    private var favoritesJob: Job? = null
    private var tuneJob: Job? = null
    private var bannerJob: Job? = null
    private var overlayJob: Job? = null

    /** The slot the screen is showing, so a lineup update only retunes when what's on changes. */
    private var onAir: GuideSlot? = null

    /** A slot's file and streams, found ahead of its boundary. */
    private class Prepared(val slot: GuideSlot, val source: PlaybackSource, val streams: List<PlexStream>?)

    /** The next slot's file, found ahead of its boundary. */
    private var prepared: Prepared? = null

    /** The file last asked to play, so Start over plays it again without looking it up. */
    private var opened: Pair<GuideSlot, PlaybackSource>? = null

    /** Each program's streams from Plex, kept for the last few: read once per slot (G13). */
    private val streamCache = LinkedHashMap<String, List<PlexStream>>()

    /** A file was asked to play and hasn't ended or failed since, so its events count. */
    private var videoActive = false

    /** A surf is waiting out its debounce: lineup updates leave it be. */
    private var settling = false

    /** What the intent asked for besides live: Start over (G11) or a saved spot (P3). */
    private sealed interface Pending {
        data object StartOver : Pending

        data class Resume(val spot: SavedSpot) : Pending
    }

    /** Asked for before the lineup was known (the intent's extras): it happens once it is. */
    private var pending: Pending? = null

    /** A program Plex couldn't give, being tried again ([retryLater]): [count] is the try that's next or under way. */
    private class Retry(val slot: GuideSlot, val count: Int)

    private var retry: Retry? = null

    /**
     * A program's viewing this sitting (Phase 11): how long its frames played, whether it's been
     * counted or carded, and whether you chose it (P5): started it over, paused, skipped or picked it up.
     */
    private class Viewing(var watchedMs: Long = 0, var counted: Boolean = false, var carded: Boolean = false, var chose: Boolean = false)

    /** The last few programs' viewing, by [programId]: surfing away and back keeps what was watched. */
    private val viewings = LinkedHashMap<String, Viewing>()

    /** Programs you were watching and left this sitting (P5), by [programId], oldest first: back on their channel, they're picked up. */
    private val places = LinkedHashMap<String, Place>()

    /** The home's place ([kept]), as last read. */
    private var keptSpot: SavedSpot? = null
    private var keptJob: Job? = null
    private var pickUpJob: Job? = null

    /** When the channel last tuned in, for the home's place arriving a moment after it ([offerKeptLate]). */
    private var tunedAtMillis: Long? = null

    /** The program whose frames were playing at the last [sample], and when that was. */
    private var sampling: Viewing? = null
    private var sampledAt = 0L

    /** Each program's credits marker, once [credits] has answered (null: none). A key that's absent isn't known yet. */
    private val creditsByKey = LinkedHashMap<String, Long?>()
    private val creditsAsked = mutableSetOf<String>()
    private var viewingJob: Job? = null
    private var cardJob: Job? = null

    /** Behind live (G11), mirrored in the state for the banner. Kept across [stop], paused, so coming back carries on. */
    private var behind: BehindLive? = null
        set(value) {
            field = value
            update { it.copy(behind = value) }
        }

    /**
     * The channel to show ([GuideChannel.id]); the first in the lineup when null or gone. Tunes
     * in at once when started: live, with [startOver] the program on now from its start (G11),
     * or with [resume] the saved spot's frame, paused behind live (P3). A spot whose program
     * has gone from the lineup, or whose file can't be had, tunes in live.
     */
    fun select(channelId: String?, startOver: Boolean = false, resume: SavedSpot? = null) {
        if (started) leaveProgram(clock())
        this.channelId = channelId
        behind = null
        setOverlay(Overlay.None)
        dropCreditsCard()
        dropPickUp()
        update { it.copy(controls = false, scrub = null) }
        pending = when {
            resume != null -> Pending.Resume(resume)
            startOver -> Pending.StartOver
            else -> null
        }
        val list = channels?.takeIf { it.isNotEmpty() } ?: return
        val channel = current(list)
        present(channel)
        showBanner()
        if (!takePending() && !pickUp(channel, clock())) tune()
    }

    /** Opens the video and tunes in from the lineup. */
    fun start() {
        if (started) return
        started = true
        video = try {
            openVideo(this)
        } catch (e: Exception) {
            log("player: couldn't open the player: ${e.summary()}")
            null
        }
        sampledAt = clock()
        viewingJob = scope.launch {
            while (true) {
                delay(GuideViewing.TICK_MILLIS)
                sample()
                checkViewing()
            }
        }
        lineupJob = scope.launch {
            lineup
                .catch { e ->
                    log("player: lineup: ${e.summary()}")
                    emit(GuideCardState.NotSetUp)
                }
                .distinctUntilChanged()
                .collect(::onLineup)
        }
        favoritesJob = scope.launch {
            favorites
                .catch { e ->
                    log("player: favorites: ${e.summary()}")
                    emit(emptyList())
                }
                .distinctUntilChanged()
                .collect { ids -> update { it.copy(favorites = ids) } }
        }
        keptJob = scope.launch {
            kept
                .catch { e ->
                    log("player: kept place: ${e.summary()}")
                    emit(null)
                }
                .distinctUntilChanged()
                .collect { spot ->
                    keptSpot = spot
                    offerKeptLate()
                }
        }
    }

    /**
     * Stops everything and releases the video: no decoder or buffer is held while the screen
     * is away. The program on screen, if it has a file and its frames show, stays where it
     * is, paused behind live (P3), so coming back (Back from Plezy, say) carries on from
     * there, and the home can offer it ([SavedSpot.of]). Filler, a card or a gap tune in
     * afresh.
     */
    fun stop() {
        if (!started) return
        // What played up to now counts, and may reach the watched point (Home during the credits).
        sample()
        checkViewing()
        // P5: a program you were watching tells Plex where you left it, however the player stops.
        leaveProgram(clock())
        started = false
        holdForLater()
        viewingJob?.cancel()
        dropCreditsCard()
        dropPickUp()
        lineupJob?.cancel()
        favoritesJob?.cancel()
        keptJob?.cancel()
        tuneJob?.cancel()
        bannerJob?.cancel()
        overlayJob?.cancel()
        settling = false
        videoActive = false
        retry = null
        try {
            video?.release()
        } catch (e: Exception) {
            log("player: release: ${e.summary()}")
        }
        video = null
        channels = null
        onAir = null
        prepared = null
        opened = null
        update {
            it.copy(
                picture = Picture.Tuning, banner = false, playing = false, videoAspect = null, overlay = Overlay.None, tracks = TrackMenu(),
                controls = false, scrub = null,
            )
        }
    }

    /** On [stop]: the picture held where it is, behind live and paused, if it's a program with a file whose frames show (P3). */
    private fun holdForLater() {
        val current = behind
        if (current != null) {
            if (!current.paused) behind = current.pausedAt(clock(), position())
            return
        }
        val slot = onAir ?: return
        if (!videoActive || mutableState.value.picture != Picture.Video || !GuideTuning.canStartOver(slot)) return
        val now = clock()
        behind = BehindLive.pausedLive(slot, now, position())
        log("player: channel $channelId, held at ${(behind?.positionAt(now) ?: 0) / 1_000} s on stopping")
    }

    /** Ends the tuner for good (the activity's onDestroy). */
    fun close() {
        stop()
        scope.cancel()
    }

    /** Up (+1) or Down (-1): the next or previous channel in lineup order, wrapping. Its file opens once the presses stop. */
    fun surf(step: Int) {
        val list = channels?.takeIf { it.isNotEmpty() } ?: return
        val target = GuideTuning.channelAfter(list, channelId, step) ?: return
        // P5: a program you were watching is kept, so surfing back picks it up.
        leaveProgram(clock())
        channelId = target.id
        behind = null
        dropCreditsCard()
        dropPickUp()
        stopVideo()
        onAir = null
        present(target)
        // A new channel's banner is a surf's: no control row.
        update { it.copy(picture = Picture.Tuning, videoAspect = null, controls = false, scrub = null) }
        showBanner()
        tune(afterMillis = GuideTuning.SURF_DEBOUNCE_MILLIS)
    }

    /**
     * The banner (Info, and every channel change), for [GuideTuning.BANNER_MILLIS]; while
     * paused it stays. The control row, when it's up, stays and goes with it.
     */
    fun showBanner() {
        val channel = mutableState.value.channel
        if (channel == null || mutableState.value.overlay != Overlay.None) return
        // Behind live, Next moves on while you're away: work it out again for the banner.
        if (behind != null) present(channel)
        update { it.copy(banner = true) }
        bannerJob?.cancel()
        if (behind?.paused == true) return
        bannerJob = scope.launch {
            delay(GuideTuning.BANNER_MILLIS)
            update { it.copy(banner = false, controls = false) }
        }
    }

    /**
     * OK over the picture (P2): the banner with its control row, Play/pause focused. Over
     * filler, a card or a gap there's nothing to control: just the banner.
     */
    fun showControls() {
        val state = mutableState.value
        if (state.channel == null || state.overlay != Overlay.None) return
        if (state.canStartOver) update { it.copy(controls = true) }
        showBanner()
    }

    /** A press in the control row (P2): it stays, and the banner with it, another [GuideTuning.BANNER_MILLIS]. */
    fun keepControls() {
        if (mutableState.value.showsControls) showBanner()
    }

    /** Back with the control row up (P2): the row goes, and the banner with it unless paused. */
    fun hideControls() {
        update { it.copy(controls = false) }
        if (behind?.paused == true) return
        bannerJob?.cancel()
        update { it.copy(banner = false) }
    }

    /**
     * ⏪ or ⏩ tapped (P1): back [Seeking.BACK_MILLIS] or forward [Seeking.FORWARD_MILLIS] in
     * the program on screen, no further back than its start; forward as far as live is Back
     * to live. Nothing happens on filler, a card or a gap.
     */
    fun skip(forward: Boolean) {
        val slot = seekable() ?: return
        val now = clock()
        seek(slot, Seeking.skip(picturePosition(slot, now), forward, Seeking.liveAt(slot, now)), now)
    }

    /**
     * ⏪ or ⏩ held for [heldMillis] (P1): the banner's marker moves, faster the longer it's
     * held ([Seeking.scrubTo]); the picture carries on as it was until [endScrub]. Live
     * there's nothing ahead to scrub to, so ⏩ just shows the banner.
     */
    fun scrub(forward: Boolean, heldMillis: Long) {
        val slot = seekable() ?: return
        if (forward && behind == null) return showBanner()
        val now = clock()
        val from = mutableState.value.scrub?.takeIf { it.forward == forward }?.fromMs ?: picturePosition(slot, now)
        val target = Seeking.scrubTo(from, forward, heldMillis, Seeking.liveAt(slot, now))
        bannerJob?.cancel()
        update { it.copy(banner = true, scrub = Scrub(forward, Seeking.speedAt(heldMillis), from, target)) }
    }

    /** ⏪ or ⏩ let go after a hold (P1): the picture jumps to the marker, the only seek a hold makes. */
    fun endScrub() {
        val scrub = mutableState.value.scrub ?: return
        update { it.copy(scrub = null) }
        val slot = seekable() ?: return showBanner()
        seek(slot, scrub.targetMs, clock())
    }

    /** The program on screen, if it can be sought in now: its frames show and nothing's open over it. */
    private fun seekable(): GuideSlot? {
        val state = mutableState.value
        if (video == null || !videoActive || !state.canSeek || state.overlay != Overlay.None) return null
        return state.slot
    }

    /** Where the picture is in [slot]'s file: the player's own position, else where behind live or the schedule says. */
    private fun picturePosition(slot: GuideSlot, now: Long): Long = position() ?: behind?.positionAt(now) ?: slot.offsetAt(now)

    /**
     * Moves the picture to [target] in [slot] (P1): behind live, playing or held as it was
     * (G11), so the banner says how far behind; the schedule waits until the program ends
     * at this pace. At live or past it, Back to live.
     */
    private fun seek(slot: GuideSlot, target: Long, now: Long) {
        if (target >= Seeking.liveAt(slot, now)) {
            if (behind != null) rejoin() else showBanner()
            return
        }
        val held = behind?.paused == true
        val sought = BehindLive(slot, target.coerceAtLeast(0), now, paused = held, startedOver = false)
        behind = sought
        viewing(slot).chose = true
        onAir = slot
        log("player: channel $channelId, sought to ${sought.positionMs / 1_000} s, ${sought.behindAt(now) / 1_000} s behind live")
        try {
            video?.seekTo(sought.positionMs)
        } catch (e: Exception) {
            log("player: seek: ${e.summary()}")
        }
        showBanner()
        followBehind(open = false)
    }

    /** Start over (G11): the program on screen from its start, behind live until it ends. */
    fun startOver() {
        setOverlay(Overlay.None)
        startOverNow()
    }

    /** Back to live (G11): whatever's on now on the channel. */
    fun backToLive() {
        setOverlay(Overlay.None)
        if (behind != null) rejoin()
    }

    /** Play/pause on the remote. */
    fun playPause() = if (behind?.paused == true) resume() else pause()

    /**
     * Holds the picture (G11). The schedule waits: nothing is opened ahead or switched to,
     * and the banner stays, saying how far behind live it is. The screen isn't held on (G10).
     */
    fun pause() {
        val video = video ?: return
        val current = behind
        if (!videoActive || mutableState.value.picture != Picture.Video || current?.paused == true) return
        val slot = current?.slot ?: onAir ?: return
        val now = clock()
        val position = position()
        behind = current?.pausedAt(now, position) ?: BehindLive.pausedLive(slot, now, position)
        viewing(slot).chose = true
        log("player: paused, ${(behind?.behindAt(now) ?: 0) / 1_000} s behind live")
        tuneJob?.cancel()
        settling = false
        prepared = null
        try {
            video.pause()
        } catch (e: Exception) {
            log("player: pause: ${e.summary()}")
        }
        update { it.copy(playing = false) }
        showBanner()
    }

    /** Carries on from the paused picture, behind live, until the program ends (G11). */
    fun resume() {
        val current = behind?.takeIf { it.paused } ?: return
        behind = current.resumedAt(clock())
        try {
            video?.resume()
        } catch (e: Exception) {
            log("player: resume: ${e.summary()}")
        }
        showBanner()
        followBehind(open = false)
    }

    /** The options' Audio or Subtitles choice (G14): this program only, nothing saved or sent to Plex. */
    fun chooseTrack(kind: TrackKind, streamId: String?) {
        setOverlay(Overlay.None)
        if (!videoActive) return
        try {
            video?.selectTrack(kind, streamId)
        } catch (e: Exception) {
            log("player: track: ${e.summary()}")
        }
    }

    /** Left/Right: the mini guide (G12), on the channel playing. It closes by itself after [GuideTuning.MINI_GUIDE_MILLIS]. */
    fun openMiniGuide() {
        val channel = mutableState.value.channel ?: return
        if (channels.isNullOrEmpty()) return
        setOverlay(Overlay.MiniGuide(channel.id))
    }

    /** Up (-1) or Down (+1) in the mini guide: the channel above or below in its list (favorites first), wrapping. Nothing tunes until OK. */
    fun moveMiniGuide(step: Int) {
        val open = mutableState.value.overlay as? Overlay.MiniGuide ?: return
        if (channels.isNullOrEmpty()) return
        val target = GuideTuning.channelAfter(mutableState.value.miniGuide, open.focusedId, step) ?: return
        setOverlay(Overlay.MiniGuide(target.id))
    }

    /** A key on an overlay (Left or Right in the mini guide, any in a sheet) keeps it open a while longer. */
    fun keepOverlay() {
        mutableState.value.overlay.takeIf { it != Overlay.None }?.let(::setOverlay)
    }

    /** OK in the mini guide: tunes in to the channel picked out, or just closes on the one playing. */
    fun chooseMiniGuide() {
        val open = mutableState.value.overlay as? Overlay.MiniGuide ?: return
        setOverlay(Overlay.None)
        if (open.focusedId != channelId) select(open.focusedId) else showBanner()
    }

    /** Hold OK or Menu: the options sheet. */
    fun openOptions() {
        if (mutableState.value.channel != null) setOverlay(Overlay.Options)
    }

    /** The options' Audio or Subtitles list, when the file's tracks are known. */
    fun openTracks(kind: TrackKind) {
        if (mutableState.value.tracks.of(kind).isNotEmpty()) setOverlay(Overlay.Tracks(kind))
    }

    /** The options' Details, over the picture. */
    fun openDetails() {
        if (mutableState.value.detailsSlot != null) setOverlay(Overlay.Details)
    }

    /** Back, or an action that's done: the picture again (and the banner, while paused). */
    fun closeOverlay() {
        if (mutableState.value.overlay == Overlay.None) return
        setOverlay(Overlay.None)
        if (behind?.paused == true) showBanner()
    }

    override fun onPlaying(playing: Boolean) {
        if (!videoActive) {
            if (!playing) update { it.copy(playing = false) }
            return
        }
        // A report from before a pause.
        if (playing && behind?.paused == true) return
        if (playing) {
            retry?.let { log("player: channel $channelId playing again after ${it.count} ${if (it.count == 1) "retry" else "retries"}") }
            retry = null
        }
        update { it.copy(playing = playing, picture = if (playing) Picture.Video else it.picture) }
    }

    override fun onEnded() {
        if (!videoActive) return
        videoActive = false
        if (behind != null) {
            // The program watched behind live has ended: back to what's on now (G11).
            rejoin()
            return
        }
        // The file is shorter than its slot: the next program and a countdown until the boundary.
        val slot = onAir
        val upNext = slot == null || GuideTuning.endedEarly(slot, clock())
        update { it.copy(playing = false, picture = if (upNext) Picture.UpNext else it.picture) }
    }

    override fun onError(error: VideoError) {
        log("player: ${error.code}")
        if (!videoActive) {
            // The next file, opened early: it's tried again at its own slot.
            prepared = null
            return
        }
        if (behind != null) {
            // Behind live, there's no slot to wait for: try live again.
            rejoin()
            return
        }
        val slot = onAir
        if (slot != null) {
            cantPlay(slot, error.reason)
        } else {
            stopVideo()
            picture(Picture.OffAir(error.reason))
        }
    }

    override fun onVideoSize(width: Int, height: Int, pixelWidthHeightRatio: Float) {
        val aspect = GuideTuning.aspect(width, height, pixelWidthHeightRatio) ?: return
        update { it.copy(videoAspect = aspect) }
    }

    override fun onTracks(menu: TrackMenu) {
        if (videoActive) update { it.copy(tracks = menu) }
    }

    private fun onLineup(card: GuideCardState) {
        val list = (card as? GuideCardState.Ready)?.channels.orEmpty()
        val hadChannels = !channels.isNullOrEmpty()
        channels = list
        if (list.isEmpty()) {
            tuneJob?.cancel()
            bannerJob?.cancel()
            settling = false
            behind = null
            stopVideo()
            onAir = null
            prepared = null
            overlayJob?.cancel()
            update { GuidePlayerState(picture = Picture.OffAir(OffAirReason.NO_CHANNELS), lineupState = card, favorites = it.favorites) }
            return
        }
        update { it.copy(lineup = list) }
        // The channel watched behind live has left the lineup: live on the first channel instead.
        if (behind != null && list.none { it.id == channelId }) behind = null
        val channel = current(list)
        val now = clock()
        val shown = onAir
        when {
            !hadChannels -> {
                present(channel, now)
                showBanner()
                when {
                    pending != null && takePending() -> Unit
                    behind != null -> followBehind(open = true)
                    else -> if (!pickUp(channel, now)) tune()
                }
            }
            // Behind live, the program plays on whatever the schedule does.
            behind != null -> present(channel, now)
            tuneJob?.isActive != true -> {
                present(channel, now)
                showBanner()
                tune()
            }
            settling -> Unit
            // What's on changed under the screen (a gap filled, a slot replaced). At a slot's end
            // the schedule moves on by itself, with the next file already open: leave it be.
            channel.slotAt(now) != shown && (shown == null || now < shown.endMillis) -> tune()
            // The same program: only the channel's later slots may have changed.
            else -> present(channel, now)
        }
    }

    /** Shows [channel] and what's on it at [now] (or the program watched behind live), before its file opens. */
    private fun present(channel: GuideChannel, now: Long = clock()) {
        val behind = behind
        val slot = behind?.slot ?: channel.slotAt(now)
        // Behind live, Next is what's on when the program on screen ends and the channel
        // rejoins live (G11), not what followed it in the schedule, which may be long over.
        val next = if (behind != null) {
            GuideTuning.programAt(channel, behind.rejoinsLiveAt(now))
        } else {
            GuideTuning.nextProgram(channel, slot?.endMillis ?: now)
        }
        update { state ->
            state.copy(
                channel = channel, slot = slot, next = next,
                creditsCard = state.creditsCard?.takeIf { slot != null && same(it.program, slot) },
                pickUp = state.pickUp?.takeIf { it.place.channelId == channel.id },
            )
        }
    }

    /** The selected channel, or the first when it's gone from the lineup. */
    private fun current(list: List<GuideChannel>): GuideChannel =
        (list.firstOrNull { it.id == channelId } ?: list.first()).also { channelId = it.id }

    /** Follows the selected channel's schedule live, after [afterMillis] (a surf's debounce). */
    private fun tune(afterMillis: Long = 0) {
        tuneJob?.cancel()
        prepared = null
        retry = null
        settling = afterMillis > 0
        tuneJob = scope.launch {
            if (afterMillis > 0) delay(afterMillis)
            settling = false
            // A surf that settles on a channel with a program you were watching (P5): picked up at once, or offered.
            val settled = channels?.takeIf { afterMillis > 0 && it.isNotEmpty() }?.let { current(it) }
            // Picked up, [followBehind] has taken over this job.
            if (settled != null && pickUp(settled, clock())) return@launch
            followSchedule()
        }
    }

    /** Each slot in turn: shown, its successor opened ahead of the boundary, then switched at it. Ends only when cancelled. */
    private suspend fun followSchedule() {
        while (true) {
            val list = channels?.takeIf { it.isNotEmpty() } ?: return
            val channel = current(list)
            val now = clock()
            val slot = channel.slotAt(now)
            onAir = slot
            present(channel, now)
            if (slot == null) {
                stopVideo()
                picture(Picture.OffAir(OffAirReason.NOTHING_ON))
                val wake = channel.slots.firstOrNull { it.startMillis > now }?.startMillis ?: (now + RECHECK_MILLIS)
                delay(wake - now)
                continue
            }
            show(slot)
            // A program that failed and is to be tried again: [retryLater] has replaced this job.
            currentCoroutineContext().ensureActive()
            val handoff = GuideTuning.handoff(slot, clock())
            delay(handoff.prepareAtMillis - clock())
            GuideTuning.following(channel, slot)?.let { prepare(it) }
            delay(handoff.switchAtMillis - clock())
        }
    }

    private suspend fun show(slot: GuideSlot) {
        val key = slot.itemKey
        if (retry?.slot != slot) retry = null
        when {
            slot.offAir != null -> {
                stopVideo()
                picture(Picture.OffAir(OffAirReason.OFF_AIR, slot.offAir))
            }
            key == null -> {
                // Up next filler (G3): the card, and the next program's file opens before it ends.
                stopVideo()
                picture(Picture.UpNext)
            }
            else -> {
                val ready = prepared?.takeIf { it.slot == slot }
                prepared = null
                if (ready == null) {
                    stopVideo()
                    // Tried again, the card stays up until frames move, rather than flashing "Tuning in…".
                    if (retry == null) picture(Picture.Tuning)
                }
                when (val found = ready?.source?.let(SlotSource::Found) ?: sources.find(key)) {
                    is SlotSource.Found -> play(slot, found.source, if (ready != null) ready.streams else streamsFor(slot))
                    // As the guide says it once the lineup knows (the Shield isn't to blame).
                    SlotSource.NoFile -> cantPlay(slot, OffAirReason.OFF_AIR, GuideEngine.GONE)
                    SlotSource.NotLinked -> cantPlay(slot, OffAirReason.NOT_LINKED)
                    SlotSource.Offline -> cantPlay(slot, OffAirReason.OFFLINE)
                }
            }
        }
    }

    private fun play(slot: GuideSlot, source: PlaybackSource, streams: List<PlexStream>?) {
        if (video == null) return cantPlay(slot, OffAirReason.CANT_PLAY)
        val start = GuideTuning.startPosition(slot, clock(), source.durationMs)
        if (start == null) {
            // Nothing left of the file (a trailer joined after it ended): the card until the boundary.
            stopVideo()
            picture(Picture.UpNext)
            return
        }
        log("player: channel $channelId, ${slot.itemKey} from ${start / 1_000} s")
        // A file opened ahead switches without a gap, so the picture only changes once it
        // plays; tried again, the card stays until then.
        if (mutableState.value.picture != Picture.Video && retry == null) picture(Picture.Tuning)
        open(slot, source, start, streams)
    }

    /** Asks the video to play [source] from [startMs] (held at once when [paused]); its events count from here. */
    private fun open(slot: GuideSlot, source: PlaybackSource, startMs: Long, streams: List<PlexStream>?, paused: Boolean = false) {
        val video = video ?: return cantPlay(slot, OffAirReason.CANT_PLAY)
        videoActive = true
        opened = slot to source
        askCredits(slot)
        // A track list belongs to the file it was opened on.
        if (mutableState.value.overlay is Overlay.Tracks) setOverlay(Overlay.None)
        update { it.copy(tracks = TrackMenu()) }
        try {
            video.play(source, startMs, streams)
            if (paused) video.pause()
        } catch (e: Exception) {
            log("player: play: ${e.summary()}")
            behind = null
            cantPlay(slot, OffAirReason.CANT_PLAY)
        }
    }

    /** Opens the next slot's file early, so the switch at the boundary is quick. */
    private suspend fun prepare(next: GuideSlot) {
        val key = next.itemKey ?: return
        if (next.offAir != null) return
        val found = sources.find(key) as? SlotSource.Found ?: return
        if (GuideTuning.startPosition(next, next.startMillis, found.source.durationMs) == null) return
        val streams = streamsFor(next)
        prepared = Prepared(next, found.source, streams)
        try {
            video?.prepareNext(found.source, streams)
        } catch (e: Exception) {
            log("player: prepare: ${e.summary()}")
        }
    }

    /**
     * [slot]'s audio and subtitle streams from Plex (G13): read once and kept for the last few
     * programs. Filler has none worth asking for. A slow or failed answer is null: the file
     * plays on Media3's own choice rather than waiting.
     */
    private suspend fun streamsFor(slot: GuideSlot): List<PlexStream>? {
        val key = slot.itemKey?.takeIf { !slot.filler } ?: return null
        streamCache[key]?.let { return it }
        val found = withTimeoutOrNull(GuideTuning.STREAMS_TIMEOUT_MILLIS) { streams.find(key) } ?: return null
        streamCache[key] = found
        while (streamCache.size > STREAM_CACHE_SIZE) streamCache.remove(streamCache.keys.first())
        return found
    }

    /**
     * Starts the program on screen over (G11), if it has a file: from its start, behind live.
     * False when there's nothing to start over (filler, off air, a gap).
     */
    private fun startOverNow(): Boolean {
        val list = channels?.takeIf { it.isNotEmpty() } ?: run {
            pending = Pending.StartOver
            return false
        }
        pending = null
        val channel = current(list)
        val now = clock()
        val slot = behind?.slot ?: channel.slotAt(now)
        if (slot == null || !GuideTuning.canStartOver(slot)) return false
        log("player: channel $channelId, ${slot.itemKey} started over")
        dropPickUp()
        behind = BehindLive.startedOver(slot, now)
        viewing(slot).chose = true
        onAir = slot
        present(channel, now)
        showBanner()
        followBehind(open = true)
        return true
    }

    /** What the intent asked for, once the lineup is known: false when it tunes in live after all. */
    private fun takePending(): Boolean = when (val asked = pending) {
        null -> false
        Pending.StartOver -> startOverNow()
        is Pending.Resume -> resumeNow(asked.spot)
    }

    /**
     * Opens a saved spot (P3): its program's file held on the frame where it was left, behind
     * live and paused, with the banner saying how far. Play carries on. False, and live, when
     * the program has left the channel's schedule or lost its file; a file Plex can't give
     * now rejoins live too ([followBehind]), with the usual cards.
     */
    private fun resumeNow(spot: SavedSpot): Boolean {
        val list = channels?.takeIf { it.isNotEmpty() } ?: run {
            pending = Pending.Resume(spot)
            return false
        }
        pending = null
        val channel = current(list)
        val slot = channel.takeIf { it.id == spot.channelId }?.slots?.firstOrNull(spot::isFor)
        if (slot == null || !GuideTuning.canStartOver(slot)) {
            log("player: channel $channelId, the saved program is gone, so live")
            return false
        }
        val now = clock()
        behind = spot.behindLive(slot, now)
        log("player: channel $channelId, ${slot.itemKey} resumed paused at ${spot.positionMs / 1_000} s")
        onAir = slot
        present(channel, now)
        showBanner()
        followBehind(open = true)
        return true
    }

    /**
     * Watches [behind] until its program ends, then rejoins live (G11). With [open], its file
     * opens first at the picture's place: played, or held when paused (coming back after a stop).
     * While paused nothing waits: [resume] starts this again.
     */
    private fun followBehind(open: Boolean) {
        tuneJob?.cancel()
        settling = false
        prepared = null
        retry = null
        tuneJob = scope.launch {
            val start = behind ?: return@launch
            if (open) {
                val slot = start.slot
                val key = slot.itemKey ?: return@launch rejoin()
                val reuse = opened?.takeIf { it.first == slot }?.second
                if (reuse == null) {
                    stopVideo()
                    picture(Picture.Tuning)
                }
                val source = reuse ?: (sources.find(key) as? SlotSource.Found)?.source ?: return@launch rejoin()
                // Held, the first frame shows under the banner; played, it shows once it moves.
                if (start.paused) picture(Picture.Video) else if (mutableState.value.picture != Picture.Video) picture(Picture.Tuning)
                open(slot, source, start.positionAt(clock()), streamsFor(slot), paused = start.paused)
            }
            val end = behind?.endsAtMillis ?: return@launch
            delay(end - clock())
            rejoin()
        }
    }

    /** Back to live (G11): whatever's on now on the channel, from its live point. */
    private fun rejoin() {
        behind = null
        update { it.copy(scrub = null, creditsCard = null) }
        val list = channels?.takeIf { it.isNotEmpty() } ?: return
        log("player: channel $channelId back to live")
        present(current(list))
        showBanner()
        tune()
    }

    /**
     * [slot] can't play, for [reason]: its off-air card, until the next slot or, when Plex or
     * the file may be back soon, until it's tried again. Filler that can't play just shows the
     * Up next card: it isn't worth an off-air card, or a retry.
     */
    private fun cantPlay(slot: GuideSlot, reason: OffAirReason, detail: String? = null) {
        stopVideo()
        if (slot.filler) return picture(Picture.UpNext)
        picture(Picture.OffAir(reason, detail))
        if (reason == OffAirReason.OFFLINE || reason == OffAirReason.UNAVAILABLE) retryLater(slot)
    }

    /**
     * Tunes [slot] in again in [GuideTuning.RETRY_MILLIS], the way tuning in does (where it is
     * by then), so a NAS drive or a Plex server that's back brings the program back. The tune
     * job is replaced by one that waits for the try, so there's one at a time, and changing
     * channel, stopping or starting over cancels it. A try that would come after the next
     * program's file opens ahead isn't made: the schedule carries on, and the next program
     * tunes in as usual. Logged by count only.
     */
    private fun retryLater(slot: GuideSlot) {
        val now = clock()
        val at = now + GuideTuning.RETRY_MILLIS
        if (at >= GuideTuning.handoff(slot, now).prepareAtMillis) return
        val count = (retry?.takeIf { it.slot == slot }?.count ?: 0) + 1
        retry = Retry(slot, count)
        tuneJob?.cancel()
        settling = false
        prepared = null
        tuneJob = scope.launch {
            delay(at - clock())
            log("player: channel $channelId, retry $count")
            followSchedule()
        }
    }

    private fun stopVideo() {
        videoActive = false
        opened = null
        try {
            video?.stop()
        } catch (e: Exception) {
            log("player: stop: ${e.summary()}")
        }
        update { it.copy(playing = false, tracks = TrackMenu()) }
    }

    /** Where the video is in its file, if it can say. */
    private fun position(): Long? = try {
        video?.positionMs()
    } catch (e: Exception) {
        log("player: position: ${e.summary()}")
        null
    }

    /**
     * Opens or closes an overlay. The banner gives way to it. Left alone, each closes itself:
     * the mini guide soon (G12), a sheet or the details after a while, so nothing sits still
     * over a playing picture for long (G10).
     */
    private fun setOverlay(overlay: Overlay) {
        overlayJob?.cancel()
        if (overlay != Overlay.None) bannerJob?.cancel()
        if (overlay != Overlay.None) {
            dropCreditsCard()
            dropPickUp()
        }
        update {
            if (overlay == Overlay.None) it.copy(overlay = overlay) else it.copy(overlay = overlay, banner = false, controls = false, scrub = null)
        }
        val idle = when (overlay) {
            Overlay.None -> return
            is Overlay.MiniGuide -> GuideTuning.MINI_GUIDE_MILLIS
            else -> GuideTuning.OVERLAY_IDLE_MILLIS
        }
        overlayJob = scope.launch {
            delay(idle)
            closeOverlay()
        }
    }

    private fun picture(picture: Picture) = update { it.copy(picture = picture) }

    /** Every change to the state: frames that played up to it count to their program (Phase 11). */
    private inline fun update(change: (GuidePlayerState) -> GuidePlayerState) {
        mutableState.updateFlow(change)
        sample()
    }

    // Phase 11: guide viewing in Plex, the branch and the credits card.

    /**
     * Where the picture is in [slot] when the viewer branches into Plezy or Plex: null when
     * it isn't the program on screen with its frames showing (the details of the next
     * program, say), so nothing is written.
     */
    fun branchPoint(slot: GuideSlot): BranchPoint? {
        val state = mutableState.value
        val shown = state.slot ?: return null
        if (!same(shown, slot) || !GuideTuning.canStartOver(slot) || !videoActive || state.picture != Picture.Video) return null
        sample()
        val duration = durationOf(slot)
        return BranchPoint(picturePosition(slot, clock()), GuideViewing.watchedPoint(duration, creditsByKey[slot.itemKey]))
    }

    /** The branch marked [slot] watched itself: it isn't counted again this sitting. */
    fun markCounted(slot: GuideSlot) {
        if (GuideTuning.canStartOver(slot)) viewing(slot).counted = true
    }

    /**
     * OK on the credits card (K5): the card goes and is handed back, for its next episode's
     * hand-off. What played of the program on screen counts first, so leaving at the credits
     * still marks it watched. Null when there's no card.
     */
    fun takeCreditsCard(): CreditsCard? {
        val card = mutableState.value.creditsCard ?: return null
        sample()
        checkViewing()
        dropCreditsCard()
        return card
    }

    /** Back on the credits card: it goes, and the channel carries on. */
    fun dismissCreditsCard() = dropCreditsCard()

    private fun dropCreditsCard() {
        cardJob?.cancel()
        cardJob = null
        if (mutableState.value.creditsCard != null) update { it.copy(creditsCard = null) }
    }

    /**
     * Adds the time since the last sample to the program whose frames were playing then,
     * and notes which program plays now: one with a file whose frames are moving.
     */
    private fun sample() {
        val now = clock()
        sampling?.let { it.watchedMs += (now - sampledAt).coerceAtLeast(0) }
        sampledAt = now
        val state = mutableState.value
        val slot = state.slot
        val playing = started && videoActive && state.playing && state.picture == Picture.Video
        sampling = if (playing && slot != null && GuideTuning.canStartOver(slot)) viewing(slot) else null
    }

    /** Counts the program playing once it's watched (K2), and offers its next episode at its credits (K5). */
    private fun checkViewing() {
        val viewing = sampling ?: return
        val slot = mutableState.value.slot ?: return
        val key = slot.itemKey ?: return
        // Until Plex says where its credits are, the watched point isn't known.
        if (!creditsByKey.containsKey(key)) return
        val credits = creditsByKey[key]
        val duration = durationOf(slot)
        val position = picturePosition(slot, clock())
        if (!viewing.counted && GuideViewing.counts(position, viewing.watchedMs, duration, credits)) {
            viewing.counted = true
            log("player: channel $channelId, $key watched, ${viewing.watchedMs / 60_000} of ${duration / 60_000} min")
            // Watched to its end, there's no place to come back to (P5).
            places.remove(programId(slot))
            onWatched(slot)
        }
        if (!viewing.carded && slot.showKey != null && GuideViewing.inCredits(position, duration, credits)) {
            viewing.carded = true
            offerNext(slot, key)
        }
    }

    /** The credits card (K5), once [slot]'s next episode is found; not over an overlay, nor once the program has changed. */
    private fun offerNext(slot: GuideSlot, key: String) {
        cardJob?.cancel()
        cardJob = scope.launch {
            val next = quietly { withTimeoutOrNull(GuideViewing.CREDITS_TIMEOUT_MILLIS) { nextEpisode(key) } } ?: return@launch
            val state = mutableState.value
            if (state.slot?.let { same(it, slot) } != true || state.overlay != Overlay.None) return@launch
            update { it.copy(creditsCard = CreditsCard(slot, next)) }
            delay(GuideViewing.CARD_MILLIS)
            update { it.copy(creditsCard = null) }
        }
    }

    /** Asks for [slot]'s credits marker, once per program. */
    private fun askCredits(slot: GuideSlot) {
        val key = slot.itemKey?.takeIf { GuideTuning.canStartOver(slot) } ?: return
        if (creditsByKey.containsKey(key) || !creditsAsked.add(key)) return
        scope.launch {
            try {
                val found = quietly { withTimeoutOrNull(GuideViewing.CREDITS_TIMEOUT_MILLIS) { credits.find(key) } }
                creditsByKey[key] = found
                while (creditsByKey.size > KEPT) creditsByKey.remove(creditsByKey.keys.first())
            } finally {
                creditsAsked.remove(key)
            }
        }
    }

    // P5: keeping your place.

    /**
     * Back over the picture (P5): the place in the program on screen, as the home keeps it,
     * when you were watching it; null when you weren't (surfing past), so Back leaves nothing.
     */
    fun placeToKeep(): SavedSpot? {
        val place = keptPlace(clock()) ?: return null
        val key = place.slot.itemKey ?: return null
        return SavedSpot(place.channelId, place.slot.startMillis, key, place.positionMs, place.leftAtMillis)
    }

    /** OK on the pick-up card (P5): the program from where you left it, playing. False when there's no card. */
    fun takePickUp(): Boolean {
        val card = mutableState.value.pickUp ?: return false
        val list = channels?.takeIf { it.isNotEmpty() } ?: return false
        val channel = list.firstOrNull { it.id == card.place.channelId } ?: return false
        resumePlace(channel, card.place, clock())
        return true
    }

    /** Back on the pick-up card: it goes, and the channel carries on live. */
    fun dismissPickUp() = dropPickUp()

    /**
     * The program on screen as a place at [now], when you were watching it ([PlaceRules.watching]):
     * one with a file whose frames show, or held behind live, and not yet watched to its end.
     */
    private fun keptPlace(now: Long): Place? {
        sample()
        val channel = channelId ?: return null
        val slot = behind?.slot ?: onAir?.takeIf { videoActive && mutableState.value.picture == Picture.Video } ?: return null
        if (!GuideTuning.canStartOver(slot)) return null
        val viewing = viewings[programId(slot)] ?: return null
        if (viewing.counted || !PlaceRules.watching(viewing.watchedMs, viewing.chose)) return null
        return Place(channel, slot, behind?.positionAt(now) ?: position() ?: slot.offsetAt(now), now)
    }

    /**
     * Leaving the program on screen (P5): a surf, another channel chosen, or the player
     * stopping. When you were watching it, it's kept for this sitting; when you'd seen it from
     * its start, Plex is told where you left it ([PlaceRules.leftOff]).
     */
    private fun leaveProgram(now: Long) {
        val place = keptPlace(now) ?: return
        val id = programId(place.slot)
        places.remove(id)
        places[id] = place
        while (places.size > KEPT) places.remove(places.keys.first())
        val viewing = viewings[id] ?: return
        val key = place.slot.itemKey ?: return
        // Until Plex says where its credits are, the watched point is 90%.
        val watchedAt = GuideViewing.watchedPoint(durationOf(place.slot), creditsByKey[key])
        PlaceRules.leftOff(place.positionMs, viewing.watchedMs, watchedAt)?.let { onLeftOff(place.slot, it) }
    }

    /**
     * Tuning in to [channel] (P5): a program you were watching on it and left, still in its
     * schedule with something to pick up, is picked up at once when you're back within
     * [PlaceRules.QUICK_RETURN_MILLIS] this sitting, and otherwise offered on the pick-up card
     * while the channel tunes in live. True when it was picked up, so nothing tunes in live.
     */
    private fun pickUp(channel: GuideChannel, now: Long): Boolean {
        tunedAtMillis = now
        val sitting = placeInSitting(channel, now)
        if (sitting != null && PlaceRules.quickReturn(sitting.leftAtMillis, now)) {
            resumePlace(channel, sitting, now)
            return true
        }
        val place = sitting ?: keptPlaceOn(channel, now) ?: return false
        offerPickUp(place)
        return false
    }

    /** The newest place this sitting on [channel] still worth picking up at [now], with its slot as the schedule has it now. */
    private fun placeInSitting(channel: GuideChannel, now: Long): Place? = places.values.toList().asReversed()
        .firstNotNullOfOrNull { place -> scheduled(channel, place.slot)?.takeIf { place.channelId == channel.id }?.let { place.copy(slot = it) } }
        ?.takeIf { PlaceRules.missed(it.positionMs, it.slot.offsetAt(now)) }

    /** The home's place (P3), when it's on [channel] and still worth picking up at [now]. */
    private fun keptPlaceOn(channel: GuideChannel, now: Long): Place? {
        val spot = keptSpot?.takeIf { it.channelId == channel.id } ?: return null
        val slot = channel.slots.firstOrNull { spot.isFor(it) }?.takeIf { GuideTuning.canStartOver(it) } ?: return null
        return Place(channel.id, slot, spot.positionMs, spot.savedAtMillis, spot.room).takeIf { PlaceRules.missed(it.positionMs, slot.offsetAt(now)) }
    }

    /** [slot] in [channel]'s schedule as it is now, while it's there with a file. */
    private fun scheduled(channel: GuideChannel, slot: GuideSlot): GuideSlot? =
        channel.slots.firstOrNull { same(it, slot) }?.takeIf { GuideTuning.canStartOver(it) }

    /** Carries on from [place] (P5): its program behind live, playing, the banner saying it was picked up. */
    private fun resumePlace(channel: GuideChannel, place: Place, now: Long) {
        dropPickUp()
        places.remove(programId(place.slot))
        val length = (place.slot.endMillis - place.slot.startMillis).coerceAtLeast(0)
        behind = BehindLive(place.slot, place.positionMs.coerceIn(0, length), now, paused = false, startedOver = false, pickedUp = true)
        viewing(place.slot).chose = true
        log("player: channel ${channel.id}, ${place.slot.itemKey} picked up at ${place.positionMs / 1_000} s")
        onAir = place.slot
        present(channel, now)
        showBanner()
        followBehind(open = true)
    }

    /** The pick-up card for [place], for [PlaceRules.CARD_MILLIS]; the channel plays on live under it. */
    private fun offerPickUp(place: Place) {
        pickUpJob?.cancel()
        update { it.copy(pickUp = PickUpCard(place)) }
        pickUpJob = scope.launch {
            delay(PlaceRules.CARD_MILLIS)
            update { it.copy(pickUp = null) }
        }
    }

    private fun dropPickUp() {
        pickUpJob?.cancel()
        pickUpJob = null
        if (mutableState.value.pickUp != null) update { it.copy(pickUp = null) }
    }

    /**
     * The home's place is read from storage, so it can arrive a moment after the channel tuned
     * in live: it's offered then, while the tune-in is fresh and nothing's been picked up.
     */
    private fun offerKeptLate() {
        val list = channels?.takeIf { it.isNotEmpty() } ?: return
        val tuned = tunedAtMillis ?: return
        val now = clock()
        if (behind != null || settling || mutableState.value.pickUp != null || now - tuned > PlaceRules.CARD_MILLIS) return
        keptPlaceOn(current(list), now)?.let { offerPickUp(it) }
    }

    private fun viewing(slot: GuideSlot): Viewing {
        val id = programId(slot)
        val viewing = viewings.getOrPut(id) { Viewing() }
        while (viewings.size > KEPT) viewings.remove(viewings.keys.first { it != id })
        return viewing
    }

    /** [slot]'s length: its file's, when it's the one open, else the slot's. */
    private fun durationOf(slot: GuideSlot): Long =
        opened?.takeIf { same(it.first, slot) }?.second?.durationMs?.takeIf { it > 0 } ?: (slot.endMillis - slot.startMillis)

    /** A lookup's answer, or null when it failed: a background lookup never ends as an off-air card. */
    private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("player: ${e.summary()}")
        null
    }

    private fun programId(slot: GuideSlot) = "${slot.itemKey}@${slot.startMillis}"

    /** The same program, whatever else a lineup update changed about its slot. */
    private fun same(a: GuideSlot, b: GuideSlot) = a.itemKey == b.itemKey && a.startMillis == b.startMillis

    private companion object {
        /** Past the end of the schedule, look again this often (the lineup also retunes when it grows). */
        const val RECHECK_MILLIS = 60_000L

        /** The programs whose streams are kept: the one on, the one opened ahead, and a surf back. */
        const val STREAM_CACHE_SIZE = 4

        /** The programs whose viewing and credits are kept (Phase 11): a sitting's surfing back and forth. */
        const val KEPT = 8
    }
}
