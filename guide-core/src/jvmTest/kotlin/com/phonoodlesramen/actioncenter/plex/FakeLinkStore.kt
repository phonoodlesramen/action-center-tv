package com.phonoodlesramen.actioncenter.plex

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * [PlexLinkStore] in memory, behaving as the TVs' `PlexStore` (`:app`) does: the account and
 * the server count only while their tokens are there, and signing out forgets both.
 */
internal class FakeLinkStore : PlexLinkStore {
    private data class Saved(
        val account: PlexAccount? = null,
        val userToken: String? = null,
        val server: LinkedServer? = null,
        val serverToken: String? = null,
    )

    private val saved = MutableStateFlow(Saved())

    override val link: Flow<PlexLink> = saved.map {
        PlexLink(
            account = it.account?.takeIf { _ -> it.userToken != null },
            server = it.server?.takeIf { _ -> it.serverToken != null },
        )
    }

    /** As `PlexStore.saveAccount`: the plex.tv sign-in. */
    fun saveAccount(token: String, account: PlexAccount) = saved.update { it.copy(account = account, userToken = token) }

    override suspend fun saveServer(server: PlexServer, connection: PlexServers.Candidate) = saved.update {
        it.copy(server = LinkedServer(server.name, server.clientIdentifier, connection.uri, connection.kind), serverToken = server.accessToken)
    }

    override suspend fun userToken(): String? = saved.value.userToken

    override suspend fun serverToken(): String? = saved.value.serverToken

    override suspend fun signOut() = saved.update { Saved() }
}
