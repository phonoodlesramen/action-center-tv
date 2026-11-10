package com.phonoodlesramen.actioncenter.apps

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * Usage access (M5): whether Action Center may read Android's usage stats. An app can't ask
 * for it: Settings › Setup opens TV Settings' Usage access, or `scripts/grant-permissions.ps1`
 * sets the app op to allow over ADB.
 */
object UsageAccess {
    fun granted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION") // Deprecated in later SDKs; it's Android 11's call, and the app only runs there.
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
