package com.phonoodlesramen.actioncenter.plex

import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 8 made the device headers parameters: the TVs still say exactly what they always have. */
class ShieldPlexDeviceTest {
    private val headers = PlexHeaders({ "client-123" }, "0.2.0", SHIELD_PLEX_DEVICE)

    @Test
    fun `a request says it's a SHIELD on Android 11`() = runTest {
        val request = headers.apply(Request.Builder().url("https://plex.tv/api/v2/user")).build()

        assertEquals("Android", request.header("X-Plex-Platform"))
        assertEquals("11", request.header("X-Plex-Platform-Version"))
        assertEquals("SHIELD Android TV", request.header("X-Plex-Device"))
        assertEquals("SHIELD", request.header("X-Plex-Device-Name"))
    }

    @Test
    fun `a media request says the same`() = runTest {
        val media = headers.media("server-token")

        assertEquals(
            mapOf(
                "X-Plex-Client-Identifier" to "client-123",
                "X-Plex-Product" to "Action Center",
                "X-Plex-Version" to "0.2.0",
                "X-Plex-Platform" to "Android",
                "X-Plex-Device" to "SHIELD Android TV",
                "X-Plex-Token" to "server-token",
            ),
            media,
        )
    }
}
