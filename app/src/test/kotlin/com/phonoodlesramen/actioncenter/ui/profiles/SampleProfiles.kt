package com.phonoodlesramen.actioncenter.ui.profiles

import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.plex.PlexHomeUser
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.profiles.AgeBracket
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.profiles.KidEntity
import com.phonoodlesramen.actioncenter.profiles.LinkableUsers
import com.phonoodlesramen.actioncenter.profiles.PlexLinkResult
import com.phonoodlesramen.actioncenter.profiles.ProfilesController
import com.phonoodlesramen.actioncenter.profiles.RoomProfileStore
import com.phonoodlesramen.actioncenter.ui.home.SampleHomeState
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Two made-up kids, Maya (9, coral, linked) and Leo (7, green), for Phase 5's tests and screenshots. */
internal object SampleProfiles {
    val maya = KidEntity(
        id = "kid-maya",
        name = "Maya",
        color = 0xFFFF8A65,
        birthday = LocalDate.of(2017, 3, 14).toEpochDay(),
        plexUserId = 1002,
        plexUserTitle = "Maya R",
        apps = "com.disney.disneyplus/com.disney.disneyplus.MainActivity|com.edde746.plezy/com.edde746.plezy.MainActivity",
        sort = 0,
    )
    val leo = KidEntity(
        id = "kid-leo",
        name = "Leo",
        color = 0xFFAED581,
        birthday = LocalDate.of(2019, 6, 2).toEpochDay(),
        apps = "com.edde746.plezy/com.edde746.plezy.MainActivity",
        sort = 1,
    )
    val kids = listOf(maya, leo)

    val mayaUser = PlexHomeUser(1002, "u-maya", "Maya R", admin = false, restricted = true, restrictionProfile = "older_kid", protected = false)
    val leoUser = PlexHomeUser(1003, "u-leo", "Leo R", admin = false, restricted = true, restrictionProfile = "little_kid", protected = true)

    /** [state] with you and both kids, and [on] the profile that's on. */
    fun withKids(state: HomeUiState, on: String? = null): HomeUiState {
        val everyone = listOf(ActiveProfile.owner(state.name)) + with(RoomProfileStore) { kids.map { it.active(AgeBrackets.DEFAULT, state.now.toLocalDate()) } }
        return state.copy(profile = everyone.firstOrNull { it.id == on } ?: everyone.first(), profiles = everyone)
    }

    /** "Who's watching?" on a weekend morning: you, Maya and Leo. */
    fun everyone(today: LocalDate = SampleHomeState.saturdayMorning.toLocalDate()): List<ActiveProfile> =
        listOf(ActiveProfile.owner("Alex")) + with(RoomProfileStore) { kids.map { it.active(AgeBrackets.DEFAULT, today) } }
}

/** Settings › Profiles' controller in memory: it records what was saved, linked and removed. */
internal class FakeProfiles(kids: List<KidEntity> = emptyList()) : ProfilesController {
    override val kids = MutableStateFlow(kids)
    override val brackets = MutableStateFlow(AgeBrackets.DEFAULT)

    val saved = mutableListOf<KidEntity>()
    val removed = mutableListOf<KidEntity>()
    val unlinked = mutableListOf<KidEntity>()
    val bracketSaves = mutableListOf<List<AgeBracket>>()
    val links = mutableListOf<Triple<String, Long, String?>>()
    var users = LinkableUsers(listOf(SampleProfiles.mayaUser, SampleProfiles.leoUser))
    var linkResult: ((KidEntity, PlexHomeUser) -> PlexLinkResult)? = null

    override fun saveKid(kid: KidEntity) {
        saved += kid
        kids.update { list -> if (list.any { it.id == kid.id }) list.map { if (it.id == kid.id) kid else it } else list + kid }
    }

    override fun removeKid(kid: KidEntity) {
        removed += kid
        kids.update { list -> list.filter { it.id != kid.id } }
    }

    override fun saveBrackets(brackets: List<AgeBracket>) {
        bracketSaves += brackets
        this.brackets.value = brackets
    }

    override suspend fun linkableUsers() = users

    override suspend fun link(kid: KidEntity, user: PlexHomeUser, pin: String?): PlexLinkResult {
        links += Triple(kid.id, user.id, pin)
        return linkResult?.invoke(kid, user) ?: PlexLinkResult.Linked(kid.copy(plexUserId = user.id, plexUserTitle = user.title))
    }

    override fun unlink(kid: KidEntity) {
        unlinked += kid
    }
}
