package com.phonoodlesramen.actioncenter.ui.player

import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.player.BehindLive
import com.phonoodlesramen.actioncenter.player.OffAirReason
import com.phonoodlesramen.actioncenter.player.Scrub
import com.phonoodlesramen.actioncenter.ui.guide.NoChannelsYet
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTextTest {
    private val utc = ZoneOffset.UTC
    private val now = SampleGuide.WEEKNIGHT // 8:41 PM UTC
    private val minute = 60_000L
    private val halfPastEight = now - 11 * minute

    private fun slot(start: Long, minutes: Int, episode: String? = null, rating: String? = null) =
        GuideSlot(start, start + minutes * minute, "Ridge Line", episode, "k", null, null, rating, false, null)

    @Test
    fun `times read as the top bar's clock`() {
        assertEquals("8:41 PM", PlayerText.clock(now, utc))
        assertEquals("8:30 – 9:22 PM", PlayerText.timeRange(halfPastEight, halfPastEight + 52 * minute, utc))
        // Across midnight, both ends say which half of the day.
        assertEquals("11:30 PM – 1:02 AM", PlayerText.timeRange(halfPastEight + 180 * minute, halfPastEight + 272 * minute, utc))
    }

    @Test
    fun `time left is rounded up, in the home cards' words`() {
        assertEquals("41m left", PlayerText.left(slot(halfPastEight, 52), now))
        assertEquals("1h 50m left", PlayerText.left(slot(halfPastEight, 121), now))
        assertEquals("1m left", PlayerText.left(slot(halfPastEight, 52), halfPastEight + 52 * minute - 5_000))
    }

    @Test
    fun `behind live, the banner says how and how far, and time left counts from the picture`() {
        val ridgeLine = slot(halfPastEight, 52)
        val startedOver = BehindLive.startedOver(ridgeLine, now)
        assertEquals("Started over · 11 min behind live", PlayerText.behindEyebrow(startedOver, now))
        assertEquals("52m left", PlayerText.left(startedOver, now))
        val paused = BehindLive.pausedLive(ridgeLine, now)
        assertEquals("Paused · Under a minute behind live", PlayerText.behindEyebrow(paused, now))
        assertEquals("Paused · 12 min behind live", PlayerText.behindEyebrow(paused, now + 12 * minute + 30_000))
        // Carrying on after a pause, it only says how far.
        assertEquals("12 min behind live", PlayerText.behindEyebrow(paused.resumedAt(now + 12 * minute), now + 20 * minute))
        assertEquals("1 hr 5 min behind live", PlayerText.behindLive(65 * minute))
    }

    @Test
    fun `the countdown runs by the second and reaches 0 00 as the program starts`() {
        assertEquals("5:00", PlayerText.countdown(5 * minute))
        assertEquals("5:00", PlayerText.countdown(5 * minute - 1))
        assertEquals("0:01", PlayerText.countdown(1))
        assertEquals("0:00", PlayerText.countdown(0))
        assertEquals("0:00", PlayerText.countdown(-3_000))
        assertEquals("1:49:00", PlayerText.countdown(109 * minute))
    }

    @Test
    fun `meta joins the episode and rating`() {
        assertEquals("S3 E1 · Thaw · TV-MA", PlayerText.meta(slot(now, 52, "S3 E1 · Thaw", "TV-MA")))
        assertEquals("PG-13", PlayerText.meta(slot(now, 52, rating = "PG-13")))
        assertNull(PlayerText.meta(slot(now, 52)))
    }

    @Test
    fun `each off-air reason has its headline, and a slot's own reason wins`() {
        assertEquals("No channels yet", PlayerText.headline(OffAirReason.NO_CHANNELS, null))
        assertEquals("This title is no longer in your library", PlayerText.headline(OffAirReason.OFF_AIR, "This title is no longer in your library"))
        assertEquals("Off air", PlayerText.headline(OffAirReason.OFF_AIR, null))
        assertEquals("Plex isn't linked", PlayerText.headline(OffAirReason.NOT_LINKED, null))
    }

    private val film = slot(halfPastEight, 131)
    private val episode = slot(halfPastEight, 52, "S3 E1 · Thaw")

    @Test
    fun `the player's errors read as what happened, in plain words`() {
        // Plex answered, but the file's drive is off or restarting: a movie or an episode.
        assertEquals("This movie isn't available right now", PlayerText.headline(OffAirReason.UNAVAILABLE, null, film))
        assertEquals("This episode isn't available right now", PlayerText.headline(OffAirReason.UNAVAILABLE, null, episode))
        assertEquals("It may be on a drive that's off or restarting. Trying again…", PlayerText.explanation(OffAirReason.UNAVAILABLE))
        // Plex can't be reached.
        assertEquals("Can't reach Plex", PlayerText.headline(OffAirReason.OFFLINE, null, film))
        assertEquals("Check that your Plex server is on. Trying again…", PlayerText.explanation(OffAirReason.OFFLINE))
        // A format the Shield can't decode: nothing to try again.
        assertEquals("This one won't play on the Shield", PlayerText.headline(OffAirReason.CANT_PLAY, null, episode))
        assertEquals("The channel carries on with the next program.", PlayerText.explanation(OffAirReason.CANT_PLAY))
    }

    @Test
    fun `no error card says file, HTTP, error or a code`() {
        val jargon = listOf("file", "http", "error", "code", "io_", "media3")
        listOf(OffAirReason.UNAVAILABLE, OffAirReason.OFFLINE, OffAirReason.CANT_PLAY).forEach { reason ->
            listOf(film, episode).forEach { slot ->
                val card = PlayerText.headline(reason, null, slot) + " " + PlayerText.explanation(reason)
                jargon.forEach { word -> assertFalse("$reason says \"$word\": $card", card.contains(word, ignoreCase = true)) }
            }
        }
    }

    @Test
    fun `no channels says what the lineup waits on, in the full guide's words`() {
        assertEquals(
            "They're being made from your library now. Press Back to return.",
            PlayerText.explanation(OffAirReason.NO_CHANNELS, NoChannelsYet.BUILDING),
        )
        assertEquals(
            "They appear after your library syncs. Press Back to return.",
            PlayerText.explanation(OffAirReason.NO_CHANNELS),
        )
        NoChannelsYet.entries.forEach { why -> assertEquals(why.guide, "No channels yet: " + why.sentence.replaceFirstChar(Char::lowercaseChar)) }
    }

    // Phase 7.

    @Test
    fun `a place in a program reads by the second, with hours once there are any`() {
        assertEquals("0:00", PlayerText.position(0))
        assertEquals("0:09", PlayerText.position(9_999))
        assertEquals("12:30", PlayerText.position(12 * minute + 30_000))
        assertEquals("1:12:30", PlayerText.position(72 * minute + 30_000))
        assertEquals("0:00", PlayerText.position(-5_000))
    }

    @Test
    fun `while a key is held the eyebrow gives its speed and where letting go lands`() {
        val ridgeLine = slot(halfPastEight, 52)
        assertEquals("Rewind ×2 · Under a minute behind live", PlayerText.scrubEyebrow(Scrub(false, 2, 11 * minute, 11 * minute - 15_000), ridgeLine, now))
        assertEquals("Rewind ×8 · 4 min behind live", PlayerText.scrubEyebrow(Scrub(false, 8, 11 * minute, 7 * minute), ridgeLine, now))
        assertEquals("Fast forward ×4 · Live", PlayerText.scrubEyebrow(Scrub(true, 4, 9 * minute, 11 * minute), ridgeLine, now))
        // Behind live past the slot's end, the schedule has moved on further still.
        assertEquals("Rewind ×2 · 1 hr 8 min behind live", PlayerText.scrubEyebrow(Scrub(false, 2, 50 * minute, 30 * minute), ridgeLine, halfPastEight + 98 * minute))
    }
}
