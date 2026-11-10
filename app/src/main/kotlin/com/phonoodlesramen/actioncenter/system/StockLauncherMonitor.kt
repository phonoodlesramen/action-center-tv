package com.phonoodlesramen.actioncenter.system

enum class PackageState { ENABLED, DISABLED, MISSING }

fun interface PackageStateSource {
    fun stateOf(packageName: String): PackageState
}

/**
 * Watches the stock launcher. We become home by disabling it, and an update to it
 * (or a SHIELD OTA) can quietly enable it again, taking the Home button back.
 */
class StockLauncherMonitor(private val source: PackageStateSource) {
    fun state(): PackageState = source.stateOf(STOCK_LAUNCHER)

    /** True when the stock launcher is enabled again and will win the Home button. */
    fun stockLauncherIsBack(): Boolean = state() == PackageState.ENABLED

    companion object {
        const val STOCK_LAUNCHER = "com.google.android.tvlauncher"
    }
}
