package com.phonoodlesramen.actioncenter.plex

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sealing that's easy to spot: anything stored in plain text would fail these tests. */
class FakeBox : SecretBox {
    override fun seal(plain: String) = "sealed:" + plain.reversed()
    override fun open(sealed: String) = if (sealed.startsWith("sealed:")) sealed.removePrefix("sealed:").reversed() else null
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlexSessionTest {
    private val account = PlexAccount(1, "uuid", "example-user", "Example User")

    private fun server(name: String, id: String, owned: Boolean = true) = PlexServer(
        name, id, "$id-token", owned,
        listOf(PlexConnection("https://$id.plex.direct:32400", "https", "192.168.1.20", 32400, local = true, relay = false, ipv6 = false)),
    )

    private class FakeAccounts(val clock: () -> Long) : PlexAccounts {
        var pinCount = 0
        var claimOnCheck = Int.MAX_VALUE
        var checks = 0
        var offline = false
        var servers: List<PlexServer> = emptyList()
        var serversUnauthorized = false
        lateinit var account: PlexAccount

        override suspend fun createPin(): PlexPin {
            if (offline) throw PlexException.Unreachable(java.io.IOException("down"))
            pinCount++
            checks = 0
            return PlexPin(pinCount.toLong(), "CD${pinCount}X", clock() + 900_000, null)
        }

        override suspend fun checkPin(id: Long): PlexPin? {
            checks++
            return PlexPin(id, "code", 0, if (checks >= claimOnCheck) "user-token" else null)
        }

        override suspend fun account(token: String) = account

        override suspend fun servers(token: String): List<PlexServer> {
            if (serversUnauthorized) throw PlexException.Unauthorized()
            return servers
        }
    }

    private class Setup(val store: PlexStore, val accounts: FakeAccounts, val session: PlexSession)

    private fun TestScope.setup(reachable: Boolean = true): Setup {
        val file = File(Files.createTempDirectory("plex").toFile(), "plex.preferences_pb")
        val store = PlexStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, FakeBox())
        val accounts = FakeAccounts { testScheduler.currentTime }.also { it.account = account }
        val reach = ServerReach { s -> if (reachable) PlexServers.candidates(s).first() else null }
        return Setup(store, accounts, PlexSession(store, accounts, reach) { testScheduler.currentTime })
    }

    @Test
    fun `shows a code, checks it once a second, and links your only server once it's claimed`() = runTest {
        val (store, accounts, session) = setup().let { Triple(it.store, it.accounts, it.session) }
        accounts.servers = listOf(server("Basement", "aaaa"), server("Friend's", "eeee", owned = false))
        accounts.claimOnCheck = 5
        val job = launch { session.link() }

        runCurrent()
        assertEquals(PlexSignIn.ShowingCode("CD1X", 900_000), session.status.value)
        advanceTimeBy(4_500)
        assertEquals(4, accounts.checks)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(PlexSignIn.Linked, session.status.value)
        val link = store.link.first()
        assertEquals(account, link.account)
        assertEquals(LinkedServer("Basement", "aaaa", "https://aaaa.plex.direct:32400", ConnectionKind.LOCAL), link.server)
        assertEquals("user-token", store.userToken())
        assertEquals("aaaa-token", store.serverToken())
        assertTrue(job.isCompleted)
    }

    @Test
    fun `an expired code is replaced by a new one`() = runTest {
        val s = setup()
        val job = launch { s.session.link() }

        advanceTimeBy(900_000 + 1_500)

        assertEquals(2, s.accounts.pinCount)
        assertEquals("CD2X", (s.session.status.value as PlexSignIn.ShowingCode).code)
        job.cancel()
    }

    @Test
    fun `without plex_tv it says offline and tries again later`() = runTest {
        val s = setup()
        s.accounts.offline = true
        val job = launch { s.session.link() }

        runCurrent()
        assertEquals(PlexSignIn.Offline, s.session.status.value)
        s.accounts.offline = false
        advanceTimeBy(PlexSession.RETRY_MILLIS + 1)

        assertTrue(s.session.status.value is PlexSignIn.ShowingCode)
        job.cancel()
    }

    @Test
    fun `owning more than one server asks which (P2)`() = runTest {
        val s = setup()
        s.accounts.servers = listOf(server("Basement", "aaaa"), server("Attic", "bbbb"))
        s.accounts.claimOnCheck = 1
        val job = launch { s.session.link() }
        advanceTimeBy(1_500)

        assertEquals(
            PlexSignIn.ChooseServer(listOf(ServerChoice("Basement", "aaaa"), ServerChoice("Attic", "bbbb"))),
            s.session.status.value,
        )
        s.session.choose("bbbb")
        runCurrent()

        assertEquals("Attic", s.store.link.first().server?.name)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `an account with no server of its own says so`() = runTest {
        val s = setup()
        s.accounts.servers = listOf(server("Friend's", "eeee", owned = false))
        s.accounts.claimOnCheck = 1
        val job = launch { s.session.link() }
        advanceTimeBy(1_500)

        assertEquals(PlexSignIn.NoServer, s.session.status.value)
        assertTrue(s.store.link.first().signedIn)
        job.cancel()
    }

    @Test
    fun `a server that doesn't answer is retried, not forgotten`() = runTest {
        val s = setup(reachable = false)
        s.accounts.servers = listOf(server("Basement", "aaaa"))
        s.accounts.claimOnCheck = 1
        val job = launch { s.session.link() }
        advanceTimeBy(1_500)

        assertEquals(PlexSignIn.CantReachServer("Basement"), s.session.status.value)
        assertNull(s.store.link.first().server)
        job.cancel()
    }

    @Test
    fun `a revoked token signs out and shows a new code`() = runTest {
        val s = setup()
        s.store.saveAccount("old-token", account)
        s.accounts.serversUnauthorized = true
        val job = launch { s.session.link() }
        advanceTimeBy(PlexSession.RETRY_MILLIS + 1)

        assertFalse(s.store.link.first().signedIn)
        assertTrue(s.session.status.value is PlexSignIn.ShowingCode)
        job.cancel()
    }

    @Test
    fun `tokens are sealed, the client id is kept, and sign-out forgets the rest`() = runTest {
        val s = setup()
        val id = s.store.clientId()
        s.store.saveAccount("user-token", account)
        s.store.saveServer(server("Basement", "aaaa"), PlexServers.Candidate("https://x", ConnectionKind.LOCAL))

        assertEquals(id, s.store.clientId())
        assertNotEquals("user-token", FakeBox().seal("user-token"))
        assertTrue(s.store.link.first().linked)

        s.session.signOut()

        assertEquals(PlexLink(), s.store.link.first())
        assertNull(s.store.userToken())
        assertEquals(id, s.store.clientId())
    }

    @Test
    fun `a token that can't be unsealed counts as signed out`() = runTest {
        val file = File(Files.createTempDirectory("plex").toFile(), "plex.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        PlexStore(store, FakeBox()).saveAccount("user-token", account)

        val unreadable = PlexStore(store, object : SecretBox {
            override fun seal(plain: String) = plain
            override fun open(sealed: String): String? = null
        })

        assertFalse(unreadable.link.first().signedIn)
    }
}
