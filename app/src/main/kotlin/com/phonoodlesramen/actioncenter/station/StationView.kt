package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.station.Favorites
import com.phonoodlesramen.actioncenter.guide.station.PairState
import com.phonoodlesramen.actioncenter.guide.station.StationDevice
import com.phonoodlesramen.actioncenter.guide.station.StationInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** Whether this TV can talk to its station right now. */
enum class StationReach {
    CONNECTED,

    /** The last call couldn't reach it: the TV shows its copy, and editing waits. */
    UNREACHABLE,

    /** The station revoked this TV (another TV unpaired it). */
    UNPAIRED,
}

/** Settings › Plex Guide › Guide station, as the screen shows it. */
data class StationView(
    /** "192.168.1.20" as typed (with ":port" off 8443); empty before it's typed. */
    val address: String = "",
    /** This TV's room, as it pairs ("Living room"); empty before it's picked. */
    val room: String = "",
    /** Whether this TV's guide is the station's: it holds a certificate from it. */
    val paired: Boolean = false,
    val reach: StationReach = StationReach.CONNECTED,
    /** When this TV last heard its copy of the guide is current. */
    val checkedAtMillis: Long? = null,
    /** Asking the station for a pairing code. */
    val starting: Boolean = false,
    /** Why the last thing asked of the station didn't work, as a sentence. */
    val problem: String? = null,
    val phase: StationPhase = StationPhase.Unpaired,
    /** The station answered favorites' calls 404 (1.1, Phase 13): it needs an update, and favorites stay on this TV meanwhile. */
    val favoritesNeedUpdate: Boolean = false,
) {
    /** Why Settings › Plex Guide and the phone editor are greyed out (no offline queue); null while editing works. */
    val editingLocked: String?
        get() = when {
            !paired -> null
            reach == StationReach.UNREACHABLE -> CANT_REACH
            reach == StationReach.UNPAIRED -> StationException.UNPAIRED
            else -> null
        }

    companion object {
        const val CANT_REACH = "Can't reach the guide station"
    }
}

sealed interface StationPhase {
    /** Not paired: this TV makes its own guide. */
    data object Unpaired : StationPhase

    /** A code is showing: waiting for approval, or the answer (refused, expired). */
    data class Pairing(val code: String, val expiresAtMillis: Long, val state: PairState = PairState.PENDING) : StationPhase

    /** Paired: the station's Plex and library ([station]), the house's TVs ([devices]), and the station's plex.tv/link code while it signs in. */
    data class Paired(
        val station: StationInfo? = null,
        val devices: List<StationDevice>? = null,
        /** This TV's id among [devices]. */
        val thisDevice: String? = null,
        val plexCode: String? = null,
        val leaving: Boolean = false,
    ) : StationPhase
}

/** The station's answer about a profile's favorite channels (Phase 13, C1). */
sealed interface StationFavorites {
    data class Kept(val favorites: Favorites) : StationFavorites

    /** `404`: a 1.1 station, which doesn't keep favorites yet. */
    data object NotKept : StationFavorites

    /** It couldn't be asked, or said no: the sentence to show ("Can't reach the guide station."). */
    data class Refused(val sentence: String) : StationFavorites
}

/** The station's side of favorites, for [com.phonoodlesramen.actioncenter.favorites.ChannelFavorites]. */
interface FavoritesStation {
    /** Whether this TV holds a station's certificate: favorites with a profile key are then the station's. */
    val paired: Flow<Boolean>

    /** The station's `changes` version as this TV last heard it: a favorites change moves it too. */
    val changes: Flow<Long>

    suspend fun favorites(key: String): StationFavorites

    suspend fun saveFavorites(key: String, channels: List<String>): StationFavorites

    /** Never paired: favorites are each TV's own. */
    object None : FavoritesStation {
        override val paired: Flow<Boolean> = flowOf(false)
        override val changes: Flow<Long> = flowOf(0L)
        override suspend fun favorites(key: String): StationFavorites = StationFavorites.Refused(StationException.UNPAIRED)
        override suspend fun saveFavorites(key: String, channels: List<String>): StationFavorites = StationFavorites.Refused(StationException.UNPAIRED)
    }
}

/** What Settings › Plex Guide › Guide station does. The defaults do nothing (previews, screenshots). */
interface GuideStationController {
    val view: StateFlow<StationView>

    /** Pairs this TV with the station at [address] ("192.168.1.20" or "…:9443"), named [room]. */
    fun pair(address: String, room: String) {}

    /** Stops waiting for approval, or puts away a refused or expired code. */
    fun cancelPairing() {}

    /** Approves a pending TV from this one (later TVs). */
    fun approve(device: StationDevice) {}

    /** Unpairs another TV; this TV's own row leaves the station. */
    fun unpair(device: StationDevice) {}

    /** Unpairs this TV and goes back to its own guide, made from its copy of the lineup. */
    fun leave() {}

    /** Starts the station's own plex.tv sign-in: its link code shows on the page. */
    fun linkPlex() {}

    /** While the page is open: the station, its devices, and its Plex sign-in, kept current. */
    suspend fun follow() {}

    /** Shows [view] and does nothing: screenshots and previews. */
    class Fixed(view: StationView) : GuideStationController {
        override val view: StateFlow<StationView> = MutableStateFlow(view)
    }
}
