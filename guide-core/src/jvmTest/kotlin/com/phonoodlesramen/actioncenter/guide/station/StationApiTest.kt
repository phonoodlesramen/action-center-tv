package com.phonoodlesramen.actioncenter.guide.station

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.RuleChoices
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationApiTest {
    private val ca = ByteArray(300) { it.toByte() }
    private val tv = ByteArray(91) { (it * 7).toByte() }

    @Test
    fun `both ends work out the same six-character code from the same keys`() {
        val code = StationApi.pairingCode(ca, tv)

        assertEquals(code, StationApi.pairingCode(ca.copyOf(), tv.copyOf()))
        assertEquals(6, code.length)
        assertTrue(code, code.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
    }

    @Test
    fun `another station or another TV gives another code`() {
        val code = StationApi.pairingCode(ca, tv)
        val otherCa = ca.copyOf().also { it[0] = 99 }
        val otherTv = tv.copyOf().also { it[90] = 1 }

        assertNotEquals(code, StationApi.pairingCode(otherCa, tv))
        assertNotEquals(code, StationApi.pairingCode(ca, otherTv))
    }

    @Test
    fun `a slot crosses the wire unchanged`() {
        val slot = GuideSlot(1_000, 2_000, "Frasier", "S3 · E7", "5101", "4800", "/art", "TV-PG", filler = false, offAir = null, summary = "Niles…")

        assertEquals(slot, StationSlot.of(slot).toGuideSlot())
    }

    @Test
    fun `the guide's JSON reads back, rule included, and unknown fields from a newer station are ignored`() {
        val guide = StationGuide(
            version = 412, updatedAtMillis = 1_793_000_000_000, timeZone = "America/Los_Angeles",
            channels = listOf(
                StationChannel(
                    id = "c-comedy", number = 3, name = "Comedy", enabled = true, alignmentMinutes = 30, filler = "trailers",
                    rule = ChannelRule(), contentRatings = listOf("TV-PG"), slots = listOf(StationSlot(1_000, 2_000, "Frasier", itemKey = "5101")),
                ),
            ),
        )
        val text = StationApi.json.encodeToString(guide)

        assertEquals(guide, StationApi.json.decodeFromString<StationGuide>(text))
        val newer = text.replaceFirst("{", "{\"weather\":\"sunny\",")
        assertEquals(guide, StationApi.json.decodeFromString<StationGuide>(newer))
    }

    @Test
    fun `a channel's icon crosses the wire (1_4_0), and a 1_3 station's guide and choices read as Automatic and none`() {
        val channel = StationChannel(
            id = "c-scifi", number = 4, name = "Sci-Fi", icon = "rocket_launch", enabled = true, alignmentMinutes = 30, filler = "trailers",
            rule = ChannelRule(), slots = emptyList(),
        )
        val text = StationApi.json.encodeToString(channel)
        assertEquals("rocket_launch", StationApi.json.decodeFromString<StationChannel>(text).icon)
        assertNull(StationApi.json.decodeFromString<StationChannel>(text.replace(""","icon":"rocket_launch"""", "")).icon)

        val draft = ChannelDraft("Sci-Fi", ChannelRule(), icon = "rocket_launch")
        assertEquals(draft, StationApi.json.decodeFromString<ChannelDraft>(StationApi.json.encodeToString(draft)))
        val older = """{"sections":[],"movieGenres":[],"tvGenres":[],"shows":[],"collections":[],"decades":[],"contentRatings":[]}"""
        assertTrue(StationApi.json.decodeFromString<RuleChoices>(older).icons.isEmpty())
    }

    @Test
    fun `pairing states and an empty spot read as the API documents them`() {
        assertEquals(PairStatus(PairState.PENDING), StationApi.json.decodeFromString<PairStatus>("""{"state":"pending"}"""))
        assertEquals(SpotAnswer(null), StationApi.json.decodeFromString<SpotAnswer>("""{"spot":null}"""))
        assertEquals("""{"state":"linked","serverName":"Basement"}""", StationApi.json.encodeToString(PlexLinkStatus(PlexLinkState.LINKED, "Basement")))
    }
}
