package com.phonoodlesramen.actioncenter.setup

import androidx.compose.runtime.compositionLocalOf

/**
 * Where setup stands, for the top bar's chip. Provided by the home activity from
 * [SetupMonitor.status]; previews and screenshots get a TV that's all set up, so no chip.
 */
val LocalSetup = compositionLocalOf { SetupStatus() }
