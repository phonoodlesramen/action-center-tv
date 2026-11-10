package com.phonoodlesramen.actioncenter.profiles

import com.phonoodlesramen.actioncenter.plex.PlexAccount
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.suggest.Profiles
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Whose Plex the guide writes to, and who Plezy opens as (Phase 11, K3). Every name, id and token is made up. */
class PlexViewersTest {
    private val server = ServerAccess("https://10-0-0-5.abc.plex.direct:32400", "owner-token")
    private val account = PlexAccount(1, "u-owner", "sam", "Sam")
    private val maya = KidEntity("kid-maya", "Maya", 0xFFFF8A65, LocalDate.of(2017, 3, 14).toEpochDay(), plexUserId = 1002, plexUserTitle = "Maya R")
    private val leo = KidEntity("kid-leo", "Leo", 0xFF4FC3F7, LocalDate.of(2018, 6, 1).toEpochDay())

    private val current = MutableStateFlow(Profiles.OWNER)
    private val kids = MutableStateFlow(listOf(maya, leo))
    private val tokens = MemoryKidTokens()
    private val access = MutableStateFlow<ServerAccess?>(server)
    private val viewers = ProfileViewers(current, kids, tokens, access, account = { account })

    @Test
    fun `you are the owner's token, and Plezy's profile is your UUID once your Home has a linked kid`() = runTest {
        val you = viewers.current()!!

        assertEquals("owner-token" to "u-owner", you.access.token to you.profile)

        kids.value = listOf(leo)
        assertNull("no Plex Home in sight: Plezy stays on its profile", viewers.current()!!.profile)
    }

    @Test
    fun `a kid linked to a Plex user is their token and their UUID`() = runTest {
        tokens.save("kid-maya", "maya-token")
        tokens.saveUuid("kid-maya", "u-maya")
        current.value = "kid-maya"

        val kid = viewers.current()!!

        assertEquals("maya-token" to "u-maya", kid.access.token to kid.profile)
        assertEquals("your server's address", server.baseUri, kid.access.baseUri)
    }

    @Test
    fun `a kid linked before Phase 11 is named by their Plex name`() = runTest {
        tokens.save("kid-maya", "maya-token")
        current.value = "kid-maya"

        assertEquals("Maya R", viewers.current()!!.profile)
    }

    @Test
    fun `a kid without a Plex user, or without their token, is nobody to Plex`() = runTest {
        current.value = "kid-leo"
        assertNull(viewers.current())

        current.value = "kid-maya"
        assertNull("their token unreadable: link them again", viewers.current())
    }

    @Test
    fun `nothing is anybody while Plex isn't linked`() = runTest {
        access.value = null

        assertNull(viewers.current())
    }

    @Test
    fun `a viewer never prints its token`() {
        assertEquals("PlexViewer(profile=u-maya)", PlexViewer(server, "u-maya").toString())
    }
}
