package com.phonoodlesramen.actioncenter.ui.player

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.player.BehindLive
import com.phonoodlesramen.actioncenter.player.GuidePlayerState
import com.phonoodlesramen.actioncenter.player.Picture
import com.phonoodlesramen.actioncenter.player.TrackKind
import com.phonoodlesramen.actioncenter.player.TrackMenu
import com.phonoodlesramen.actioncenter.player.TrackOption
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's options (hold OK or Menu): what each state offers, with Add or Remove from
 * favorites (Phase 13, C2), never more than the side sheet's five rows.
 */
class PlayerOptionsTest {
    private val now = SampleGuide.WEEKNIGHT
    private val tvMix = SampleGuide.ready().channels.first { it.id == "tv-mix" }
    private val slot = tvMix.slotAt(now)!!
    private val both = TrackMenu(
        audio = listOf(TrackOption("10", "English (AC3 5.1)", true), TrackOption("11", "Commentary", false)),
        subtitles = listOf(TrackOption("20", "English (SRT)", false)),
    )
    private val opened = mutableListOf<String>()
    private val actions = object : PlayerActions {
        override fun keepWatching(slot: GuideSlot) = KeepWatching(PlayIn.PLEZY) {}

        override fun openTracks(kind: TrackKind) {
            opened += kind.name
        }

        override fun setFavorite(favorite: Boolean) {
            opened += "favorite $favorite"
        }
    }

    private fun labels(state: GuidePlayerState) = optionsSheet(state, actions).items.map { it.label }

    private fun live(tracks: TrackMenu = both, favorites: List<String> = emptyList()) =
        GuidePlayerState(channel = tvMix, slot = slot, picture = Picture.Video, tracks = tracks, favorites = favorites)

    @Test
    fun `at the live point, Watch in, Start over, the tracks in one row, Details, and Add to favorites`() {
        assertEquals(
            listOf("Watch in Plezy", "Start over", "Audio and subtitles", "Details", "Add TV Mix to favorites"),
            labels(live()),
        )
    }

    @Test
    fun `behind live, Back to live joins Start over, and a favorite offers Remove`() {
        val behind = live(favorites = listOf("tv-mix")).copy(behind = BehindLive.pausedLive(slot, now))
        assertEquals(
            listOf("Watch in Plezy", "Start over", "Back to live", "Audio and subtitles", "Details", "Remove TV Mix from favorites"),
            labels(behind),
        )
    }

    @Test
    fun `one kind of track keeps its own row, and both open on the audio`() {
        assertTrue("Audio" in labels(live(TrackMenu(audio = both.audio))))
        assertTrue("Subtitles" in labels(live(TrackMenu(subtitles = both.subtitles))))
        optionsSheet(live(), actions).items.first { it.label == "Audio and subtitles" }.run()
        assertEquals(listOf("AUDIO"), opened)
    }

    @Test
    fun `the favorites row adds or removes the channel on screen`() {
        optionsSheet(live(), actions).items.last().run()
        optionsSheet(live(favorites = listOf("tv-mix")), actions).items.last().run()
        assertEquals(listOf("favorite true", "favorite false"), opened)
    }

    @Test
    fun `no state has more than six rows, which fit at 115%`() {
        val states = listOf(true, false).flatMap { behind ->
            listOf(both, TrackMenu(), TrackMenu(audio = both.audio)).map { tracks ->
                live(tracks).let { if (behind) it.copy(behind = BehindLive.pausedLive(slot, now)) else it }
            }
        }
        states.forEach { assertTrue(labels(it).toString(), labels(it).size <= 6) }
    }
}
