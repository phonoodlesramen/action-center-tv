package com.phonoodlesramen.actioncenter.guide

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Everything Settings › Plex Guide on the TV and the phone editor can change (G1, G18).
 * Both use this one interface, so the phone's API maps onto it one to one.
 *
 * - **What's on now finishes (G16):** saving a channel's rule, start times or filler
 *   rewrites only that channel's schedule, from the end of the program on now. Renaming,
 *   renumbering, moving and turning a channel on or off rewrite nothing.
 * - **Never a crash:** a change that can't be made throws [EditRejected] with a sentence
 *   to show; nothing else escapes.
 */
interface GuideEditor {
    /** Every channel, off ones too, in number order. Updates as anything changes it. */
    val lineup: Flow<List<EditableChannel>>

    /** What a rule can pick from, from the library cache. */
    suspend fun choices(): RuleChoices

    suspend fun rename(id: String, name: String)

    /** A number no other channel has, 1 to 9999. */
    suspend fun renumber(id: String, number: Int)

    /** Moves [id] to [index] in the lineup (0 is first) and numbers every channel 1 to n in order. */
    suspend fun move(id: String, index: Int)

    suspend fun setEnabled(id: String, enabled: Boolean)

    /** A new channel, numbered after the last; returns its id. */
    suspend fun create(draft: ChannelDraft): String

    /** Saves [draft] over [id]: its schedule is rewritten from the end of the program on now (G16). */
    suspend fun update(id: String, draft: ChannelDraft)

    /** A copy of [id] numbered after the last, named "<name> 2"; returns its id. */
    suspend fun duplicate(id: String): String

    suspend fun delete(id: String)

    /** The next 24 hours [draft] would play from now, without saving anything. */
    suspend fun preview(draft: ChannelDraft): List<GuideSlot>

    /** Replaces every channel with a new starter lineup (G18, and G19 on a new server). */
    suspend fun remakeStarterLineup()
}

/** A change the editor can't make, with the sentence to show (for example "Channel 12 is taken"). */
class EditRejected(message: String) : Exception(message)

/** What a channel plays and how, as the editor saves it. */
@Serializable
data class ChannelDraft(
    val name: String,
    val rule: ChannelRule,
    /** 30, or 15 for short episodes ([ChannelEntity.alignmentMinutes]). */
    val alignmentMinutes: Int = 30,
    /** [ChannelEntity.FILLER_TRAILERS] or [ChannelEntity.FILLER_UP_NEXT]. */
    val filler: String = ChannelEntity.FILLER_TRAILERS,
)

/** A channel as the editor lists it. */
@Serializable
data class EditableChannel(
    val id: String,
    val number: Int,
    val enabled: Boolean,
    val draft: ChannelDraft,
    /** Where its schedule ends; null before it's first scheduled. */
    val scheduledUntil: Long?,
    /** What's on now, for the list ("Big Atlas"); null in a gap or before scheduling. */
    val onNow: String?,
    /** Its icon, as the guide draws it ([ChannelLook]). */
    val glyph: String? = null,
    val logo: String? = null,
)

/** What a rule's fields can be set to, from the library cache. */
@Serializable
data class RuleChoices(
    val sections: List<Choice>,
    val movieGenres: List<String>,
    val tvGenres: List<String>,
    val shows: List<Choice>,
    /** Collection names; empty until Phase 3's cache keeps them. */
    val collections: List<String>,
    /** Decades with titles, by their first year (1980). */
    val decades: List<Int>,
    val contentRatings: List<String>,
    /**
     * Studio and label names, the most titles first (Phase 9). Empty from a station before
     * API 1.1.0, so the phone editor hides their rows (F3).
     */
    val studios: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
)

/** A Plex key and what to call it. */
@Serializable
data class Choice(val key: String, val title: String)
