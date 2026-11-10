package com.phonoodlesramen.actioncenter.handoff

import com.phonoodlesramen.actioncenter.handoff.IntentSpec.LaunchApp
import com.phonoodlesramen.actioncenter.handoff.IntentSpec.ViewUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IntentProbeTest {
    private fun probe(vararg resolvable: IntentSpec) = IntentProbe { it in resolvable }

    @Test
    fun `a trailer opens in YouTube for Android TV`() {
        val youtubeTv = ViewUri("https://www.youtube.com/watch?v=$VIDEO_ID", Handoffs.YOUTUBE_TV)

        assertEquals(youtubeTv, IntentProbe { true }.firstResolvable(Handoffs.trailer(VIDEO_ID)))
    }

    @Test
    fun `a trailer falls back to the vnd youtube scheme`() {
        val fallback = ViewUri("vnd.youtube:$VIDEO_ID")

        assertEquals(fallback, probe(fallback).firstResolvable(Handoffs.trailer(VIDEO_ID)))
    }

    @Test
    fun `play hands off to Plezy with its server and id link`() {
        // Plezy's own link (source at 7b999fb, deep_link.dart): server and id together.
        val expected = ViewUri("plezy://play?server=$SERVER_ID&id=$RATING_KEY", Handoffs.PLEZY)

        assertEquals(expected, IntentProbe { true }.firstResolvable(Handoffs.play(PlayIn.PLEZY, SERVER_ID, RATING_KEY)))
    }

    @Test
    fun `Plezy's link names the profile to switch to, encoded`() {
        val uuid = Handoffs.play(PlayIn.PLEZY, SERVER_ID, RATING_KEY, profile = "a1b2c3d4e5f60718").first() as ViewUri
        val name = Handoffs.play(PlayIn.PLEZY, SERVER_ID, RATING_KEY, profile = " Juniper & Co ").first() as ViewUri
        val blank = Handoffs.play(PlayIn.PLEZY, SERVER_ID, RATING_KEY, profile = " ").first() as ViewUri

        assertEquals("plezy://play?server=$SERVER_ID&id=$RATING_KEY&profile=a1b2c3d4e5f60718", uuid.uri)
        assertEquals("a space is %20, an ampersand can't end the link", "plezy://play?server=$SERVER_ID&id=$RATING_KEY&profile=Juniper%20%26%20Co", name.uri)
        assertEquals("plezy://play?server=$SERVER_ID&id=$RATING_KEY", blank.uri)
    }

    @Test
    fun `Plezy's chain is its link, its home, then the Plex app's home`() {
        assertEquals(
            listOf(
                ViewUri("plezy://play?server=$SERVER_ID&id=$RATING_KEY", Handoffs.PLEZY),
                LaunchApp(Handoffs.PLEZY),
                LaunchApp(Handoffs.PLEX),
            ),
            Handoffs.play(PlayIn.PLEZY, SERVER_ID, RATING_KEY),
        )
    }

    @Test
    fun `play opens Plezy's home when its deep link does not resolve`() {
        val plezyHome = LaunchApp(Handoffs.PLEZY)

        assertEquals(plezyHome, probe(plezyHome, LaunchApp(Handoffs.PLEX)).firstResolvable(Handoffs.play(PlayIn.PLEZY, SERVER_ID, RATING_KEY)))
    }

    @Test
    fun `play falls back to the Plex app when Plezy is not installed`() {
        val plexHome = LaunchApp(Handoffs.PLEX)

        assertEquals(plexHome, probe(plexHome).firstResolvable(Handoffs.play(PlayIn.PLEZY, SERVER_ID, RATING_KEY)))
    }

    @Test
    fun `Plex's link names the title by its percent-encoded key, with no tv segment`() {
        assertEquals(
            "plex://libraries/$SERVER_ID/unmatched/%2Flibrary%2Fmetadata%2F$RATING_KEY",
            Handoffs.plexLink(SERVER_ID, RATING_KEY),
        )
    }

    @Test
    fun `Plex's chain is its link, its home, then Plezy's link and home, with the profile`() {
        assertEquals(
            listOf(
                ViewUri("plex://libraries/$SERVER_ID/unmatched/%2Flibrary%2Fmetadata%2F$RATING_KEY", Handoffs.PLEX),
                LaunchApp(Handoffs.PLEX),
                ViewUri("plezy://play?server=$SERVER_ID&id=$RATING_KEY&profile=u-1", Handoffs.PLEZY),
                LaunchApp(Handoffs.PLEZY),
            ),
            Handoffs.play(PlayIn.PLEX, SERVER_ID, RATING_KEY, profile = "u-1"),
        )
    }

    @Test
    fun `without the Plex app, Play in Plex goes to Plezy`() {
        val plezy = ViewUri("plezy://play?server=$SERVER_ID&id=$RATING_KEY", Handoffs.PLEZY)

        assertEquals(plezy, probe(plezy, LaunchApp(Handoffs.PLEZY)).firstResolvable(Handoffs.play(PlayIn.PLEX, SERVER_ID, RATING_KEY)))
    }

    @Test
    fun `Trailer plays the title's Plex trailer extra through Plezy, like Play`() {
        val extra = ViewUri("plezy://play?server=$SERVER_ID&id=$EXTRA_KEY", Handoffs.PLEZY)

        assertEquals(extra, IntentProbe { true }.firstResolvable(Handoffs.trailerFor("Arrival", 2016, SERVER_ID, EXTRA_KEY)))
    }

    @Test
    fun `with no trailer extra, Trailer searches YouTube for the title, year and trailer`() {
        val search = ViewUri("https://www.youtube.com/results?search_query=Arrival+2016+trailer", Handoffs.YOUTUBE_TV)

        assertEquals(search, IntentProbe { true }.firstResolvable(Handoffs.trailerFor("Arrival", 2016, SERVER_ID, extraKey = null)))
    }

    @Test
    fun `when Plezy can't open the extra, Trailer searches YouTube instead of opening an app's home`() {
        val search = ViewUri("https://www.youtube.com/results?search_query=Arrival+2016+trailer", Handoffs.YOUTUBE_TV)
        val candidates = Handoffs.trailerFor("Arrival", 2016, SERVER_ID, EXTRA_KEY)

        assertEquals(search, probe(search, LaunchApp(Handoffs.PLEZY)).firstResolvable(candidates))
        assertEquals(emptyList<IntentSpec>(), candidates.filterIsInstance<LaunchApp>())
    }

    @Test
    fun `without YouTube for Android TV, any app that opens YouTube links gets the search`() {
        val anyApp = ViewUri("https://www.youtube.com/results?search_query=Arrival+trailer")

        assertEquals(anyApp, probe(anyApp).firstResolvable(Handoffs.trailerFor("Arrival", year = null, serverId = null, extraKey = EXTRA_KEY)))
    }

    @Test
    fun `a title can't break the search link`() {
        val search = Handoffs.trailerFor("Fast & Furious: Tokyo Drift?#", 2006, serverId = null, extraKey = null).first() as ViewUri

        assertEquals("https://www.youtube.com/results?search_query=Fast+%26+Furious%3A+Tokyo+Drift%3F%23+2006+trailer", search.uri)
    }

    @Test
    fun `nothing resolvable gives null`() {
        assertNull(probe().firstResolvable(Handoffs.play(PlayIn.PLEZY, SERVER_ID, RATING_KEY)))
    }

    @Test
    fun `probe reports every candidate in order`() {
        val candidates = Handoffs.play(PlayIn.PLEZY, SERVER_ID, RATING_KEY)

        val results = probe(LaunchApp(Handoffs.PLEX)).probe(candidates)

        assertEquals(candidates, results.map { it.spec })
        assertEquals(listOf(false, false, true), results.map { it.resolves })
    }

    @Test
    fun `ids that would break a deep link are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Handoffs.play(PlayIn.PLEZY, "abc 123", RATING_KEY) }
        assertThrows(IllegalArgumentException::class.java) { Handoffs.play(PlayIn.PLEZY, SERVER_ID, "4567&x=1") }
        assertThrows(IllegalArgumentException::class.java) { Handoffs.plexLink(SERVER_ID, "4567/x") }
        assertThrows(IllegalArgumentException::class.java) { Handoffs.trailer("dQw4w9W?XcQ") }
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val SERVER_ID = "0a1b2c3d4e5f60718293a4b5c6d7e8f901234567"
        const val RATING_KEY = "4567"
        const val EXTRA_KEY = "4570"
    }
}
