package com.phonoodlesramen.actioncenter.system

import android.content.pm.PackageManager

class AndroidPackageStateSource(private val pm: PackageManager) : PackageStateSource {
    override fun stateOf(packageName: String): PackageState {
        val setting = try {
            pm.getApplicationEnabledSetting(packageName)
        } catch (_: IllegalArgumentException) {
            return PackageState.MISSING
        }
        return when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> PackageState.ENABLED
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ->
                if (isEnabledInManifest(packageName)) PackageState.ENABLED else PackageState.DISABLED
            // DISABLED, DISABLED_USER (what `pm disable-user` sets), DISABLED_UNTIL_USED.
            else -> PackageState.DISABLED
        }
    }

    private fun isEnabledInManifest(packageName: String): Boolean = try {
        pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
