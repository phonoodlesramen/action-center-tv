package com.phonoodlesramen.actioncenter.guide

/**
 * Which Plex server the lineup was made from, by its client identifier (G19): when the
 * library syncs from another one, the lineup is remade. Kept beside `guide.db`, not in it,
 * so its schema stays as it is. Null until a starter lineup is made, or after one made
 * nothing, so the next sync tries again. Read and written off the main thread.
 *
 * It also remembers whether the lineup has had its Family channel (K8): a starter lineup
 * from Phase 5 on was made with it in mind, and one made before gets it appended once, so a
 * Family channel deleted by hand stays deleted.
 *
 * The TVs keep it in a small preferences file (`SharedPrefsLineupServer`, in `:app`).
 */
interface LineupServer {
    suspend fun get(): String?

    suspend fun set(clientIdentifier: String?)

    /** Whether the lineup has had its Family channel (K8). One that doesn't remember (a test's) never has. */
    suspend fun familyAdded(): Boolean = false

    suspend fun setFamilyAdded(added: Boolean) {}

    /** Kept in memory only: tests, and a guide without Plex. */
    class InMemory(@Volatile private var value: String? = null, @Volatile private var family: Boolean = false) : LineupServer {
        override suspend fun get(): String? = value

        override suspend fun set(clientIdentifier: String?) {
            value = clientIdentifier
        }

        override suspend fun familyAdded(): Boolean = family

        override suspend fun setFamilyAdded(added: Boolean) {
            family = added
        }
    }
}
