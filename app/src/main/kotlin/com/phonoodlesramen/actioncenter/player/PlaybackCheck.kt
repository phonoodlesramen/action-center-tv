package com.phonoodlesramen.actioncenter.player

import android.util.Log
import com.phonoodlesramen.actioncenter.plex.PlexMedia
import com.phonoodlesramen.actioncenter.plex.PlexServerApi
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexDao
import com.phonoodlesramen.actioncenter.safety.summary
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** A title the Playback check plays, and what it tests ([labels], e.g. "Largest MKV", "TrueHD"). */
data class CheckCase(
    val labels: List<String>,
    val ratingKey: String,
    val title: String,
    /** The file, e.g. "MKV · HEVC main 10 · 4k · TrueHD 8 ch · 48.4 GB". */
    val detail: String,
    val durationMs: Long?,
)

/** What a title did: how soon the first frame came, which decoders and audio path, or what failed. */
data class CheckResult(
    val case: CheckCase,
    val firstFrameMillis: Long? = null,
    val video: String? = null,
    val audio: String? = null,
    val error: String? = null,
) {
    val passed: Boolean get() = error == null && firstFrameMillis != null
}

data class PlaybackCheckState(
    val phase: Phase = Phase.READY,
    val cases: List<CheckCase> = emptyList(),
    /** The case playing now, an index into [cases]. */
    val playing: Int? = null,
    val results: List<CheckResult> = emptyList(),
    /** What Plex's history said afterwards (G6): nothing should be recorded. */
    val history: String? = null,
    /** How many titles' main audio is DTS, which the operator's TV doesn't take (G15). */
    val dtsTitles: Int? = null,
    val problem: String? = null,
) {
    enum class Phase { READY, PICKING, PLAYING, CHECKING_HISTORY, DONE }
}

/** Plays one title from [startMs] for a few seconds: ExoPlayer on the Shield, a fake in tests. */
fun interface ProbePlayer {
    suspend fun play(source: PlaybackSource, startMs: Long): ProbeOutcome
}

data class ProbeOutcome(val firstFrameMillis: Long?, val video: String?, val audio: String?, val error: String?)

/**
 * Diagnostics › Playback check (Phase 2.6 step 2): what the Shield plays smoothly from
 * inside the launcher before any channel is built. It picks your largest MKV and titles
 * with HEVC 10-bit, HDR10, Dolby Vision, DD+, TrueHD and DTS from the cache, plays each from
 * its middle (the guide joins programs midway), then reads Plex's history to show that
 * nothing was recorded (G6). Results go to the screen and to logcat (`ActionCenterGuide`).
 */
class PlaybackCheck(
    private val dao: PlexDao,
    private val access: Flow<ServerAccess?>,
    private val server: PlexServerApi,
    private val playables: PlexPlayables,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) {
    private val mutableState = MutableStateFlow(PlaybackCheckState())
    val state: StateFlow<PlaybackCheckState> = mutableState.asStateFlow()

    /** Picks the titles to play: one per test, merged when one title covers several. */
    suspend fun pick(): List<CheckCase> {
        val picks = linkedMapOf<String, Pair<ItemEntity, MutableList<String>>>()
        fun add(label: String, item: ItemEntity?) {
            if (item == null) return
            picks.getOrPut(item.ratingKey) { item to mutableListOf() }.second += label
        }
        add("Largest MKV", dao.playableLike(container = "mkv").firstOrNull())
        val hevc10 = dao.playableLike(videoCodec = "hevc", videoProfile = "10", limit = DETAIL_LOOKUPS)
        add("HEVC 10-bit", hevc10.firstOrNull())
        // Only a title's details say HDR10 or Dolby Vision: ask about the biggest few.
        val linked = access.first()
        if (linked != null) {
            val ranges = hevc10.associateWith { item ->
                try {
                    server.metadata(linked, item.ratingKey)?.media?.dynamicRange
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            add("Dolby Vision", ranges.entries.firstOrNull { it.value == PlexMedia.DOLBY_VISION }?.key)
            add("HDR10", ranges.entries.firstOrNull { it.value == PlexMedia.HDR10 }?.key)
        }
        add("DD+", dao.playableLike(audioCodec = "eac3").firstOrNull())
        add("TrueHD", dao.playableLike(audioCodec = "truehd").firstOrNull())
        add("DTS", DTS_CODECS.firstNotNullOfOrNull { dao.playableLike(audioCodec = it).firstOrNull() })
        return picks.values.map { (item, labels) -> CheckCase(labels, item.ratingKey, titleOf(item), detailOf(item), item.durationMs) }
    }

    /** Picks, plays each title in turn, then checks Plex's history. Safe to call again. */
    suspend fun run(player: ProbePlayer) {
        val started = clock()
        mutableState.value = PlaybackCheckState(phase = PlaybackCheckState.Phase.PICKING)
        val cases = pick()
        if (cases.isEmpty()) {
            mutableState.value = PlaybackCheckState(
                phase = PlaybackCheckState.Phase.DONE,
                problem = "Nothing to play yet: the library has no files cached. Sync Plex, then try again.",
            )
            log("playback check: nothing to play")
            return
        }
        cases.forEach { log("playback check: case ${it.labels.joinToString(" + ")}: \"${it.title}\" ${it.detail}") }
        val dts = dao.playableWithAudio(DTS_CODECS)
        log("playback check: $dts titles have DTS as their main audio")
        mutableState.value = PlaybackCheckState(phase = PlaybackCheckState.Phase.PLAYING, cases = cases, dtsTitles = dts)
        for ((index, case) in cases.withIndex()) {
            mutableState.update { it.copy(playing = index) }
            val result = playOne(case, player)
            log(
                "playback check: ${case.labels.joinToString(" + ")}: " +
                    if (result.passed) "first frame ${result.firstFrameMillis} ms, video ${result.video}, audio ${result.audio}"
                    else "FAILED ${result.error} (video ${result.video}, audio ${result.audio})",
            )
            mutableState.update { it.copy(results = it.results + result) }
        }
        mutableState.update { it.copy(phase = PlaybackCheckState.Phase.CHECKING_HISTORY, playing = null) }
        val history = historyNote(cases, started)
        log("playback check: history: $history")
        mutableState.update { it.copy(phase = PlaybackCheckState.Phase.DONE, history = history) }
    }

    private suspend fun playOne(case: CheckCase, player: ProbePlayer): CheckResult {
        val source = try {
            playables.source(case.ratingKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CheckResult(case, error = "Couldn't reach Plex: ${e.summary()}")
        } ?: return CheckResult(case, error = "No file to play")
        val outcome = player.play(source, startAt(case.durationMs))
        // A picture with no audio track playing is silent on the TV, not a pass.
        val silent = outcome.error == null && outcome.firstFrameMillis != null && outcome.audio == null
        return CheckResult(
            case, outcome.firstFrameMillis, outcome.video, outcome.audio,
            error = outcome.error ?: if (silent) noSound(outcome.firstFrameMillis) else null,
        )
    }

    /** G6: playing a file without timeline reports should leave no plays in Plex's history. */
    private suspend fun historyNote(cases: List<CheckCase>, startedMillis: Long): String {
        val linked = access.first() ?: return "Plex isn't linked, so its history wasn't checked."
        return try {
            val keys = cases.map { it.ratingKey }.toSet()
            val recorded = server.history(linked, sinceSeconds = startedMillis / 1_000 - 1).filter { it.ratingKey in keys }
            if (recorded.isEmpty()) "Plex recorded no plays for these titles." else "Plex recorded: ${recorded.joinToString { it.title }}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Couldn't read Plex's history: ${e.summary()}"
        }
    }

    companion object {
        const val LOG_TAG = "ActionCenterGuide"
        private const val DETAIL_LOOKUPS = 8

        /** Plex calls DTS "dca" (DTS-HD too, by its profile); "dts" just in case. */
        private val DTS_CODECS = listOf("dca", "dts")

        /** The middle of the title, as a channel joining mid-program would; 10 minutes in when the length is unknown. */
        fun startAt(durationMs: Long?): Long = durationMs?.let { it / 2 } ?: 600_000L

        /** Usually the Shield's Surround sound setting leaves out the title's audio format. */
        fun noSound(firstFrameMillis: Long?): String =
            "No sound: the picture came in $firstFrameMillis ms, but the Shield can't play this audio. " +
                "Check Surround sound in its sound settings."

        fun titleOf(item: ItemEntity): String =
            if (item.type == "episode" && item.showTitle != null) {
                "${item.showTitle} · S${item.season ?: "?"}E${item.episode ?: "?"} · ${item.title}"
            } else {
                listOfNotNull(item.title, item.year?.toString()).joinToString(" · ")
            }

        fun detailOf(item: ItemEntity): String = listOfNotNull(
            item.container?.uppercase(Locale.US),
            listOfNotNull(item.videoCodec?.uppercase(Locale.US), item.videoProfile).joinToString(" ").ifBlank { null },
            item.videoResolution,
            listOfNotNull(item.audioCodec?.let(::audioName), item.audioChannels?.let { "$it ch" }).joinToString(" ").ifBlank { null },
            item.sizeBytes?.let { String.format(Locale.US, "%.1f GB", it / 1_000_000_000.0) },
        ).joinToString(" · ")

        private fun audioName(codec: String) = when (codec.lowercase(Locale.US)) {
            "eac3" -> "DD+"
            "ac3" -> "Dolby Digital"
            "truehd" -> "TrueHD"
            "dca", "dts" -> "DTS"
            else -> codec.uppercase(Locale.US)
        }
    }
}
