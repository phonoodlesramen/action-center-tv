package com.phonoodlesramen.actioncenter.tvprovider

import android.database.MatrixCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Made-up rows in the provider's column names; real ones are read on the Shield (Diagnostics dump). */
@RunWith(RobolectricTestRunner::class)
class TvProviderTest {
    @Test
    fun `a Watch Next row maps by column name, and a column the provider left out is just null`() {
        val cursor = MatrixCursor(
            arrayOf("_id", "package_name", "title", "type", "watch_next_type", "episode_title", "season_display_number",
                "episode_display_number", "poster_art_uri", "intent_uri", "duration_millis", "last_playback_position_millis",
                "last_engagement_time_utc_millis", "live"),
        ).apply {
            addRow(arrayOf<Any?>(7L, "com.disney.disneyplus", "Bluey", 3L, 0L, "Sleepytime", "2", "26",
                "https://art/bluey.jpg", "https://www.disneyplus.com/play/abc", 420_000L, 120_000L, 1_793_000_000_000L, 0L))
        }
        cursor.moveToFirst()

        val program = TvRows.program(cursor, TvProgram.Kind.WATCH_NEXT)!!

        assertEquals("com.disney.disneyplus", program.packageName)
        assertEquals("Sleepytime", program.episodeTitle)
        assertEquals("2" to "26", program.season to program.episode)
        assertEquals(0, program.watchNextType)
        assertEquals(120_000L, program.positionMillis)
        assertNull("thumbnail_uri isn't in this cursor", program.thumbnailUri)
        assertNull("only preview rows have a channel", program.channelId)
        assertFalse(program.live)
    }

    @Test
    fun `a preview row keeps its channel, and a row without a title is skipped`() {
        val cursor = MatrixCursor(arrayOf("_id", "package_name", "title", "channel_id", "live", "intent_uri")).apply {
            addRow(arrayOf<Any?>(1L, "com.google.android.youtube.tvunplugged", "NBC", 42L, 1L, "intent:#Intent;end"))
            addRow(arrayOf<Any?>(2L, "com.google.android.youtube.tvunplugged", null, 42L, 0L, null))
        }

        val programs = buildList { while (cursor.moveToNext()) TvRows.program(cursor, TvProgram.Kind.PREVIEW)?.let(::add) }

        assertEquals(1, programs.size)
        assertEquals(42L, programs.single().channelId)
        assertTrue(programs.single().live)
    }

    @Test
    fun `secrets in links are blanked in the dump`() {
        assertEquals(
            "http://server:32400/library/parts/1/file.mkv?X-Plex-Token=…&download=0",
            redact("http://server:32400/library/parts/1/file.mkv?X-Plex-Token=abc123&download=0"),
        )
        assertEquals("plex://play?ratingKey=12&authKey=…", redact("plex://play?ratingKey=12&authKey=xyz"))
        assertEquals("intent:#Intent;S.token=…;S.id=7;end", redact("intent:#Intent;S.token=abc;S.id=7;end"))
        assertEquals("https://www.disneyplus.com/play/abc", redact("https://www.disneyplus.com/play/abc"))
        assertEquals("none", redact(null))
    }

    @Test
    fun `the dump lists channels, then programs by app, with each preview's channel name and a cap per app`() {
        val snapshot = TvProviderSnapshot(
            channels = listOf(TvChannel(42, "com.google.android.youtube.tvunplugged", "Live", "https://tv.youtube.com/")),
            programs = listOf(
                TvProgram(1, TvProgram.Kind.PREVIEW, "com.google.android.youtube.tvunplugged", "NBC", type = 6, channelId = 42, live = true),
                TvProgram(2, TvProgram.Kind.WATCH_NEXT, "com.disney.disneyplus", "Bluey", type = 3, season = "2", episode = "26"),
                TvProgram(3, TvProgram.Kind.WATCH_NEXT, "com.disney.disneyplus", "Moana", type = 0),
            ),
        )

        val lines = snapshot.dumpLines(perApp = 1)

        assertEquals("tv channel pkg=com.google.android.youtube.tvunplugged id=42 name=\"Live\" link=https://tv.youtube.com/", lines[0])
        assertEquals("tv program WATCH_NEXT pkg=com.disney.disneyplus type=episode live=false title=\"Bluey\" S2E26 intent=none", lines[1])
        assertEquals("tv program WATCH_NEXT pkg=com.disney.disneyplus: 1 more", lines[2])
        assertEquals(
            "tv program PREVIEW pkg=com.google.android.youtube.tvunplugged channel=\"Live\" type=channel live=true title=\"NBC\" intent=none",
            lines[3],
        )
    }
}
