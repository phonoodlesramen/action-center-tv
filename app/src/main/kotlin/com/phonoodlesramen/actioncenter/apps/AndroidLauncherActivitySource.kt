package com.phonoodlesramen.actioncenter.apps

import android.content.Intent
import android.content.pm.PackageManager

class AndroidLauncherActivitySource(private val pm: PackageManager) : LauncherActivitySource {
    override fun leanbackActivities(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map { info ->
            LaunchableApp(
                packageName = info.activityInfo.packageName,
                activityName = info.activityInfo.name,
                label = info.loadLabel(pm).toString(),
            )
        }
    }
}
