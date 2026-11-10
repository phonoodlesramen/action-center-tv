package com.phonoodlesramen.actioncenter.nfl

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Show the NFL (O6): off for a new house, on for one that set the NFL up before the switch, and as chosen after. */
class NflSettingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a new house starts with the NFL off`() {
        assertFalse(NflSettings().showing)
    }

    @Test
    fun `a house that used the NFL before the switch has it on`() {
        assertTrue(NflSettings(yourTeams = listOf("DET")).showing)
        assertTrue(NflSettings(areaTeams = listOf("WSH")).showing)
        assertTrue(NflSettings(sundayTicket = true).showing)
        assertTrue(NflSettings(hideScores = true).showing)
    }

    @Test
    fun `once chosen, the choice wins either way`() {
        assertTrue(NflSettings(show = true).showing)
        assertFalse(NflSettings(yourTeams = listOf("DET"), show = false).showing)
    }

    @Test
    fun `settings saved before the switch read as never chosen, and keep the NFL on`() {
        val before = json.decodeFromString(NflSettings.serializer(), """{"yourTeams":["DET"],"areaTeams":[],"sundayTicket":false,"hideScores":false}""")
        assertNull(before.show)
        assertTrue(before.showing)
    }

    @Test
    fun `the choice is kept, and an older reader ignores it`() {
        val saved = json.encodeToString(NflSettings.serializer(), NflSettings(show = false))
        assertEquals(NflSettings(show = false), json.decodeFromString(NflSettings.serializer(), saved))
        // What an older TV or station knows of it: everything but the switch.
        val older = Json { ignoreUnknownKeys = true }.decodeFromString(OlderSettings.serializer(), saved)
        assertEquals(OlderSettings(), older)
    }

    @kotlinx.serialization.Serializable
    private data class OlderSettings(
        val yourTeams: List<String> = emptyList(),
        val areaTeams: List<String> = emptyList(),
        val sundayTicket: Boolean = false,
        val hideScores: Boolean = false,
    )
}
