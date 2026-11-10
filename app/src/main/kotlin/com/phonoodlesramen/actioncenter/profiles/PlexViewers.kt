package com.phonoodlesramen.actioncenter.profiles

import com.phonoodlesramen.actioncenter.plex.PlexAccount
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.suggest.Profiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Whose Plex the guide writes to, and who Plezy opens as (Phase 11, K3): the linked server
 * with that user's token in [access], and [profile], the Plex Home user Plezy's link names
 * (null leaves Plezy on the profile it's on). Never logged or shown: it carries a token.
 */
class PlexViewer(val access: ServerAccess, val profile: String?) {
    override fun toString() = "PlexViewer(profile=$profile)"
}

/** The profile on now's Plex user, read when it's needed. */
fun interface PlexViewers {
    /** Null when Plex isn't linked, or for a kid with no Plex user: nothing is sent for them. */
    suspend fun current(): PlexViewer?

    companion object {
        /** Previews and tests: nobody, so nothing is sent. */
        val None = PlexViewers { null }
    }
}

/**
 * [PlexViewers] from the profile on now (K3):
 * - **you:** the linked server's own token. Plezy's link names your account's UUID once a
 *   kid is linked to a Plex Home user, which shows your account is a Plex Home and Plezy
 *   has a profile per user; without that it names nobody, since Plezy drops a link whose
 *   profile it can't find;
 * - **a kid linked to a Plex Home user** (K2): their own server token ([KidTokens]), and
 *   their UUID, or their Plex name when they were linked before Phase 11 (Plezy matches
 *   either);
 * - **a kid without one:** null.
 *
 * @param currentId the profile on now ([ProfileStore.currentId]).
 * @param access the linked server and your token.
 * @param account the signed-in plex.tv account.
 */
class ProfileViewers(
    private val currentId: Flow<String>,
    private val kids: Flow<List<KidEntity>>,
    private val tokens: KidTokens,
    private val access: Flow<ServerAccess?>,
    private val account: suspend () -> PlexAccount?,
) : PlexViewers {
    override suspend fun current(): PlexViewer? {
        val owner = access.first() ?: return null
        val id = currentId.first()
        val kids = kids.first()
        if (id == Profiles.OWNER) {
            val home = kids.any { it.plexUserId != null }
            return PlexViewer(owner, account()?.uuid?.takeIf { home && it.isNotBlank() })
        }
        val kid = kids.firstOrNull { it.id == id }?.takeIf { it.plexUserId != null } ?: return null
        val token = tokens.serverToken(kid.id) ?: return null
        val profile = tokens.plexUuid(kid.id) ?: kid.plexUserTitle?.takeIf { it.isNotBlank() }
        return PlexViewer(owner.copy(token = token), profile)
    }
}
