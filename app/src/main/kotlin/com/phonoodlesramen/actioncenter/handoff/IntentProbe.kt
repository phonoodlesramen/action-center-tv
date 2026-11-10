package com.phonoodlesramen.actioncenter.handoff

import java.net.URLEncoder

/** An intent we might start, kept as plain data so the fallback logic is testable. */
sealed interface IntentSpec {
    /** ACTION_VIEW on [uri], optionally pinned to [packageName]. */
    data class ViewUri(val uri: String, val packageName: String? = null) : IntentSpec

    /** The app's own launch activity: its TV one first, then its phone one. */
    data class LaunchApp(val packageName: String) : IntentSpec
}

/** How a candidate reads on the probe screen and in the device report. */
fun IntentSpec.label(): String = when (this) {
    is IntentSpec.ViewUri -> "Open ${uri.substringBefore('?')} with ${packageName ?: "any app"}"
    is IntentSpec.LaunchApp -> "Launch $packageName"
}

fun interface IntentResolver {
    fun canResolve(spec: IntentSpec): Boolean
}

data class ProbeResult(val spec: IntentSpec, val resolves: Boolean)

/**
 * Where a library title plays (Phase 11, K4): Settings › Plex › Play in, one choice per TV.
 * The other app is the fallback.
 */
enum class PlayIn(val label: String, val packageName: String) {
    PLEZY("Plezy", Handoffs.PLEZY),
    PLEX("Plex", Handoffs.PLEX),
    ;

    companion object {
        /** The setting, or with none chosen, Plezy when it's installed and Plex when it isn't (K4). */
        fun of(choice: PlayIn?, plezyInstalled: Boolean): PlayIn = choice ?: if (plezyInstalled) PLEZY else PLEX
    }
}

/** Ordered handoff chains, best option first. */
object Handoffs {
    const val YOUTUBE_TV = "com.google.android.youtube.tv"
    const val PLEZY = "com.edde746.plezy"
    const val PLEX = "com.plexapp.android"

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]+")
    private val SERVER_ID = Regex("[A-Za-z0-9-]+")
    private val RATING_KEY = Regex("[0-9]+")

    fun trailer(videoId: String): List<IntentSpec> {
        require(VIDEO_ID.matches(videoId)) { "Bad YouTube video id: $videoId" }
        return listOf(
            IntentSpec.ViewUri("https://www.youtube.com/watch?v=$videoId", YOUTUBE_TV),
            IntentSpec.ViewUri("vnd.youtube:$videoId"),
        )
    }

    /**
     * Plex playback handoff (D11, Phase 11), in the app [app] names (Settings › Plex › Play in):
     * - **Plezy:** its link, which plays the title (from where Plex last had it), then
     *   Plezy's home, then the Plex app's home;
     * - **Plex:** the Plex app's link, which plays the title once Plex knows who's watching
     *   ([plexLink]), then Plex's home, then Plezy's link and its home.
     *
     * @param serverId the Plex server's machine identifier (`clientIdentifier`).
     * @param profile the Plex Home user Plezy switches to first (K3): their UUID, or their
     *   name. Null leaves Plezy on the profile it's on. The Plex app has no such link.
     */
    fun play(app: PlayIn, serverId: String, ratingKey: String, profile: String? = null): List<IntentSpec> = when (app) {
        PlayIn.PLEZY -> listOf(
            plezy(serverId, ratingKey, profile),
            IntentSpec.LaunchApp(PLEZY),
            IntentSpec.LaunchApp(PLEX),
        )
        PlayIn.PLEX -> listOf(
            IntentSpec.ViewUri(plexLink(serverId, ratingKey), PLEX),
            IntentSpec.LaunchApp(PLEX),
            plezy(serverId, ratingKey, profile),
            IntentSpec.LaunchApp(PLEZY),
        )
    }

    /**
     * Trailer handoff (Phase 2 plan §6): the title's Plex trailer extra through Plezy's
     * deep link, as Play does; with none, or when Plezy can't open it, a YouTube search
     * for "{title} {year} trailer". YouTube for Android TV has no ACTION_SEARCH filter but
     * opens youtube.com links, so the search is a link: to YouTube first, then any app
     * that opens it. Never an app's home: that wouldn't play a trailer. The Plex app has no
     * link that plays an extra, so Play in doesn't apply.
     *
     * @param serverId the Plex server's machine identifier; null skips the Plex extra.
     * @param extraKey the trailer extra's ratingKey (from `primaryExtraKey`); null when there's none.
     */
    fun trailerFor(title: String, year: Int?, serverId: String?, extraKey: String?): List<IntentSpec> {
        val query = listOfNotNull(title, year?.toString(), "trailer").joinToString(" ")
        val search = "https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}"
        val extra = if (serverId != null && extraKey != null) listOf(plezy(serverId, extraKey, profile = null)) else emptyList()
        return extra + listOf(IntentSpec.ViewUri(search, YOUTUBE_TV), IntentSpec.ViewUri(search))
    }

    /**
     * The Plex app's link to a title (Phase 11): `plex://libraries/<machineId>/unmatched/<key>`,
     * the key being `/library/metadata/<ratingKey>` percent-encoded, for films and episodes
     * alike. Community reports (Plex forum, Oct 2026; SeerrTV PR #16) found it;
     * `plex://preplay`, `plex://play` and `plex://server://` open Plex's home.
     *
     * **Checked on the Shield** (10/31/2026, Plex for Android TV 2026.21.0, plan step 2): it
     * starts the title playing, after Plex's own "Who's watching?" when Plex asks. No `tv/`
     * segment: SeerrTV's `tv/` is for a show's page. It starts partway in, at the server's
     * `viewOffset`, as Plezy does, so a branch's resume point applies to it too. Change the
     * link here only.
     */
    fun plexLink(serverId: String, ratingKey: String): String {
        checkIds(serverId, ratingKey)
        return "plex://libraries/$serverId/unmatched/${encode("/library/metadata/$ratingKey")}"
    }

    /**
     * Plezy's link (source at `7b999fb`, `lib/services/deep_links/deep_link.dart`):
     * `plezy://play?server=<machineId>&id=<ratingKey>`. Plezy fetches the item from the
     * server and plays it from its `viewOffset`, an extra included. With [profile], Plezy
     * switches to that Plex Home user first (matched by profile id, UUID, then name;
     * a protected user asks for its PIN), and drops the link if none matches.
     */
    private fun plezy(serverId: String, ratingKey: String, profile: String?): IntentSpec {
        checkIds(serverId, ratingKey)
        val user = profile?.trim()?.takeIf { it.isNotEmpty() }?.let { "&profile=${encode(it)}" }.orEmpty()
        return IntentSpec.ViewUri("plezy://play?server=$serverId&id=$ratingKey$user", PLEZY)
    }

    private fun checkIds(serverId: String, ratingKey: String) {
        require(SERVER_ID.matches(serverId)) { "Bad Plex server id: $serverId" }
        require(RATING_KEY.matches(ratingKey)) { "Bad Plex ratingKey: $ratingKey" }
    }

    /** Percent-encoded for a link: a space is `%20`, as Dart's query parser and Plex's path both read it. */
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

class IntentProbe(private val resolver: IntentResolver) {
    /** The first candidate the device can handle, or null when none can. */
    fun firstResolvable(candidates: List<IntentSpec>): IntentSpec? =
        candidates.firstOrNull(resolver::canResolve)

    /** Every candidate and whether it resolves, for the probe screen and device report. */
    fun probe(candidates: List<IntentSpec>): List<ProbeResult> =
        candidates.map { ProbeResult(it, resolver.canResolve(it)) }
}
