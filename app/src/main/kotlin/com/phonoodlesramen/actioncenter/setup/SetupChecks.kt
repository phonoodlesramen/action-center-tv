package com.phonoodlesramen.actioncenter.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.phonoodlesramen.actioncenter.apps.UsageAccess
import com.phonoodlesramen.actioncenter.home.HomeWatchService

/** What [SetupMonitor] reads from the system, so tests can fake it. Every call is quick and local. */
interface SetupChecks {
    /** Home resolves to Action Center itself (the stock launcher is off). */
    fun homeIsOurs(): Boolean

    /** HomeWatch is in Android's list of accessibility services that are on. */
    fun homeWatchOn(): Boolean

    fun granted(permission: SetupPermission): Boolean
    fun usageAccess(): Boolean

    /** This TV opens Accessibility from an app ([SettingsLinks.usable]). */
    fun accessibilityLink(): Boolean

    /** Android's Install unknown apps is on for Action Center, so it can install its own updates. */
    fun canInstallUpdates(): Boolean
}

/** The checks on a real TV. */
class AndroidSetupChecks(private val context: Context) : SetupChecks {
    private val packageManager = context.packageManager

    override fun homeIsOurs(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    }

    override fun homeWatchOn(): Boolean = HomeWatchSetting.isOn(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        ComponentName(context, HomeWatchService::class.java),
    )

    override fun granted(permission: SetupPermission): Boolean =
        permission.permissions(Build.VERSION.SDK_INT).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun usageAccess(): Boolean = UsageAccess.granted(context)

    override fun accessibilityLink(): Boolean {
        val resolved = packageManager.resolveActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0)?.activityInfo
        return SettingsLinks.usable(resolved?.packageName, resolved?.name)
    }

    override fun canInstallUpdates(): Boolean = packageManager.canRequestPackageInstalls()
}

/** Android's `enabled_accessibility_services`: a colon-separated list of components. */
object HomeWatchSetting {
    fun isOn(setting: String?, service: ComponentName): Boolean =
        setting.orEmpty().split(':').any { ComponentName.unflattenFromString(it.trim()) == service }
}

/** Whether a system-settings link goes somewhere real, not to a stub that says nothing can open it. */
object SettingsLinks {
    /** The SHIELD answers `ACCESSIBILITY_SETTINGS` with this package's stub (11/06/2026). */
    const val STUBS = "com.google.android.tv.frameworkpackagestubs"

    fun usable(packageName: String?, className: String?): Boolean =
        packageName != null && packageName != STUBS && className?.contains("Stub") != true
}
