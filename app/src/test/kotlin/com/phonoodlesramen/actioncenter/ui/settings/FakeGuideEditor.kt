package com.phonoodlesramen.actioncenter.ui.settings

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.ChannelGlyphs
import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.EditRejected
import com.phonoodlesramen.actioncenter.guide.EditableChannel
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.RuleChoices
import com.phonoodlesramen.actioncenter.ui.guide.SampleGuide
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A [GuideEditor] for Settings › Plex Guide's tests and screenshots: a made-up lineup it
 * changes in memory, the calls it was asked to make, and a sentence to turn the next one
 * down with. Every title is invented.
 */
internal class FakeGuideEditor(channels: List<EditableChannel> = sample()) : GuideEditor {
    val channels = MutableStateFlow(channels)
    override val lineup = this.channels

    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The next change is turned down with this. */
    @Volatile
    var reject: String? = null

    private fun call(text: String) {
        reject?.let {
            reject = null
            throw EditRejected(it)
        }
        calls += text
    }

    override suspend fun choices() = RuleChoices(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    override suspend fun rename(id: String, name: String) = call("rename $id $name")

    override suspend fun renumber(id: String, number: Int) = call("renumber $id $number")

    override suspend fun move(id: String, index: Int) {
        call("move $id $index")
        channels.update { list ->
            val moved = list.first { it.id == id }
            (list - moved).toMutableList().apply { add(index.coerceIn(0, size), moved) }.mapIndexed { i, it -> it.copy(number = i + 1) }
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        call("setEnabled $id $enabled")
        channels.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    override suspend fun create(draft: ChannelDraft): String = "new".also { call("create ${draft.name}") }

    /** Records the ratings it saves ("update comedy [G, PG]") and keeps the draft. */
    override suspend fun update(id: String, draft: ChannelDraft) {
        call("update $id ${draft.rule.contentRatings}")
        channels.update { list -> list.map { if (it.id == id) it.copy(draft = draft) else it } }
    }

    override suspend fun duplicate(id: String): String = "copy".also { call("duplicate $id") }

    override suspend fun delete(id: String) = call("delete $id")

    override suspend fun preview(draft: ChannelDraft): List<GuideSlot> = emptyList()

    override suspend fun remakeStarterLineup() = call("remake")

    companion object {
        private const val MINUTE = 60_000L

        /**
         * 18 channels on the sample weeknight (8:41 PM UTC), two of them off, every one that's
         * on scheduled until 9:30 PM tomorrow or later. Comedy's rule has the 10-12 bracket's
         * ratings, and Drama's PG-13 and R, set on the phone ([RATINGS]); the rest name none.
         */
        fun sample(now: Long = SampleGuide.WEEKNIGHT): List<EditableChannel> = listOf(
            "Movies" to "Big Atlas",
            "Action" to "Cold Star Relay",
            "Comedy" to "Glowing Reef",
            "Drama" to "Quiet Field",
            "Sci-Fi" to "Orbit of Glass",
            "Thriller" to "The Long Signal",
            "Drama TV" to "Night Shift",
            "Crime TV" to "Harbor Watch",
            "Comedy TV" to "Up next: Open House",
            "Documentary TV" to "Sky Atlas",
            "TV Mix" to "Kitchen Table",
            "Classics" to "A Train at Midnight",
            "Ridge Line Marathon" to "Ridge Line",
            "Harbor Watch Marathon" to "Harbor Watch",
            "Desk Duty Marathon" to "Desk Duty",
            "Late Night Films" to "Paper Harbor",
            "Short Films" to "Up next: The Silver Coast",
            "Westerns" to null,
        ).mapIndexed { i, (name, onNow) ->
            EditableChannel(
                id = name.lowercase().replace(' ', '-'),
                number = i + 1,
                enabled = name != "Action" && name != "Desk Duty Marathon",
                draft = ChannelDraft(name, ChannelRule(contentRatings = RATINGS[name].orEmpty())),
                scheduledUntil = if (onNow == null) null else now + 24 * 60 * MINUTE + 49 * MINUTE + (i % 3) * 30 * MINUTE,
                onNow = onNow,
                // As RoomGuideEditor picks them, from the name: the marathons have no poster here, so monograms.
                glyph = ChannelGlyphs.pick(name, null),
            )
        }

        /** The sample rules' content ratings: Comedy's are the 10-12 bracket's (K3), Drama's no bracket's. */
        val RATINGS = mapOf(
            "Comedy" to listOf("G", "PG", "PG-13", "TV-Y", "TV-Y7", "TV-G", "TV-PG"),
            "Drama" to listOf("PG-13", "R"),
        )
    }
}
