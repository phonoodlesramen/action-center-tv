package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.nfl.NflGameDetail
import com.phonoodlesramen.actioncenter.nfl.NflSettings
import com.phonoodlesramen.actioncenter.nfl.NflState
import com.phonoodlesramen.actioncenter.nfl.NflTeamSchedule
import com.phonoodlesramen.actioncenter.nfl.NflWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** The guide station's answer to one of the NFL's calls (Phase 13, station API 1.2.0). */
sealed interface NflReply<out T> {
    data class Answered<T>(val value: T) : NflReply<T>

    /** `404` to `GET /v1/nfl`, `nfl/changes` or the settings: a 1.1 station, which has no NFL yet. */
    data object Outdated : NflReply<Nothing>

    /**
     * It couldn't be asked, or it said no: the [sentence] to show ("Can't reach the guide
     * station.", "ESPN has no games that week."). [reached] is false when it couldn't be asked.
     */
    data class Failed(val sentence: String, val reached: Boolean = true) : NflReply<Nothing>
}

/** The guide station's side of the NFL, for [com.phonoodlesramen.actioncenter.nfl.NflSource]. */
interface NflStation {
    /** Whether this TV holds a station's certificate: the NFL is then the station's. */
    val paired: Flow<Boolean>

    /** `GET /v1/nfl`: the state and its ETag, or null when [etag] is still current (`304`). */
    suspend fun nfl(etag: String?): NflReply<Pair<NflState, String?>?>

    /** `GET /v1/nfl/changes?after=`: the new version, or null when the station's wait ran out. */
    suspend fun nflChanges(after: Long): NflReply<Long?>

    suspend fun nflWeek(seasonType: Int, week: Int, seasonYear: Int?): NflReply<NflWeek>

    suspend fun nflSchedule(abbr: String, seasonYear: Int?): NflReply<NflTeamSchedule>

    suspend fun nflGame(id: String): NflReply<NflGameDetail>

    /** A team's logo, `s` (64 px) or `l` (192 px), as PNG bytes. */
    suspend fun nflLogo(abbr: String, size: String): NflReply<ByteArray>

    /** The house's settings (B): the NFL's new version. */
    suspend fun saveNflSettings(settings: NflSettings): NflReply<Long>

    /** Never paired: the TV asks ESPN itself. */
    object None : NflStation {
        private val no = NflReply.Failed(StationException.UNPAIRED, reached = false)
        override val paired: Flow<Boolean> = flowOf(false)
        override suspend fun nfl(etag: String?) = no
        override suspend fun nflChanges(after: Long) = no
        override suspend fun nflWeek(seasonType: Int, week: Int, seasonYear: Int?) = no
        override suspend fun nflSchedule(abbr: String, seasonYear: Int?) = no
        override suspend fun nflGame(id: String) = no
        override suspend fun nflLogo(abbr: String, size: String) = no
        override suspend fun saveNflSettings(settings: NflSettings) = no
    }
}
