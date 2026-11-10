package com.phonoodlesramen.actioncenter.lastapp

import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.nfl.WatchApp
import com.phonoodlesramen.actioncenter.player.SpotRules
import com.phonoodlesramen.actioncenter.tvprovider.normalisedName
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** A YouTube TV channel opened from Action Center: [name] as the Live row shows it, [link] as it opens, at [atMillis]. */
@Immutable
data class TunedChannel(val name: String, val link: String, val atMillis: Long)

/**
 * What the home knows of YouTube TV: the last [visit] to an app you watch (any app: the card
 * only shows for YouTube TV's), the [channels] opened here, newest first, and the visit
 * [forgotten] (its [AppVisit.frontAt]).
 */
@Immutable
data class TvState(val visit: AppVisit? = null, val channels: List<TunedChannel> = emptyList(), val forgotten: Long? = null)

/**
 * The Back to YouTube TV card: [visit] to [app] (YouTube TV, for its banner), the [channel] it
 * started on when that was opened here, and the [recent] channels its sheet offers, that
 * one first.
 */
@Immutable
data class BackToTv(val visit: AppVisit, val app: LaunchableApp, val channel: TunedChannel?, val recent: List<TunedChannel>)

/** Back to YouTube TV's rules. Pure. */
object YouTubeTv {
    val PACKAGE = WatchApp.YOUTUBE_TV.packageName

    /** The card's for 4 hours after you leave YouTube TV, as the Plex Back-to card is after you leave its player. */
    const val KEEP_MILLIS = SpotRules.KEEP_MILLIS

    /** Channels in the Live row after the favorites, and in the card's sheet. */
    const val RECENT = 3

    /** Channels remembered. */
    const val KEPT = 5

    /**
     * A channel opened here started the visit when YouTube TV came to the front within this
     * long after (a slow TV takes a while to open it), or just before (two clocks' worth of slack).
     */
    const val STARTED_WITHIN_MILLIS = 2 * 60_000L
    private const val SLACK_MILLIS = 10_000L

    /** A link that opens in YouTube TV: an `intent:` URI naming its package, or a `tv.youtube.com` link. */
    fun isChannelLink(link: String): Boolean = PACKAGE in link || "tv.youtube.com" in link.lowercase()

    /** A Live row network that's a YouTube TV channel: from YouTube TV's own row, or a `channels.json` button for it. */
    fun isChannel(button: ChannelButton): Boolean = button.source == ChannelButton.Source.YOUTUBE_TV || isChannelLink(button.intentUri)

    /** [channels] with [channel] first: one per name, the newest [KEPT]. */
    fun remembered(channels: List<TunedChannel>, channel: TunedChannel): List<TunedChannel> {
        val name = normalisedName(channel.name)
        return (listOf(channel) + channels.filter { normalisedName(it.name) != name }).take(KEPT)
    }

    /**
     * The card at [nowMillis], or null: the last visit isn't YouTube TV's, it was forgotten, it
     * was left [KEEP_MILLIS] ago or more (a clock that went back keeps it), or YouTube TV isn't
     * among [apps] any more.
     */
    fun backTo(tv: TvState, apps: List<LaunchableApp>, nowMillis: Long): BackToTv? {
        val visit = tv.visit?.takeIf { it.packageName == PACKAGE && it.frontAt != tv.forgotten } ?: return null
        if (nowMillis - visit.leftAt >= KEEP_MILLIS) return null
        val app = apps.firstOrNull { it.packageName == PACKAGE } ?: return null
        val started = visit.frontAt - STARTED_WITHIN_MILLIS..visit.frontAt + SLACK_MILLIS
        val channel = tv.channels.firstOrNull { it.atMillis in started }
        val recent = (listOfNotNull(channel) + tv.channels).distinct().take(RECENT)
        return BackToTv(visit, app, channel, recent)
    }

    /**
     * The Live row's networks ([buttons], in their order) split in two: the YouTube TV
     * channels among the [RECENT] newest of [channels], newest first, then the rest as they
     * were. A channel no button shows is skipped.
     */
    fun recentFirst(buttons: List<ChannelButton>, channels: List<TunedChannel>): Pair<List<ChannelButton>, List<ChannelButton>> {
        if (channels.isEmpty()) return emptyList<ChannelButton>() to buttons
        val tv = buttons.filter(::isChannel)
        val recent = channels.take(RECENT).mapNotNull { channel ->
            val name = normalisedName(channel.name)
            tv.firstOrNull { normalisedName(it.name) == name }
        }.distinct()
        return recent to buttons.filter { it !in recent }
    }

    /** The banner the Live row shows for [channel]: its button's art, if a button shows it. */
    fun buttonFor(channel: TunedChannel, buttons: List<ChannelButton>): ChannelButton? {
        val name = normalisedName(channel.name)
        return buttons.filter(::isChannel).firstOrNull { normalisedName(it.name) == name }
    }
}

/** The channels opened here and the visit forgotten, kept where they survive the process being killed. */
interface TvChannelStore {
    /** Newest first, at most [YouTubeTv.KEPT]. */
    val channels: Flow<List<TunedChannel>>

    /** The forgotten visit's [AppVisit.frontAt]. */
    val forgotten: Flow<Long?>

    /** [channel] was opened: it leads the list. */
    suspend fun tuned(channel: TunedChannel)

    /** Forget this: [visit]'s card goes. The next visit brings it back. */
    suspend fun forget(visit: AppVisit)

    /** Nothing's kept (previews, screenshots). */
    object None : TvChannelStore {
        override val channels: Flow<List<TunedChannel>> = flowOf(emptyList())
        override val forgotten: Flow<Long?> = flowOf(null)
        override suspend fun tuned(channel: TunedChannel) = Unit
        override suspend fun forget(visit: AppVisit) = Unit
    }
}

/** A [TvChannelStore] in its own Preferences DataStore file. A file that can't be read reads as nothing kept. */
class DataStoreTvChannels(private val store: DataStore<Preferences>) : TvChannelStore {
    private val prefs = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    override val channels: Flow<List<TunedChannel>> = prefs.map { decode(it[CHANNELS]) }.distinctUntilChanged()
    override val forgotten: Flow<Long?> = prefs.map { it[FORGOTTEN] }.distinctUntilChanged()

    override suspend fun tuned(channel: TunedChannel) {
        store.edit { it[CHANNELS] = encode(YouTubeTv.remembered(decode(it[CHANNELS]), channel)) }
    }

    override suspend fun forget(visit: AppVisit) {
        store.edit { it[FORGOTTEN] = visit.frontAt }
    }

    internal companion object {
        val CHANNELS = stringPreferencesKey("channels")
        val FORGOTTEN = longPreferencesKey("forgotten")

        /** One channel a line: its time, name and link, between tabs. Neither a name nor a link has a tab or a line break. */
        fun encode(channels: List<TunedChannel>): String = channels.joinToString("\n") { channel ->
            listOf(channel.atMillis.toString(), clean(channel.name), clean(channel.link)).joinToString("\t")
        }

        /** A line that doesn't read is skipped. */
        fun decode(text: String?): List<TunedChannel> = text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TunedChannel(name, link, at)
        }.toList()

        private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    }
}

/** A [TvChannelStore] in memory: tests. */
class MemoryTvChannels(initial: List<TunedChannel> = emptyList()) : TvChannelStore {
    private val list = MutableStateFlow(initial)
    private val forgottenAt = MutableStateFlow<Long?>(null)
    override val channels: Flow<List<TunedChannel>> = list
    override val forgotten: Flow<Long?> = forgottenAt

    override suspend fun tuned(channel: TunedChannel) {
        list.value = YouTubeTv.remembered(list.value, channel)
    }

    override suspend fun forget(visit: AppVisit) {
        forgottenAt.value = visit.frontAt
    }
}
