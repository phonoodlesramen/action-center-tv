package com.phonoodlesramen.actioncenter.guide.station

import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The guide station's API (Phase 8, `docs/design/guide-station-api.md`): the contract between
 * the station (`:station`) and the TVs (`:app`). Both compile against these types.
 */
object StationApi {
    /**
     * The API's version, sent as [VERSION_HEADER]. A TV refuses a station whose major it doesn't know.
     * 1.1.0 (Phase 9): rules by studio and label, and their choices; every new field has a default.
     * 1.2.0 (Phase 13): each profile's favorite channels, and the NFL (`/v1/nfl…`). A 1.1 station
     * answers 404 to both, and a TV then keeps favorites on itself and says the station needs an update.
     * 1.3.0: `NflSettings.show`, the house's Show the NFL; while it's off the station doesn't poll ESPN.
     * 1.4.0: a channel's icon picked on the phone: `StationChannel.icon`, `ChannelDraft.icon` and
     * `RuleChoices.icons`. A 1.3 station offers no icons, so the phone hides its Icon row.
     */
    const val VERSION = "1.4.0"
    const val MAJOR = 1
    const val VERSION_HEADER = "X-Station-Version"
    const val PREFIX = "/v1"
    const val DEFAULT_PORT = 8443

    /** How far ahead the station keeps every channel scheduled, and the TVs keep a copy (N5). */
    const val HORIZON_MILLIS = 48 * 60 * 60_000L

    /** How far back the guide reaches, so the program on now is always in it. */
    const val BEHIND_MILLIS = 60 * 60_000L

    /** How long a `changes` call is held open. */
    const val CHANGES_WAIT_MILLIS = 60_000L

    /** How long a pairing request waits for approval. */
    const val PAIRING_MILLIS = 5 * 60_000L

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * The pairing code both ends show: the first 6 Crockford base32 characters of
     * SHA-256(the station CA's DER ‖ the TV's public key, SubjectPublicKeyInfo DER). A machine in
     * the middle would give the two ends different codes.
     */
    fun pairingCode(stationCaDer: ByteArray, tvPublicKeySpki: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(stationCaDer)
            update(tvPublicKeySpki)
        }.digest()
        var bits = 0L
        for (i in 0 until 4) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
        // 32 bits read, 30 used: six 5-bit characters from the top.
        return (0 until 6).map { CROCKFORD[((bits ushr (27 - 5 * it)) and 0x1F).toInt()] }.joinToString("")
    }
}

/** `{"error": "…"}`: a plain sentence a TV shows as it is. */
@Serializable
data class ApiError(val error: String)

// Pairing (open).

@Serializable
data class PairRequest(
    /** The room the TV is in ("Living room"), shown on the Back-to card. */
    val name: String,
    val model: String,
    /** The TV's public key, SubjectPublicKeyInfo DER, base64. */
    val publicKey: String,
)

@Serializable
data class PairStarted(val requestId: String, val expiresAtMillis: Long)

@Serializable
enum class PairState {
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("refused") REFUSED,
    @SerialName("expired") EXPIRED,
}

/** Once [PairState.APPROVED]: the TV's certificate and the station's CA, PEM. */
@Serializable
data class PairStatus(val state: PairState, val certificate: String? = null, val caCertificate: String? = null)

// The guide.

/** `GET /v1/guide`: the lineup and every channel's slots, [StationApi.BEHIND_MILLIS] back to [StationApi.HORIZON_MILLIS] ahead. */
@Serializable
data class StationGuide(
    val version: Long,
    val updatedAtMillis: Long,
    /** The house's zone (the station's `TZ`), which sets the schedule's day boundaries. */
    val timeZone: String,
    val channels: List<StationChannel>,
)

@Serializable
data class StationChannel(
    val id: String,
    val number: Int,
    val name: String,
    val logo: String? = null,
    /** The glyph picked on the phone, null for Automatic (1.4.0). Each TV draws it, or its automatic one. */
    val icon: String? = null,
    val enabled: Boolean,
    val alignmentMinutes: Int,
    /** [com.phonoodlesramen.actioncenter.guide.ChannelEntity.FILLER_TRAILERS] or `FILLER_UP_NEXT`, as guide.db stores it. */
    val filler: String,
    val rule: ChannelRule,
    /** What the channel can play, for each TV's kid filter (R2). */
    val contentRatings: List<String> = emptyList(),
    val unrated: Boolean = false,
    val slots: List<StationSlot>,
)

/** A [GuideSlot] on the wire: Plex keys and art paths, never a token or a host. */
@Serializable
data class StationSlot(
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val episode: String? = null,
    val itemKey: String? = null,
    val showKey: String? = null,
    val art: String? = null,
    val contentRating: String? = null,
    val filler: Boolean = false,
    val offAir: String? = null,
    val summary: String? = null,
) {
    fun toGuideSlot() = GuideSlot(
        startMillis = startMillis, endMillis = endMillis, title = title, episode = episode, itemKey = itemKey, showKey = showKey,
        art = art, contentRating = contentRating, filler = filler, offAir = offAir, summary = summary,
    )

    companion object {
        fun of(slot: GuideSlot) = StationSlot(
            startMillis = slot.startMillis, endMillis = slot.endMillis, title = slot.title, episode = slot.episode, itemKey = slot.itemKey,
            showKey = slot.showKey, art = slot.art, contentRating = slot.contentRating, filler = slot.filler, offAir = slot.offAir,
            summary = slot.summary,
        )
    }
}

/** `GET /v1/changes`, and every edit's answer: the guide's (or the spot's) new version. */
@Serializable
data class Changed(val version: Long)

// Editing.

@Serializable
data class Created(val id: String, val version: Long)

/** `PATCH /v1/channels/{id}`: rename, renumber, on/off, move. Absent fields stay. */
@Serializable
data class ChannelPatch(val name: String? = null, val number: Int? = null, val enabled: Boolean? = null, val index: Int? = null)

@Serializable
data class Preview(val slots: List<StationSlot>)

// The house's saved spot (N6).

/** `PUT /v1/spot`: who saved it comes from the caller's certificate. */
@Serializable
data class SpotBody(val channelId: String, val slotStartMillis: Long, val itemKey: String, val positionMs: Long, val savedAtMillis: Long)

@Serializable
data class StationSpot(
    val channelId: String,
    val slotStartMillis: Long,
    val itemKey: String,
    val positionMs: Long,
    val savedAtMillis: Long,
    /** The TV that saved it: its device id. */
    val device: String,
    /** Its room, for the Back-to card ("Paused in the living room"). */
    val room: String,
)

@Serializable
data class SpotAnswer(val spot: StationSpot? = null)

@Serializable
data class SpotKey(val channelId: String, val slotStartMillis: Long, val itemKey: String)

/** `DELETE /v1/spot`: with [only], forgotten only while it's still that one. */
@Serializable
data class SpotForget(val only: SpotKey? = null)

/** `POST /v1/spot/played`: another channel played on the calling TV. */
@Serializable
data class ChannelPlayed(val channelId: String)

// Favorite channels (Phase 13, C1).

/**
 * Who a profile is to the station (proposal A): every TV agrees on these without the station
 * knowing any TV's profiles. A kid without a Plex Home user has no key, and keeps favorites on
 * their TV only.
 */
object ProfileKeys {
    /** Whoever signed the TVs in to Plex: the adult profile. */
    const val OWNER = "owner"

    /** A kid linked to a Plex Home user (K2). */
    fun plexUser(plexUserId: Long) = "plex:$plexUserId"

    fun isValid(key: String) = key == OWNER || key.removePrefix("plex:").let { it != key && it.isNotEmpty() && it.all(Char::isDigit) }
}

/** `GET`, `PUT /v1/favorites/{profileKey}`: channel ids, in the profile's order. A change goes out through `changes`. */
@Serializable
data class Favorites(val channels: List<String> = emptyList(), val version: Long = 0)

// Devices and the station.

@Serializable
data class StationDevice(
    val id: String,
    val name: String,
    val model: String,
    val pairedAtMillis: Long? = null,
    val lastSeenMillis: Long? = null,
    val pending: Boolean = false,
    /** A pending TV's pairing code, to compare with the one on its screen. */
    val code: String? = null,
)

@Serializable
data class StationInfo(val version: String, val plex: StationPlex, val library: StationLibrary, val timeZone: String)

@Serializable
data class StationPlex(val linked: Boolean, val serverName: String? = null)

@Serializable
data class StationLibrary(val lastSyncMillis: Long? = null, val error: String? = null)

@Serializable
data class PlexLinkStarted(val code: String, val expiresAtMillis: Long)

@Serializable
enum class PlexLinkState {
    @SerialName("waiting") WAITING,
    @SerialName("linked") LINKED,
    @SerialName("expired") EXPIRED,
}

@Serializable
data class PlexLinkStatus(val state: PlexLinkState, val serverName: String? = null)
