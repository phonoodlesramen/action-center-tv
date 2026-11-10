package com.phonoodlesramen.actioncenter.player

import com.phonoodlesramen.actioncenter.plex.PlexStream
import java.util.Locale
import java.util.MissingResourceException

/** The two kinds of track the player's options change (G13). */
enum class TrackKind { AUDIO, SUBTITLES }

/**
 * One audio or subtitle track of the playing file as Media3 lists it, in the file's order,
 * without Media3's types, so the matching is unit-tested.
 */
data class FileTrack(
    /** Its group in Media3's `Tracks`, and its place in that group. */
    val group: Int,
    val track: Int,
    /** Media3's language tag ("en", "de-DE"), or null when the file doesn't say. */
    val language: String?,
    /** Media3's `Format.id`: a subtitle file attached from Plex carries [TrackMatch.sidecarId]. */
    val id: String?,
    val selected: Boolean,
    /** The Shield can play it (passed through or decoded): only these are offered or chosen. */
    val supported: Boolean = true,
)

/** One line of the options' Audio or Subtitles list: a Plex stream, matched to the file. */
data class TrackOption(val streamId: String, val label: String, val selected: Boolean)

/**
 * What the options list for the program playing (G13). A kind is empty when its streams
 * aren't known or don't clearly match the file: Media3 chooses on its own, and the options
 * leave it out.
 */
data class TrackMenu(val audio: List<TrackOption> = emptyList(), val subtitles: List<TrackOption> = emptyList()) {
    /** No subtitle track is on: the list's Off is ticked. */
    val subtitlesOff: Boolean get() = subtitles.none { it.selected }

    fun of(kind: TrackKind): List<TrackOption> = if (kind == TrackKind.AUDIO) audio else subtitles
}

/** Plex's streams of one kind, each with the file's track it is. */
data class MatchedTracks(val pairs: List<Pair<PlexStream, FileTrack>>) {
    /**
     * Plex's choice for the file (G13): the track of the stream it marks selected, or null to
     * leave it to Media3 (one the Shield can't play is left to Media3 too).
     */
    val plexChoice: FileTrack? get() = pairs.firstOrNull { it.first.selected }?.second?.takeIf { it.supported }

    fun track(streamId: String): FileTrack? = pairs.firstOrNull { it.first.id == streamId }?.second

    /** The options' lines, named by Plex, ticked where Media3 has the track on; tracks the Shield can't play are left out. */
    fun options(): List<TrackOption> {
        val labels = TrackMatch.labels(pairs.map { it.first })
        return pairs.filter { it.second.supported }.map { (stream, track) -> TrackOption(stream.id, labels.getValue(stream.id), track.selected) }
    }
}

/**
 * Matches Plex's streams to Media3's tracks (G13): by kind, then in the file's order (Plex's
 * `index`), checking that the languages agree wherever both say. When the counts differ
 * or a language disagrees, the match isn't clear, so it's null: Media3 keeps its own
 * choice, rather than turning on the wrong track.
 */
object TrackMatch {
    /** The id an attached Plex subtitle file gets in Media3, so its track is found again. */
    fun sidecarId(stream: PlexStream): String = "plex-${stream.id}"

    fun audio(streams: List<PlexStream>, tracks: List<FileTrack>): MatchedTracks? =
        inOrder(streams.filter { it.type == PlexStream.AUDIO }, tracks)?.let(::MatchedTracks)

    /**
     * The file's own subtitles in order, as audio; subtitle files Plex keeps next to it are
     * found by [sidecarId] (only those Media3 can read were attached). Closed captions inside
     * the video aren't a track of their own, so they're left out.
     */
    fun subtitles(streams: List<PlexStream>, tracks: List<FileTrack>): MatchedTracks? {
        val subtitles = streams.filter { it.type == PlexStream.SUBTITLES && !it.inVideo }
        val (sidecars, embedded) = subtitles.partition { it.key != null }
        val (attached, inFile) = tracks.partition { it.id?.startsWith(SIDECAR_PREFIX) == true }
        val matched = inOrder(embedded, inFile) ?: return null
        val files = sidecars.mapNotNull { stream -> attached.firstOrNull { it.id == sidecarId(stream) }?.let { stream to it } }
        return MatchedTracks(matched + files)
    }

    /** The same language, or one of them doesn't say ("und" counts as not saying). */
    fun sameLanguage(a: String?, b: String?): Boolean {
        val first = language(a) ?: return true
        val second = language(b) ?: return true
        return first == second
    }

    /**
     * A language as ISO 639-2/T, so Plex's "ger" and Media3's "de" (or "de-DE") agree:
     * two-letter codes through [Locale], bibliographic codes to their terminology twins.
     */
    fun language(code: String?): String? {
        val primary = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
            ?.takeIf { it.isNotEmpty() && it !in UNKNOWN } ?: return null
        val three = if (primary.length == 2) {
            try {
                Locale.forLanguageTag(primary).isO3Language.ifEmpty { primary }
            } catch (_: MissingResourceException) {
                primary
            }
        } else {
            primary
        }
        return BIBLIOGRAPHIC[three] ?: three
    }

    /**
     * Each stream's name in the options: Plex's `displayTitle`, or its `extendedDisplayTitle`
     * where two would read the same (a commentary beside the main English track), else the
     * language, else "Track 2".
     */
    fun labels(streams: List<PlexStream>): Map<String, String> {
        val plain = streams.mapIndexed { i, s -> s.id to (s.displayTitle ?: s.language ?: "Track ${i + 1}") }
        val counts = plain.groupingBy { it.second }.eachCount()
        return streams.zip(plain).associate { (stream, named) ->
            val label = named.second
            stream.id to if ((counts[label] ?: 0) > 1) stream.extendedDisplayTitle?.takeIf { it.isNotBlank() } ?: label else label
        }
    }

    /**
     * One line for the log about a file's match: counts, languages and Plex's pick, never a
     * title (a stream's own title can name the film).
     */
    fun describe(kind: TrackKind, streams: List<PlexStream>?, tracks: List<FileTrack>, match: MatchedTracks?): String {
        val name = if (kind == TrackKind.AUDIO) "audio" else "subtitles"
        val type = if (kind == TrackKind.AUDIO) PlexStream.AUDIO else PlexStream.SUBTITLES
        if (streams == null) return "$name: ${tracks.size} in the file, Plex's streams unknown: Media3 chooses"
        val ofKind = streams.filter { it.type == type && !it.inVideo }
        val languages = ofKind.joinToString(" ") { language(it.languageCode) ?: "und" }
        return when (match) {
            null -> "$name: Plex ${ofKind.size} ($languages), file ${tracks.size}: no clear match, Media3 chooses"
            else -> {
                val pick = match.plexChoice?.let { choice -> ", Plex picks ${match.pairs.indexOfFirst { it.second == choice } + 1}" }.orEmpty()
                "$name: Plex ${ofKind.size} ($languages), file ${tracks.size}: matched ${match.pairs.size}$pick"
            }
        }
    }

    /** [streams] (sorted by their place in the file) to [tracks] one for one, or null when that isn't clear. */
    private fun inOrder(streams: List<PlexStream>, tracks: List<FileTrack>): List<Pair<PlexStream, FileTrack>>? {
        if (streams.size != tracks.size) return null
        val pairs = streams.sortedBy { it.index ?: Int.MAX_VALUE }.zip(tracks)
        return pairs.takeIf { it.all { (stream, track) -> sameLanguage(stream.languageCode, track.language) } }
    }

    private const val SIDECAR_PREFIX = "plex-"

    /** Undetermined, unknown, several or no language: says nothing. */
    private val UNKNOWN = setOf("und", "unk", "mul", "zxx", "mis")

    /** ISO 639-2's bibliographic codes (Plex sends these) and their terminology codes ([Locale]'s). */
    private val BIBLIOGRAPHIC = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
        "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
        "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
        "tib" to "bod", "wel" to "cym",
    )
}
