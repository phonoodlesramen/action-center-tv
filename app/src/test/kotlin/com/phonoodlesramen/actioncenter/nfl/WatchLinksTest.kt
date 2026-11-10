package com.phonoodlesramen.actioncenter.nfl

import com.phonoodlesramen.actioncenter.handoff.IntentSpec
import com.phonoodlesramen.actioncenter.home.ChannelButton
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Watch goes (F6, F7, proposal E; ui-spec "Where Watch goes"): per carrier, per app on the TV, and Sunday Ticket on and off. */
class WatchLinksTest {
    private val zone: ZoneId = ZoneOffset.UTC

    /** A TV with YouTube TV, Prime Video, Netflix, Paramount+, Disney+ and YouTube. */
    private val shield = setOf(WatchApp.YOUTUBE_TV, WatchApp.PRIME_VIDEO, WatchApp.NETFLIX, WatchApp.PARAMOUNT_PLUS, WatchApp.DISNEY_PLUS, WatchApp.YOUTUBE)
        .mapTo(HashSet()) { it.packageName }

    /** A second TV, without Paramount+. */
    private val kitchen = shield - WatchApp.PARAMOUNT_PLUS.packageName

    /** YouTube TV's own row has NBC, CBS and Fox; channels.json adds an ESPN of its own. */
    private val channels = listOf(
        ChannelButton("yt-1", "NBC", "intent:#Intent;component=com.google.android.youtube.tvunplugged/.Main;S.channel=nbc;end", source = ChannelButton.Source.YOUTUBE_TV),
        ChannelButton("yt-2", "CBS", "https://tv.youtube.com/watch/cbs-from-the-row", source = ChannelButton.Source.YOUTUBE_TV),
        ChannelButton("yt-3", "Fox", "https://tv.youtube.com/watch/fox-from-the-row", source = ChannelButton.Source.YOUTUBE_TV),
        ChannelButton("list-0", "FOX Sports 1", "https://tv.youtube.com/watch/fs1"),
        ChannelButton("list-1", "CBS", "https://tv.youtube.com/watch/cbs-from-the-list"),
    )

    private fun millis(text: String) = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun game(tv: List<String> = emptyList(), streaming: List<String> = emptyList(), kickoff: String = "2026-11-02T20:15", away: String = "CHI", home: String = "SEA") =
        NflGame("1", 2, 8, millis(kickoff), away = away, home = home, state = GameState.PRE, tv = tv, streaming = streaming)

    private fun ways(game: NflGame, settings: NflSettings = NflSettings(), installed: Set<String> = shield) =
        WatchLinks.ways(game, settings, WatchOptions(channels, installed), zone)

    @Test
    fun `a network is YouTube TV on the Live row's channel, YouTube TV's own row before channels_json`() {
        val nbc = ways(game(tv = listOf("NBC"))).first()
        assertEquals(WatchApp.YOUTUBE_TV, nbc.app)
        assertEquals("NBC on YouTube TV", nbc.label)
        assertTrue(nbc.link!!.startsWith("intent:"))
        assertEquals("https://tv.youtube.com/watch/cbs-from-the-row", ways(game(tv = listOf("CBS"))).first().link)
        // ESPN's "Fox" is FOX, and the row's "Fox" matches it; "FOX Sports 1" doesn't.
        assertEquals("https://tv.youtube.com/watch/fox-from-the-row", ways(game(tv = listOf("Fox"))).first().link)
    }

    @Test
    fun `a YouTube TV way names its channel as the Live row does, for Back to YouTube TV`() {
        assertEquals("Fox", ways(game(tv = listOf("Fox"))).first().channel)
        assertEquals("NBC", ways(game(tv = listOf("NBC"))).first().channel)
        // No button for it: the network's own name.
        assertEquals("NFL Network", ways(game(tv = listOf("NFL Net"))).first().channel)
        assertNull(ways(game(streaming = listOf("Prime Video"))).first().channel)
    }

    @Test
    fun `without the row's channel, the national id, else YouTube TV's home`() {
        assertEquals("https://tv.youtube.com/watch/xDLamOKnZDc", ways(game(tv = listOf("ESPN"))).first().link)
        assertEquals("https://tv.youtube.com/watch/bANYnloGyMw", ways(game(tv = listOf("ESPN2"))).first().link)
        val nflNetwork = ways(game(tv = listOf("NFL Net"))).first()
        assertEquals("NFL Network on YouTube TV", nflNetwork.label)
        assertEquals("https://tv.youtube.com/watch/_pYg9qMKKIA", nflNetwork.link)
        val abc = ways(game(tv = listOf("ABC"))).first()
        assertEquals("ABC on YouTube TV", abc.label)
        assertNull(abc.link)
        assertEquals(IntentSpec.LaunchApp(WatchApp.YOUTUBE_TV.packageName), abc.spec())
    }

    @Test
    fun `a network's own app comes second, only when it's on this TV`() {
        assertEquals(listOf("CBS on YouTube TV", "Paramount+"), ways(game(tv = listOf("CBS"))).map { it.label })
        assertEquals(listOf("CBS on YouTube TV"), ways(game(tv = listOf("CBS")), installed = kitchen).map { it.label })
        assertEquals(listOf("ESPN on YouTube TV", "Disney+", "ABC on YouTube TV"), ways(game(tv = listOf("ESPN", "ABC"))).map { it.label })
        // Neither TV has Peacock or Fox One: they aren't offered for NBC or FOX.
        assertEquals(listOf("NBC on YouTube TV"), ways(game(tv = listOf("NBC"))).map { it.label })
        assertEquals(listOf("FOX on YouTube TV"), ways(game(tv = listOf("Fox"))).map { it.label })
    }

    @Test
    fun `a streaming game opens its app's home, and one that isn't here is dimmed, last`() {
        val prime = ways(game(streaming = listOf("Prime Video"))).single()
        assertEquals(WatchApp.PRIME_VIDEO, prime.app)
        assertNull(prime.link)
        assertEquals(IntentSpec.LaunchApp("com.amazon.amazonvideo.livingroom"), prime.spec())
        assertEquals(WatchApp.NETFLIX, ways(game(streaming = listOf("Netflix"))).single().app)

        val peacock = ways(game(tv = listOf("NBC"), streaming = listOf("Peacock")))
        assertEquals(listOf("NBC on YouTube TV", "Peacock"), peacock.map { it.label })
        assertFalse(peacock.last().installed)
        assertEquals("Peacock isn't on this TV", peacock.last().missing)
    }

    @Test
    fun `YouTube TV missing is dimmed, after a way that works`() {
        val ways = ways(game(tv = listOf("CBS")), installed = shield - WatchApp.YOUTUBE_TV.packageName)
        assertEquals(listOf("Paramount+", "CBS on YouTube TV"), ways.map { it.label })
        assertEquals(listOf(true, false), ways.map { it.installed })
    }

    @Test
    fun `every link names its app's package`() {
        val ways = ways(game(tv = listOf("CBS", "ESPN"), streaming = listOf("Netflix")))
        ways.forEach { way ->
            when (val spec = way.spec()) {
                is IntentSpec.ViewUri -> assertEquals(way.app.packageName, spec.packageName)
                is IntentSpec.LaunchApp -> assertEquals(way.app.packageName, spec.packageName)
                null -> assertTrue(way.link!!.startsWith("intent:"))
            }
        }
        // An intent: URI is parsed by the activity, which names the package when the URI doesn't.
        assertNull(ways(game(tv = listOf("NBC"))).first().spec())
    }

    private val sundayTicket = NflSettings(areaTeams = listOf("PHI"), sundayTicket = true)

    /** Sunday 1 November, 1 PM, Ravens at Bills on CBS: no Eagles. */
    private val sunday = game(tv = listOf("CBS"), kickoff = "2026-11-01T13:00", away = "BAL", home = "BUF")

    @Test
    fun `with Sunday Ticket on, another area's Sunday afternoon game opens Sunday Ticket first, the network second`() {
        val ways = ways(sunday, sundayTicket)
        assertEquals(listOf("Sunday Ticket on YouTube TV", "CBS on YouTube TV", "Paramount+"), ways.map { it.label })
        assertNull(ways.first().link)
    }

    @Test
    fun `Sunday Ticket off, your area's game, an unset area or a night game opens the network first`() {
        assertEquals("CBS on YouTube TV", ways(sunday, sundayTicket.copy(sundayTicket = false)).first().label)
        assertEquals("CBS on YouTube TV", ways(sunday.copy(home = "PHI"), sundayTicket).first().label)
        assertEquals("CBS on YouTube TV", ways(sunday, sundayTicket.copy(areaTeams = emptyList())).first().label)
        assertEquals("NBC on YouTube TV", ways(game(tv = listOf("NBC"), kickoff = "2026-11-01T20:20", away = "BAL", home = "BUF"), sundayTicket).first().label)
    }
}
