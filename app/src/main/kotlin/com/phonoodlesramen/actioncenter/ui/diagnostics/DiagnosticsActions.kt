package com.phonoodlesramen.actioncenter.ui.diagnostics

import com.phonoodlesramen.actioncenter.apps.LaunchableApp

/** Everything the diagnostics (probe and safe-mode) screen can ask for. Defaults do nothing (previews, screenshots). */
interface DiagnosticsActions {
    fun openSettings() {}
    fun launchApp(app: LaunchableApp) {}
    fun playTestTrailer() {}
    fun openPlezy() {}
    fun openPlex() {}

    /** Phase 2.6: plays a few titles from inside the launcher, as a guide channel would. */
    fun openPlaybackCheck() {}

    /** Phase 15: what this TV gets from each piece Search leans on. */
    fun openSearchCheck() {}
    fun leaveSafeMode() {}

    /** The hidden debug action: crash on purpose to check that safe mode appears. */
    fun forceCrash() {}

    companion object None : DiagnosticsActions
}
