package com.phonoodlesramen.actioncenter

import com.phonoodlesramen.actioncenter.nfl.EspnClient
import com.phonoodlesramen.actioncenter.plex.PlexAccount
import com.phonoodlesramen.actioncenter.plex.PlexAccounts
import com.phonoodlesramen.actioncenter.plex.PlexPin
import com.phonoodlesramen.actioncenter.plex.PlexServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * The app under Robolectric (robolectric.properties): plex.tv is a fake that shows code TEST
 * forever, and ESPN (Phase 13) is a closed port on this machine, so the NFL never reaches it.
 */
class TestActionCenterApp : ActionCenterApp() {
    override fun createContainer() = AppContainer(this, plexOverride = UnclaimedPlex, espnOverride = NoEspn)

    object UnclaimedPlex : PlexAccounts {
        const val CODE = "TEST"

        override suspend fun createPin() = PlexPin(1, CODE, Long.MAX_VALUE, null)
        override suspend fun checkPin(id: Long) = PlexPin(id, CODE, Long.MAX_VALUE, null)
        override suspend fun account(token: String) = PlexAccount(1, "uuid", "test", "Test")
        override suspend fun servers(token: String) = emptyList<PlexServer>()
    }

    /** Nothing listens on port 9 here: every call fails at once as "Can't reach ESPN." */
    private val NoEspn = "http://127.0.0.1:9/".toHttpUrl().let { EspnClient(OkHttpClient(), it, it) }
}
