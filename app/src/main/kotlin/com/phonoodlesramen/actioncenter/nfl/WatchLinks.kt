package com.phonoodlesramen.actioncenter.nfl

import androidx.compose.runtime.Immutable
import com.phonoodlesramen.actioncenter.handoff.IntentSpec
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.tvprovider.normalisedName
import java.time.ZoneId

/**
 * The apps a game can be watched in (F6, F7), each by its TV package. Neither TV had Peacock, Fox
 * One or the NFL's app when the plan was made (11/01/2026), so theirs are read from their store
 * listings, not from a TV.
 */
enum class WatchApp(val label: String, val packageName: String) {
    YOUTUBE_TV("YouTube TV", "com.google.android.youtube.tvunplugged"),
    PRIME_VIDEO("Prime Video", "com.amazon.amazonvideo.livingroom"),
    NETFLIX("Netflix", "com.netflix.ninja"),
    PARAMOUNT_PLUS("Paramount+", "com.cbs.ott"),
    DISNEY_PLUS("Disney+", "com.disney.disneyplus"),
    PEACOCK("Peacock", "com.peacocktv.peacockandroid"),
    FOX_ONE("Fox One", "com.fox.foxone"),
    YOUTUBE("YouTube", "com.google.android.youtube.tv"),
    NFL_PLUS("NFL+", "com.gotv.nflgamecenter.us.lite"),
}

/**
 * One way to watch a game: [app], and [link], which opens in that app (a `tv.youtube.com/watch/…`
 * link, or the Live row's `intent:` URI); null opens the app's home, its leanback launch intent.
 * [installed] is whether the app is on this TV: a way that isn't is a dimmed row with [missing].
 * [channel] names a YouTube TV network ("ABC"), remembered when it's opened.
 */
@Immutable
data class WatchWay(val app: WatchApp, val label: String, val link: String?, val installed: Boolean, val channel: String? = null) {
    /** "Peacock isn't on this TV". */
    val missing: String get() = "${app.label} isn't on this TV"

    /**
     * What to start for a [link] that's a web link or the app's home. Every one names its app's
     * package: on a Google TV (Android 12) a bare link opens Android's app chooser (checked
     * 11/02/2026). An `intent:` URI is parsed by the activity, which sets the package if the URI
     * has none.
     */
    fun spec(): IntentSpec? = when {
        link == null -> IntentSpec.LaunchApp(app.packageName)
        link.startsWith(INTENT_SCHEME, ignoreCase = true) -> null
        else -> IntentSpec.ViewUri(link, app.packageName)
    }

    companion object {
        const val INTENT_SCHEME = "intent:"
    }
}

/** What Watch needs from the home: the Live row's networks (YouTube TV's row and `channels.json`) and the apps on this TV. */
@Immutable
data class WatchOptions(
    val channels: List<ChannelButton> = emptyList(),
    /** The packages of the apps on this TV, from their leanback launch intents. */
    val installed: Set<String> = emptySet(),
)

/**
 * Where Watch goes (F6, F7; ui-spec "Where Watch goes"): a game's networks turned into ordered ways
 * to watch. Pure.
 *
 * - **CBS, FOX, NBC, ABC, ESPN, ESPN2, NFL Network:** YouTube TV on that channel: the Live row's
 *   link for it (YouTube TV's own row first), else `channels.json`'s, else the national id (ESPN,
 *   ESPN2, NFL Network), else YouTube TV's home. Then Paramount+ (CBS), Disney+ (ESPN), Peacock
 *   (NBC) or Fox One (FOX), only when it's on this TV.
 * - **Sunday Ticket** ([NflRules.sundayTicketGame], E): "Sunday Ticket on YouTube TV" (its home,
 *   where you pick the game: no link opens one) first, then the network.
 * - **Prime Video, Netflix, Peacock, YouTube, Paramount+, NFL+:** the app's home.
 * - Ways whose app isn't on this TV come last, dimmed.
 */
object WatchLinks {
    /** YouTube TV's national channels, as `tv.youtube.com/watch/<id>` (plan step 1). */
    val NATIONAL = mapOf("ESPN" to "xDLamOKnZDc", "ESPN2" to "bANYnloGyMw", "NFL Network" to "_pYg9qMKKIA")

    fun ways(game: NflGame, settings: NflSettings, options: WatchOptions, zone: ZoneId): List<WatchWay> {
        fun has(app: WatchApp) = app.packageName in options.installed
        fun way(app: WatchApp, label: String, link: String? = null, channel: String? = null) = WatchWay(app, label, link, has(app), channel)
        val out = mutableListOf<WatchWay>()
        if (NflRules.sundayTicketGame(game, settings, zone)) out += way(WatchApp.YOUTUBE_TV, SUNDAY_TICKET)
        val carriers = (game.tv + game.streaming).map(::network).distinct()
        for (carrier in carriers) {
            when (carrier) {
                in YOUTUBE_TV_NETWORKS -> {
                    val link = channelLink(carrier, options.channels)
                    // Remembered by the Live row's name for it, so it leads the row's networks.
                    val name = options.channels.firstOrNull { it.intentUri == link }?.name ?: carrier
                    out += way(WatchApp.YOUTUBE_TV, "$carrier on YouTube TV", link, channel = name)
                    ALSO[carrier]?.takeIf(::has)?.let { out += way(it, it.label) }
                }
                else -> STREAMING[carrier]?.let { out += way(it, it.label) }
            }
        }
        val ways = out.distinctBy { it.label }
        return ways.filter { it.installed } + ways.filterNot { it.installed }
    }

    /** ESPN's name for a network as the TVs show it: "Fox" is FOX, "NFL Net" is NFL Network. */
    fun network(name: String): String = when (normalisedName(name)) {
        "fox" -> "FOX"
        "nflnet", "nflnetwork", "nfln" -> "NFL Network"
        "primevideo", "amazonprimevideo", "prime" -> "Prime Video"
        "paramount", "paramountplus" -> "Paramount+"
        "nflplus" -> "NFL+"
        else -> name.trim()
    }

    /** The YouTube TV link for [network]: the Live row's (YouTube TV's own row first), else the national id; null is YouTube TV's home. */
    internal fun channelLink(network: String, channels: List<ChannelButton>): String? {
        val names = NAMES[network] ?: setOf(normalisedName(network))
        val matching = channels.filter { normalisedName(it.name) in names }
        val button = matching.firstOrNull { it.source == ChannelButton.Source.YOUTUBE_TV } ?: matching.firstOrNull()
        return button?.intentUri ?: NATIONAL[network]?.let { "https://tv.youtube.com/watch/$it" }
    }

    private const val SUNDAY_TICKET = "Sunday Ticket on YouTube TV"

    private val YOUTUBE_TV_NETWORKS = setOf("CBS", "FOX", "NBC", "ABC", "ESPN", "ESPN2", "NFL Network")

    /** Each network's names in the Live row, normalised ([normalisedName]). */
    private val NAMES = mapOf(
        "CBS" to setOf("cbs"),
        "FOX" to setOf("fox"),
        "NBC" to setOf("nbc"),
        "ABC" to setOf("abc"),
        "ESPN" to setOf("espn"),
        "ESPN2" to setOf("espn2"),
        "NFL Network" to setOf("nflnetwork", "nflnet"),
    )

    /** The second way for a network, only when its app is on this TV. */
    private val ALSO = mapOf(
        "CBS" to WatchApp.PARAMOUNT_PLUS,
        "ESPN" to WatchApp.DISNEY_PLUS,
        "NBC" to WatchApp.PEACOCK,
        "FOX" to WatchApp.FOX_ONE,
    )

    private val STREAMING = mapOf(
        "Prime Video" to WatchApp.PRIME_VIDEO,
        "Netflix" to WatchApp.NETFLIX,
        "Peacock" to WatchApp.PEACOCK,
        "YouTube" to WatchApp.YOUTUBE,
        "Paramount+" to WatchApp.PARAMOUNT_PLUS,
        "NFL+" to WatchApp.NFL_PLUS,
    )
}
