package com.phonoodlesramen.actioncenter.lastapp

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.home.ChannelButton
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back to YouTube TV's rules: when the card shows, the channel it names, the Live row's recent channels, and the store. */
class YouTubeTvTest {
    private val minute = 60_000L
    private val now = 1_000 * minute
    private val youTubeTv = LaunchableApp(YouTubeTv.PACKAGE, "com.google.android.apps.youtube.tvunplugged.activity.MainActivity", "YouTube TV")
    private val netflix = LaunchableApp("com.netflix.ninja", "com.netflix.ninja.MainActivity", "Netflix")
    private val apps = listOf(netflix, youTubeTv)

    /** YouTube TV came up 40 minutes ago, opened on NBC from the Live row, and was left 9 minutes ago. */
    private val visit = AppVisit(YouTubeTv.PACKAGE, frontAt = now - 40 * minute, leftAt = now - 9 * minute)
    private val nbc = TunedChannel("NBC", "https://tv.youtube.com/watch/nbc", now - 40 * minute - 4_000)
    private val espn = TunedChannel("ESPN", "https://tv.youtube.com/watch/espn", now - 3 * 60 * minute)
    private val pbs = TunedChannel("PBS", "https://tv.youtube.com/watch/pbs", now - 26 * 60 * minute)
    private val cnn = TunedChannel("CNN", "https://tv.youtube.com/watch/cnn", now - 50 * 60 * minute)

    private fun backTo(tv: TvState, at: Long = now, installed: List<LaunchableApp> = apps) = YouTubeTv.backTo(tv, installed, at)

    @Test
    fun `YouTube TV left a few minutes ago is the card, on the channel opened here, recent channels first`() {
        val card = backTo(TvState(visit, listOf(nbc, espn, pbs, cnn)))!!
        assertEquals(visit, card.visit)
        assertEquals(youTubeTv, card.app)
        assertEquals(nbc, card.channel)
        assertEquals(listOf(nbc, espn, pbs), card.recent)
    }

    @Test
    fun `a channel opened long before the visit didn't start it`() {
        val card = backTo(TvState(visit, listOf(espn, pbs)))!!
        assertNull(card.channel)
        assertEquals(listOf(espn, pbs), card.recent)
        // A slow TV takes a while to open it: up to 2 minutes.
        val slow = espn.copy(atMillis = visit.frontAt - 2 * minute)
        assertEquals(slow, backTo(TvState(visit, listOf(slow)))!!.channel)
        assertNull(backTo(TvState(visit, listOf(espn.copy(atMillis = visit.frontAt - 3 * minute))))!!.channel)
    }

    @Test
    fun `it holds for 4 hours after you leave, and a clock that went back keeps it`() {
        val tv = TvState(visit)
        assertTrue(backTo(tv, at = visit.leftAt + 4 * 60 * minute - 1) != null)
        assertNull(backTo(tv, at = visit.leftAt + 4 * 60 * minute))
        assertTrue(backTo(tv, at = visit.leftAt - 10 * minute) != null)
    }

    @Test
    fun `another app last, a forgotten visit, or YouTube TV gone, is no card`() {
        assertNull(backTo(TvState(visit.copy(packageName = netflix.packageName))))
        assertNull(backTo(TvState(visit, forgotten = visit.frontAt)))
        assertTrue(backTo(TvState(visit, forgotten = visit.frontAt - 60 * minute)) != null)
        assertNull(backTo(TvState(visit), installed = listOf(netflix)))
        assertNull(backTo(TvState()))
    }

    @Test
    fun `a channel is remembered once by its name, newest first, and only the last 5`() {
        var channels = emptyList<TunedChannel>()
        listOf(cnn, pbs, espn, nbc).forEach { channels = YouTubeTv.remembered(channels, it) }
        assertEquals(listOf("NBC", "ESPN", "PBS", "CNN"), channels.map { it.name })
        channels = YouTubeTv.remembered(channels, TunedChannel("espn", "intent:#Intent;package=${YouTubeTv.PACKAGE};end", now))
        assertEquals(listOf("espn", "NBC", "PBS", "CNN"), channels.map { it.name })
        listOf("FX", "CBS", "ABC").forEach { channels = YouTubeTv.remembered(channels, TunedChannel(it, "https://tv.youtube.com/watch/$it", now)) }
        assertEquals(listOf("ABC", "CBS", "FX", "espn", "NBC"), channels.map { it.name })
    }

    // The Live row.

    private fun button(name: String, uri: String = "https://tv.youtube.com/watch/${name.lowercase()}", source: ChannelButton.Source = ChannelButton.Source.CHANNEL_LIST) =
        ChannelButton(id = name.lowercase(), name = name, intentUri = uri, source = source)

    private val row = listOf(
        button("ESPN"),
        button("FOX Sports 1"),
        button("PBS"),
        button("NBC", "intent:#Intent;component=${YouTubeTv.PACKAGE}/.Main;S.channel=nbc;end", ChannelButton.Source.YOUTUBE_TV),
        button("CNN"),
        button("Twitch", "https://www.twitch.tv/somebody"),
    )

    @Test
    fun `the 3 newest YouTube TV channels lead the networks, newest first, the rest as they were`() {
        val (recent, rest) = YouTubeTv.recentFirst(row, listOf(nbc, espn, pbs, cnn))
        assertEquals(listOf("NBC", "ESPN", "PBS"), recent.map { it.name })
        assertEquals(listOf("FOX Sports 1", "CNN", "Twitch"), rest.map { it.name })
    }

    @Test
    fun `a channel the row doesn't show is skipped, and none opened leaves the row as it was`() {
        val (recent, rest) = YouTubeTv.recentFirst(row, listOf(TunedChannel("ABC", "https://tv.youtube.com/watch/abc", now), cnn))
        assertEquals(listOf("CNN"), recent.map { it.name })
        assertEquals(row - row[4], rest)
        assertEquals(emptyList<ChannelButton>() to row, YouTubeTv.recentFirst(row, emptyList()))
        // Not a YouTube TV link: never a recent channel.
        assertEquals(emptyList<ChannelButton>(), YouTubeTv.recentFirst(row, listOf(TunedChannel("Twitch", "x", now))).first)
    }

    @Test
    fun `YouTube TV's channels are its row's buttons and links to it`() {
        assertTrue(YouTubeTv.isChannel(row[0]))
        assertTrue(YouTubeTv.isChannel(row[3]))
        assertTrue(YouTubeTv.isChannel(button("ABC", "https://TV.YouTube.com/watch/abc")))
        assertTrue(YouTubeTv.isChannel(button("Mystery", "intent:#Intent;package=${YouTubeTv.PACKAGE};end")))
        assertFalse(YouTubeTv.isChannel(row[5]))
        assertEquals(row[3], YouTubeTv.buttonFor(nbc.copy(name = "nbc"), row))
        assertNull(YouTubeTv.buttonFor(TunedChannel("Twitch", "x", now), row))
    }

    // The store.

    private fun TestScope.store(file: File, scope: CoroutineScope = backgroundScope) =
        DataStoreTvChannels(PreferenceDataStoreFactory.create(scope = scope) { file })

    private fun file() = File(Files.createTempDirectory("tv").toFile(), "youtube_tv.preferences_pb")

    @Test
    fun `channels opened are kept newest first, and a forgotten visit is kept`() = runTest {
        val tv = store(file())
        assertEquals(emptyList<TunedChannel>(), tv.channels.first())
        assertNull(tv.forgotten.first())
        tv.tuned(espn)
        tv.tuned(nbc)
        assertEquals(listOf(nbc, espn), tv.channels.first())
        tv.forget(visit)
        assertEquals(visit.frontAt, tv.forgotten.first())
    }

    @Test
    fun `they survive the process, as a new store on the same file reads them`() = runTest {
        val file = file()
        val job = Job()
        val first = store(file, CoroutineScope(backgroundScope.coroutineContext + job))
        first.tuned(nbc)
        first.forget(visit)
        // The first process's store is gone.
        job.cancelAndJoin()
        val second = store(file)
        assertEquals(listOf(nbc), second.channels.first())
        assertEquals(visit.frontAt, second.forgotten.first())
    }

    @Test
    fun `a line that doesn't read is skipped, and tabs or line breaks in a name don't break the file`() {
        val text = DataStoreTvChannels.encode(listOf(TunedChannel("Food\tNetwork\n", "https://tv.youtube.com/watch/food", 5), nbc))
        assertEquals(listOf(TunedChannel("Food Network ", "https://tv.youtube.com/watch/food", 5), nbc), DataStoreTvChannels.decode(text))
        assertEquals(listOf(nbc), DataStoreTvChannels.decode("garbage\n12\tno link\n\n" + DataStoreTvChannels.encode(listOf(nbc))))
        assertEquals(emptyList<TunedChannel>(), DataStoreTvChannels.decode(null))
    }
}
