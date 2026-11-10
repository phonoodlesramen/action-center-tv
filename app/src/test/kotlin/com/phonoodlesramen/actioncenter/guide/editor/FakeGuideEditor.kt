package com.phonoodlesramen.actioncenter.guide.editor

import com.phonoodlesramen.actioncenter.guide.ChannelDraft
import com.phonoodlesramen.actioncenter.guide.ChannelRule
import com.phonoodlesramen.actioncenter.guide.Choice
import com.phonoodlesramen.actioncenter.guide.EditRejected
import com.phonoodlesramen.actioncenter.guide.EditableChannel
import com.phonoodlesramen.actioncenter.guide.GuideEditor
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.RuleChoices
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [GuideEditor] that records each call as a line ("rename classics Old films") and
 * answers with canned values. Set [reject] or [failure] to make the next change throw.
 */
class FakeGuideEditor : GuideEditor {
    val calls = mutableListOf<String>()

    /** The next change throws [EditRejected] with this sentence. */
    var reject: String? = null

    /** The next change throws this. */
    var failure: Exception? = null

    var previewSlots: List<GuideSlot> = emptyList()

    /** The draft the last create, update or preview was given. */
    var lastDraft: ChannelDraft? = null

    override val lineup = MutableStateFlow(
        listOf(
            EditableChannel("classics", 1, true, ChannelDraft("Classics", ChannelRule(minAgeYears = 25)), 1_000L, "Big Atlas"),
            EditableChannel("tv-mix", 2, false, ChannelDraft("TV Mix", ChannelRule(order = ChannelRule.Order.ROTATE_SHOWS)), null, null),
        ),
    )

    override suspend fun choices(): RuleChoices = RuleChoices(
        sections = listOf(Choice("1", "Movies")),
        movieGenres = listOf("Drama"),
        tvGenres = listOf("Comedy"),
        shows = listOf(Choice("100", "The Office")),
        collections = emptyList(),
        decades = listOf(1980),
        contentRatings = listOf("PG"),
        studios = listOf("Northlight", "Tidewater"),
        labels = listOf("Favourites"),
    )

    override suspend fun rename(id: String, name: String) = change("rename $id $name")

    override suspend fun renumber(id: String, number: Int) = change("renumber $id $number")

    override suspend fun move(id: String, index: Int) = change("move $id $index")

    override suspend fun setEnabled(id: String, enabled: Boolean) = change("setEnabled $id $enabled")

    override suspend fun create(draft: ChannelDraft): String {
        lastDraft = draft
        change("create ${draft.name} ${draft.rule.order} ${draft.alignmentMinutes} ${draft.filler}")
        return "new-1"
    }

    override suspend fun update(id: String, draft: ChannelDraft) {
        lastDraft = draft
        change("update $id ${draft.name} ${draft.rule.genres}")
    }

    override suspend fun duplicate(id: String): String {
        change("duplicate $id")
        return "$id-2"
    }

    override suspend fun delete(id: String) = change("delete $id")

    override suspend fun preview(draft: ChannelDraft): List<GuideSlot> {
        lastDraft = draft
        change("preview ${draft.name}")
        return previewSlots
    }

    override suspend fun remakeStarterLineup() = change("remake")

    private fun change(call: String) {
        reject?.let {
            reject = null
            throw EditRejected(it)
        }
        failure?.let {
            failure = null
            throw it
        }
        calls += call
    }
}
