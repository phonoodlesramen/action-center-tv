package com.phonoodlesramen.actioncenter.profiles

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A [ProfileStore] in memory that keeps every value it was handed, so a test can say what never reached it. */
class MemoryProfileStore(kids: List<KidEntity> = emptyList()) : ProfileStore {
    override val kids = MutableStateFlow(kids)
    override val brackets = MutableStateFlow(AgeBrackets.DEFAULT)
    override val currentId: Flow<String> = ProfileStore.None.currentId

    /** Everything saved, in order, as text. */
    val written = mutableListOf<String>()

    override fun profiles(ownerName: Flow<String>, today: Flow<LocalDate>) = ProfileStore.None.profiles(ownerName, today)
    override fun current(ownerName: Flow<String>, today: Flow<LocalDate>) = ProfileStore.None.current(ownerName, today)
    override suspend fun switchTo(profileId: String) {}

    override suspend fun saveKid(kid: KidEntity) {
        written += kid.toString()
        kids.update { list -> if (list.any { it.id == kid.id }) list.map { if (it.id == kid.id) kid else it } else list + kid }
    }

    override suspend fun removeKid(id: String) {
        written += "remove $id"
        kids.update { list -> list.filter { it.id != id } }
    }

    override suspend fun saveBrackets(brackets: List<AgeBracket>) {
        written += brackets.toString()
        this.brackets.value = brackets
    }
}

/** [KidTokens] in memory, keeping every value it was handed. */
class MemoryKidTokens : KidTokens {
    val tokens = mutableMapOf<String, String>()
    val written = mutableListOf<String>()

    override suspend fun serverToken(kidId: String) = tokens[kidId]

    override suspend fun save(kidId: String, serverToken: String) {
        written += "$kidId=$serverToken"
        tokens[kidId] = serverToken
    }

    override suspend fun forget(kidId: String) {
        written += "forget $kidId"
        tokens.remove(kidId)
        uuids.remove(kidId)
    }

    val uuids = mutableMapOf<String, String>()

    override suspend fun plexUuid(kidId: String) = uuids[kidId]

    override suspend fun saveUuid(kidId: String, uuid: String) {
        uuids[kidId] = uuid
    }
}
