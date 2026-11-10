package com.phonoodlesramen.actioncenter.profiles

/**
 * Each linked kid's server token (K2): got when you link their Plex Home user in Settings ›
 * Profiles (the switch, then `resources`), sealed like yours, and read by the kids' sync.
 * Never logged, never shown. Their Plex Home user's UUID is kept beside it (Phase 11, K3):
 * Plezy's link names it, so Plezy switches to them.
 */
interface KidTokens {
    suspend fun serverToken(kidId: String): String?

    suspend fun save(kidId: String, serverToken: String)

    /** Forgets the token and the UUID. */
    suspend fun forget(kidId: String)

    /** The kid's Plex Home user's UUID; null for a kid linked before Phase 11 (link them again to store it). */
    suspend fun plexUuid(kidId: String): String? = null

    suspend fun saveUuid(kidId: String, uuid: String) {}

    /** Previews and tests: no kid is linked. */
    object None : KidTokens {
        override suspend fun serverToken(kidId: String): String? = null
        override suspend fun save(kidId: String, serverToken: String) {}
        override suspend fun forget(kidId: String) {}
    }
}
