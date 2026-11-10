package com.phonoodlesramen.actioncenter.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Settings › AI blurbs (M7-M11): the key's state and the last night's run. The blurbs
 * themselves go to `suggestions.db`, and the home shows one in place of a pick's reason
 * while the pick keeps the reason it was written for (M9).
 */
interface AiBlurbsController {
    val status: Flow<AiBlurbsStatus>

    /** Writes tonight's blurbs now, in the background. */
    fun writeNow() {}

    /** Deletes the stored key; the blurbs stop at the next run. */
    fun forgetKey() {}

    /** Previews, screenshots and tests: no key, never run. */
    companion object None : AiBlurbsController {
        override val status: Flow<AiBlurbsStatus> = flowOf(AiBlurbsStatus())
    }
}

/** What Settings › AI blurbs shows. [keyEnding] is the stored key's last 4 characters, never more. */
data class AiBlurbsStatus(
    val keyEnding: String? = null,
    /** "Last night at 4:02 AM: 28 blurbs, about $0.002", or why it failed. */
    val lastRun: String? = null,
    /** [lastRun] says why it failed: Settings shows it in the caution colour. */
    val failed: Boolean = false,
)
