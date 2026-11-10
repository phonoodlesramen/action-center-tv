package com.phonoodlesramen.actioncenter.profiles

import com.phonoodlesramen.actioncenter.plex.PlexAccounts
import com.phonoodlesramen.actioncenter.plex.PlexException
import com.phonoodlesramen.actioncenter.plex.PlexHome
import com.phonoodlesramen.actioncenter.plex.PlexHomeUser
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Your Home's managed users, to link a kid to (K2), or why they couldn't be read. */
data class LinkableUsers(val users: List<PlexHomeUser> = emptyList(), val problem: String? = null)

/** How linking a kid to their Plex Home user went (K2). */
sealed interface PlexLinkResult {
    /** [kid] now carries the user's id and title, and their server token is sealed in [KidTokens]. */
    data class Linked(val kid: KidEntity) : PlexLinkResult

    /** Nothing changed: [why] is a sentence for Settings › Profiles. */
    data class Failed(val why: String) : PlexLinkResult
}

/**
 * What Settings › Profiles reads and changes (K10, Phase 5): the kids, the age-bracket table
 * (K3) and each kid's Plex Home user (K2). Saves are fire-and-forget, so one made just
 * before Back still lands. Defaults: you alone, nothing to link (previews, screenshots).
 */
interface ProfilesController {
    val kids: Flow<List<KidEntity>>
    val brackets: Flow<List<AgeBracket>>

    fun saveKid(kid: KidEntity) {}

    /** Removes [kid] and forgets their Plex token. Nothing changes in Plex. */
    fun removeKid(kid: KidEntity) {}

    fun saveBrackets(brackets: List<AgeBracket>) {}

    /** Your Home's managed users (not the admins), from plex.tv. */
    suspend fun linkableUsers(): LinkableUsers = LinkableUsers(problem = NOT_SIGNED_IN)

    /**
     * Links [kid] to [user]: their server token from plex.tv's switch, then `resources`.
     * [pin] is [user]'s own when they have one ([PlexHomeUser.protected]): it goes to
     * plex.tv's switch for this call only, and is never stored or logged.
     */
    suspend fun link(kid: KidEntity, user: PlexHomeUser, pin: String? = null): PlexLinkResult = PlexLinkResult.Failed(NOT_SIGNED_IN)

    /** Forgets [kid]'s Plex user and token: their home falls back to rating filters alone. */
    fun unlink(kid: KidEntity) {}

    companion object None : ProfilesController {
        override val kids: Flow<List<KidEntity>> = flowOf(emptyList())
        override val brackets: Flow<List<AgeBracket>> = flowOf(AgeBrackets.DEFAULT)

        const val NOT_SIGNED_IN = "Sign in to Plex first, in Settings › Plex."
        const val NO_SERVER = "Link your Plex server first, in Settings › Plex."
        const val OFFLINE = "Couldn't reach plex.tv. Check the Shield is online, then try again."
    }
}

/**
 * [ProfilesController] on [ProfileStore] and [KidTokens], with plex.tv's Plex Home calls
 * (research §4, community-documented). Every save runs in order on [scope], one after
 * another, so two quick changes never land the wrong way round. Nothing here is logged:
 * names, birthdays, PINs and tokens stay on the Shield.
 *
 * @param ownerToken your plex.tv token; [serverId] the linked server's machine id;
 *   [plexChanged] runs after a kid is linked, unlinked or removed, off the save queue.
 */
class StoredProfiles(
    private val store: ProfileStore,
    private val tokens: KidTokens,
    private val home: PlexHome,
    private val accounts: PlexAccounts,
    private val ownerToken: suspend () -> String?,
    private val serverId: suspend () -> String?,
    private val scope: CoroutineScope,
    private val plexChanged: suspend () -> Unit = {},
) : ProfilesController {
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in work) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A save that fails leaves the profile as it was; Settings shows what's stored.
                }
            }
        }
    }

    override val kids: Flow<List<KidEntity>> = store.kids
    override val brackets: Flow<List<AgeBracket>> = store.brackets

    override fun saveKid(kid: KidEntity) = later { store.saveKid(kid) }

    override fun removeKid(kid: KidEntity) = later {
        tokens.forget(kid.id)
        store.removeKid(kid.id)
        changed()
    }

    override fun saveBrackets(brackets: List<AgeBracket>) = later { store.saveBrackets(brackets) }

    override suspend fun linkableUsers(): LinkableUsers {
        val owner = ownerToken() ?: return LinkableUsers(problem = ProfilesController.NOT_SIGNED_IN)
        if (serverId() == null) return LinkableUsers(problem = ProfilesController.NO_SERVER)
        return try {
            LinkableUsers(home.users(owner).filter { !it.admin })
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexException) {
            LinkableUsers(
                problem = when (e) {
                    is PlexException.Unreachable -> ProfilesController.OFFLINE
                    is PlexException.Unauthorized -> EXPIRED
                    else -> CANT_LIST
                },
            )
        } catch (_: Exception) {
            LinkableUsers(problem = CANT_LIST)
        }
    }

    override suspend fun link(kid: KidEntity, user: PlexHomeUser, pin: String?): PlexLinkResult {
        val token = when (val got = serverToken(user, pin?.takeIf { it.isNotEmpty() })) {
            is Got.Token -> got.token
            is Got.Problem -> return PlexLinkResult.Failed(got.why)
        }
        later {
            tokens.save(kid.id, token)
            // Plezy's link names them by it (Phase 11, K3).
            if (user.uuid.isNotBlank()) tokens.saveUuid(kid.id, user.uuid)
            // The kid as stored now, so a change made while linking stays.
            val latest = store.kids.first().firstOrNull { it.id == kid.id } ?: kid
            store.saveKid(latest.copy(plexUserId = user.id, plexUserTitle = user.title))
            changed()
        }
        return PlexLinkResult.Linked(kid.copy(plexUserId = user.id, plexUserTitle = user.title))
    }

    override fun unlink(kid: KidEntity) = later {
        tokens.forget(kid.id)
        val latest = store.kids.first().firstOrNull { it.id == kid.id } ?: return@later
        store.saveKid(latest.copy(plexUserId = null, plexUserTitle = null))
        changed()
    }

    private sealed interface Got {
        data class Token(val token: String) : Got
        data class Problem(val why: String) : Got
    }

    /** The switch gives [user]'s plex.tv token; `resources` with it gives their token for your server. */
    private suspend fun serverToken(user: PlexHomeUser, pin: String?): Got {
        val owner = ownerToken() ?: return Got.Problem(ProfilesController.NOT_SIGNED_IN)
        val server = serverId() ?: return Got.Problem(ProfilesController.NO_SERVER)
        return try {
            val switched = home.switchTo(owner, user, pin)
            accounts.servers(switched.token).firstOrNull { it.clientIdentifier == server }?.accessToken?.takeIf { it.isNotEmpty() }
                ?.let { Got.Token(it) }
                ?: Got.Problem("${user.title} can't see your Plex server. Give them access to its libraries in Plex, then try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlexException) {
            when (e) {
                is PlexException.Unreachable -> Got.Problem(ProfilesController.OFFLINE)
                else -> Got.Problem(refused(e, user, pin))
            }
        } catch (_: Exception) {
            Got.Problem("Plex's answer couldn't be read. Try again later.")
        }
    }

    /** Why plex.tv said no: a PIN it turned down, your own sign-in, or anything else. */
    private fun refused(e: PlexException, user: PlexHomeUser, pin: String?): String = when {
        pin != null && (e is PlexException.Unauthorized || (e is PlexException.Failed && e.code in 400..499)) ->
            "Plex didn't accept that PIN for ${user.title}. Try again."
        e is PlexException.Unauthorized -> EXPIRED
        else -> "Plex couldn't switch to ${user.title}. Try again later."
    }

    private fun later(job: suspend () -> Unit) {
        work.trySend(job)
    }

    /** Their Plex and picks now, not at the next sync; apart from the queue, so saves never wait on the network. */
    private fun changed() {
        scope.launch { plexChanged() }
    }

    private companion object {
        const val EXPIRED = "Plex says your sign-in has expired. Sign in again in Settings › Plex."
        const val CANT_LIST = "Plex couldn't list your Home's users. Try again later."
    }
}
