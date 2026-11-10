package com.phonoodlesramen.actioncenter.player

import android.content.Intent
import com.phonoodlesramen.actioncenter.ActionCenterApp
import com.phonoodlesramen.actioncenter.AppContainer
import com.phonoodlesramen.actioncenter.TestActionCenterApp
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.handoff.IntentSpec
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexWatchApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.profiles.PlexViewer
import com.phonoodlesramen.actioncenter.profiles.PlexViewers
import com.phonoodlesramen.actioncenter.settings.Settings
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The app for [GuidePlayerActivityTest]: the real container (with the fake plex.tv, as
 * [TestActionCenterApp]), and the player's parts faked: the sample lineup, a file for every
 * key but [sources]' missing ones, a fake player, and a clock that stands still. Plex's
 * writes (Phase 11) are recorded in [plexWrites], and fail while [plexDown].
 */
class PlayerTestApp : ActionCenterApp(), GuidePlayerParts.Provider {
    val lineup = MutableStateFlow<GuideCardState>(SampleGuide.ready())
    val sources = FakeSlotSources()
    val streams = FakeSlotStreams()
    val videos = mutableListOf<FakeGuideVideo>()
    var nowMillis = SampleGuide.WEEKNIGHT

    /** The linked server's machine id, for Watch in Plezy: none until a test sets it. */
    val serverId = MutableStateFlow<String?>(null)

    /** Every handoff asked for; [resolves] says whether it finds an app. */
    val handoffs = mutableListOf<List<IntentSpec>>()
    var resolves = true

    /** The saved spot (P3), in memory; saving and forgetting it happen at once. */
    val spots = MemorySpots()

    /** Settings › Plex › Play in (Phase 11). */
    val playIn = MutableStateFlow(PlayIn.PLEZY)

    /** Each key's credits marker; the rest have none. */
    val credits = mutableMapOf<String, Long>()

    /** Each key's next episode, for the credits card. */
    val nextEpisodes = mutableMapOf<String, NextEpisode>()

    /** Plex's writes, as "progress <key> @<ms> as <token>" and "watched <key> as <token>". */
    val plexWrites = mutableListOf<String>()
    var plexDown = false

    /** Who's watching, as Plex knows them: you, with a made-up token and UUID. */
    var viewer: PlexViewer? = PlexViewer(ServerAccess("https://10-0-0-5.abc.plex.direct:32400", "owner-token"), "u-owner")

    override fun createContainer() = AppContainer(this, plexOverride = TestActionCenterApp.UnclaimedPlex)

    private val watch = object : PlexWatchApi {
        override suspend fun setPosition(access: ServerAccess, ratingKey: String, positionMs: Long) {
            if (plexDown) throw PlexException.Failed(503, "down")
            plexWrites += "progress $ratingKey @$positionMs as ${access.token}"
        }

        override suspend fun markWatched(access: ServerAccess, ratingKey: String) {
            if (plexDown) throw PlexException.Failed(503, "down")
            plexWrites += "watched $ratingKey as ${access.token}"
        }
    }

    override fun guidePlayerParts() = GuidePlayerParts(
        lineup = lineup,
        sources = sources,
        access = flowOf(null),
        look = flowOf(Settings()),
        openVideo = { _, _, events -> FakeGuideVideo(events).also { videos += it } },
        clock = { nowMillis },
        streams = streams,
        serverId = serverId,
        resolve = { specs ->
            handoffs += specs
            if (resolves) Intent(Intent.ACTION_VIEW).setPackage("com.edde746.plezy") else null
        },
        spots = spots,
        background = CoroutineScope(Dispatchers.Unconfined),
        playIn = playIn,
        credits = SlotCredits { credits[it] },
        nextEpisode = { nextEpisodes[it] },
        plexWrites = GuidePlexWrites(PlexViewers { viewer }, watch, counting = { true }, scope = CoroutineScope(Dispatchers.Unconfined)),
    )
}
