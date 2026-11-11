package com.phonoodlesramen.actioncenter.update

import androidx.compose.runtime.compositionLocalOf

/**
 * Where updates stand, for the top bar's chip ([UpdateChip]). Provided by the home activity from
 * [UpdatesController.status]; previews and screenshots get a build that doesn't update, so no chip.
 */
val LocalUpdates = compositionLocalOf { UpdateStatus(enabled = false) }
