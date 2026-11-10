package com.phonoodlesramen.actioncenter.ui.player

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.player.BehindLive
import com.phonoodlesramen.actioncenter.player.CreditsCard
import com.phonoodlesramen.actioncenter.player.GuidePlayerState
import com.phonoodlesramen.actioncenter.player.GuideTuning
import com.phonoodlesramen.actioncenter.player.NextEpisode
import com.phonoodlesramen.actioncenter.player.OffAirReason
import com.phonoodlesramen.actioncenter.player.Overlay
import com.phonoodlesramen.actioncenter.player.Picture
import com.phonoodlesramen.actioncenter.player.Scrub
import com.phonoodlesramen.actioncenter.player.TrackKind
import com.phonoodlesramen.actioncenter.player.TrackMenu
import com.phonoodlesramen.actioncenter.player.TrackOption
import com.phonoodlesramen.actioncenter.ui.TV_1080P
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import com.phonoodlesramen.actioncenter.ui.theme.ActionCenterTheme
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The guide player's overlays over a black picture (no video under Robolectric), from the
 * sample weeknight lineup, at the Shield's 1080p TV configuration. Times are UTC. Phase 7
 * adds the banner's control row (live, and behind live at 100 and 115%) and a scrub's
 * marker. Record with `.\gradlew.bat recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TV_1080P)
class GuidePlayerScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val minute = 60_000L
    private val lineup: GuideCardState.Ready = SampleGuide.ready()

    /** What the tuner shows on [channelId] at [now]. */
    private fun state(channelId: String, now: Long, picture: Picture, banner: Boolean): GuidePlayerState {
        val channel = lineup.channels.single { it.id == channelId }
        val slot = channel.slotAt(now)
        return GuidePlayerState(channel, slot, GuideTuning.nextProgram(channel, slot?.endMillis ?: now), picture, banner)
    }

    private fun show(state: GuidePlayerState, now: Long, scale: Float = LayoutScale.DEFAULT, actions: PlayerActions = PlayerActions.None) = compose.setContent {
        ActionCenterTheme(layoutScale = scale) {
            GuidePlayerScreen(state, now = { now }, zone = ZoneOffset.UTC, actions = actions)
        }
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")

    @Test
    fun banner() {
        // TV Mix at 8:41: Ridge Line, 41 minutes left, Kitchen Table next.
        show(state("tv-mix", SampleGuide.WEEKNIGHT, Picture.Video, banner = true), SampleGuide.WEEKNIGHT)
        capture("player_banner")
    }

    @Test
    fun bannerAtLargestScale() {
        show(state("tv-mix", SampleGuide.WEEKNIGHT, Picture.Video, banner = true), SampleGuide.WEEKNIGHT, scale = LayoutScale.MAX)
        capture("player_banner_scale_115")
    }

    @Test
    fun upNext() {
        // Comedy at 8:55, between Open House episodes: the next one starts at 9:00.
        val now = SampleGuide.WEEKNIGHT + 14 * minute
        show(state("comedy", now, Picture.UpNext, banner = false), now)
        capture("player_up_next")
    }

    @Test
    fun offAir() {
        // Classics at 12:41 AM: Harbor Lights' file is gone. Just tuned in, so the banner shows too.
        val now = SampleGuide.WEEKNIGHT + 4 * 60 * minute
        show(state("classics", now, Picture.OffAir(OffAirReason.OFF_AIR, "This title is no longer in your library"), banner = true), now)
        capture("player_off_air")
    }

    // The player's errors, in plain words (the 28 Oct 2026 report): no code on the card.

    @Test
    fun offAirUnavailable() {
        // Movies at 8:41: Quiet Field's file is on a NAS drive that's restarting, so Plex answers with an error.
        show(state("movies", SampleGuide.WEEKNIGHT, Picture.OffAir(OffAirReason.UNAVAILABLE), banner = false), SampleGuide.WEEKNIGHT)
        capture("player_off_air_unavailable")
    }

    @Test
    fun offAirOffline() {
        // TV Mix at 8:41: the Plex server is off.
        show(state("tv-mix", SampleGuide.WEEKNIGHT, Picture.OffAir(OffAirReason.OFFLINE), banner = false), SampleGuide.WEEKNIGHT)
        capture("player_off_air_offline")
    }

    @Test
    fun offAirCantPlay() {
        // Classics at 8:41: The Silver Coast is in a format the Shield can't decode.
        show(state("classics", SampleGuide.WEEKNIGHT, Picture.OffAir(OffAirReason.CANT_PLAY), banner = false), SampleGuide.WEEKNIGHT)
        capture("player_off_air_cant_play")
    }

    @Test
    fun noChannels() {
        show(GuidePlayerState(picture = Picture.OffAir(OffAirReason.NO_CHANNELS)), SampleGuide.WEEKNIGHT)
        capture("player_no_channels")
    }

    // 2.7: behind live, the options, the mini guide.

    /** TV Mix at 8:53, paused at 8:41 in Ridge Line: 12 minutes behind live. */
    private fun behindLive(): Pair<GuidePlayerState, Long> {
        val now = SampleGuide.WEEKNIGHT + 12 * minute
        val live = state("tv-mix", SampleGuide.WEEKNIGHT, Picture.Video, banner = true)
        val paused = BehindLive.pausedLive(live.slot!!, SampleGuide.WEEKNIGHT)
        return live.copy(behind = paused) to now
    }

    @Test
    fun behindLiveBanner() {
        val (state, now) = behindLive()
        show(state, now)
        capture("player_behind_live")
    }

    @Test
    fun behindLiveBannerAtLargestScale() {
        val (state, now) = behindLive()
        show(state, now, scale = LayoutScale.MAX)
        capture("player_behind_live_scale_115")
    }

    private val tracks = TrackMenu(
        audio = listOf(
            TrackOption("10", "English (TRUEHD 7.1)", selected = true),
            TrackOption("11", "Commentary (English AC3 Stereo)", selected = false),
            TrackOption("12", "Deutsch (AC3 5.1)", selected = false),
        ),
        subtitles = listOf(TrackOption("20", "English Forced (PGS)", selected = true), TrackOption("21", "English (SRT)", selected = false)),
    )

    @Test
    fun options() {
        // Hold OK on TV Mix at 8:41: Ridge Line has a file and its tracks are known.
        show(state("tv-mix", SampleGuide.WEEKNIGHT, Picture.Video, banner = false).copy(overlay = Overlay.Options, tracks = tracks), SampleGuide.WEEKNIGHT)
        capture("player_options")
    }

    @Test
    fun audioList() {
        show(
            state("tv-mix", SampleGuide.WEEKNIGHT, Picture.Video, banner = false).copy(overlay = Overlay.Tracks(TrackKind.AUDIO), tracks = tracks),
            SampleGuide.WEEKNIGHT,
        )
        capture("player_audio")
    }

    /** Left on TV Mix at 8:41, then Down to Classics. */
    private fun miniGuide() = state("tv-mix", SampleGuide.WEEKNIGHT, Picture.Video, banner = false)
        .copy(lineup = lineup.channels, overlay = Overlay.MiniGuide("classics"))

    @Test
    fun miniGuideStrip() {
        show(miniGuide(), SampleGuide.WEEKNIGHT)
        capture("player_mini_guide")
    }

    @Test
    fun miniGuideStripAtLargestScale() {
        show(miniGuide(), SampleGuide.WEEKNIGHT, scale = LayoutScale.MAX)
        capture("player_mini_guide_scale_115")
    }

    // Phase 7: the control row (P2) and the scrub's marker (P1).

    /** OK on TV Mix at 8:41, live: ⏪ 10, Pause (focused) and ⏩ 30. */
    @Test
    fun controls() {
        show(state("tv-mix", SampleGuide.WEEKNIGHT, Picture.Video, banner = true).copy(controls = true), SampleGuide.WEEKNIGHT)
        capture("player_controls")
    }

    /** OK while paused 12 minutes behind live: Play (focused), and Back to live. */
    @Test
    fun controlsBehindLive() {
        val (state, now) = behindLive()
        show(state.copy(controls = true), now)
        capture("player_controls_behind_live")
    }

    @Test
    fun controlsBehindLiveAtLargestScale() {
        val (state, now) = behindLive()
        show(state.copy(controls = true), now, scale = LayoutScale.MAX)
        capture("player_controls_behind_live_scale_115")
    }

    /** ⏪ held for 3 s on TV Mix at 8:41: ×4, the marker 2 minutes back, where letting go lands. */
    @Test
    fun scrubbing() {
        val now = SampleGuide.WEEKNIGHT
        val live = state("tv-mix", now, Picture.Video, banner = true)
        val from = live.slot!!.offsetAt(now)
        show(live.copy(scrub = Scrub(forward = false, speed = 4, fromMs = from, targetMs = from - 2 * minute)), now)
        capture("player_scrubbing")
    }

    // Phase 11: Watch in Plezy or Plex, and the credits card (K5).

    /** The server's known: the options' first row hands the program off to [app]. */
    private fun watchIn(app: PlayIn) = object : PlayerActions {
        override fun keepWatching(slot: GuideSlot) = KeepWatching(app) {}

        override fun playIn() = app
    }

    @Test
    fun optionsWatchInPlezy() {
        show(state("tv-mix", SampleGuide.WEEKNIGHT, Picture.Video, banner = false).copy(overlay = Overlay.Options, tracks = tracks), SampleGuide.WEEKNIGHT, actions = watchIn(PlayIn.PLEZY))
        capture("player_options_plezy")
    }

    @Test
    fun optionsWatchInPlezyAtLargestScale() {
        show(
            state("tv-mix", SampleGuide.WEEKNIGHT, Picture.Video, banner = false).copy(overlay = Overlay.Options, tracks = tracks),
            SampleGuide.WEEKNIGHT,
            scale = LayoutScale.MAX,
            actions = watchIn(PlayIn.PLEZY),
        )
        capture("player_options_plezy_scale_115")
    }

    /** Behind live: Watch in Plex, Start over, Back to live, Audio and subtitles, Details, and Add TV Mix to favorites (Phase 13): six rows. */
    @Test
    fun optionsWatchInPlex() {
        val (paused, now) = behindLive()
        show(paused.copy(banner = false, overlay = Overlay.Options, tracks = tracks), now, actions = watchIn(PlayIn.PLEX))
        capture("player_options_plex")
    }

    @Test
    fun optionsWatchInPlexAtLargestScale() {
        val (paused, now) = behindLive()
        show(paused.copy(banner = false, overlay = Overlay.Options, tracks = tracks), now, scale = LayoutScale.MAX, actions = watchIn(PlayIn.PLEX))
        capture("player_options_plex_scale_115")
    }

    /** TV Mix at 9:21, Ridge Line's credits: its next episode, from the cache. */
    private fun credits(): Pair<GuidePlayerState, Long> {
        val now = SampleGuide.WEEKNIGHT + 40 * minute
        val live = state("tv-mix", now, Picture.Video, banner = false)
        val next = NextEpisode("sample-ridge-line-s3e2", "S3 E2", "Snowmelt", art = "/library/metadata/sample-ridge-line-s3e2/thumb")
        return live.copy(creditsCard = CreditsCard(live.slot!!, next)) to now
    }

    @Test
    fun creditsCard() {
        val (state, now) = credits()
        show(state, now, actions = watchIn(PlayIn.PLEZY))
        capture("player_credits_card")
    }

    @Test
    fun creditsCardAtLargestScale() {
        val (state, now) = credits()
        show(state, now, scale = LayoutScale.MAX, actions = watchIn(PlayIn.PLEZY))
        capture("player_credits_card_scale_115")
    }

    /** The card with the banner up (Info during the credits), in Plex. */
    @Test
    fun creditsCardWithBanner() {
        val (state, now) = credits()
        show(state.copy(banner = true), now, actions = watchIn(PlayIn.PLEX))
        capture("player_credits_card_banner_plex")
    }
}
