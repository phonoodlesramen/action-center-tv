package com.phonoodlesramen.actioncenter.tvprovider

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelsAndAppsStoreTest {
    private fun TestScope.store(): ChannelsAndAppsStore {
        val file = File(Files.createTempDirectory("channels").toFile(), "channels_and_apps.preferences_pb")
        return ChannelsAndAppsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    @Test
    fun `every app feeds Continue in your apps until it's turned off, YouTube included`() = runTest {
        assertEquals(ChannelsAndAppsPrefs(), store().prefs.first())
        assertEquals(emptySet<String>(), ChannelsAndAppsPrefs().hiddenApps)
    }

    @Test
    fun `apps turn off and back on, one at a time`() = runTest {
        val store = store()

        store.setAppShown("com.spotify.tv.android", shown = false)
        store.setAppShown("com.google.android.youtube.tv", shown = false)
        assertEquals(setOf("com.spotify.tv.android", "com.google.android.youtube.tv"), store.prefs.first().hiddenApps)

        store.setAppShown("com.spotify.tv.android", shown = true)
        store.setAppShown("com.disney.disneyplus", shown = true)
        assertEquals(setOf("com.google.android.youtube.tv"), store.prefs.first().hiddenApps)
    }
}
