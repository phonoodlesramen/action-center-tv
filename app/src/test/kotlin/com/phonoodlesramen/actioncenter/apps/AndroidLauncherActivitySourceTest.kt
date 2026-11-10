package com.phonoodlesramen.actioncenter.apps

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidLauncherActivitySourceTest {
    private val pm = RuntimeEnvironment.getApplication().packageManager

    private fun addLeanbackActivity(packageName: String, activity: String, label: String) {
        shadowOf(pm).addOrUpdateActivity(
            ActivityInfo().apply {
                this.packageName = packageName
                name = activity
                nonLocalizedLabel = label
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            },
        )
        shadowOf(pm).addIntentFilterForActivity(
            ComponentName(packageName, activity),
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) },
        )
    }

    @Test
    fun `lists every leanback launcher activity with its label`() {
        addLeanbackActivity("com.edde746.plezy", "com.edde746.plezy.PlezyActivity", "Plezy")
        addLeanbackActivity("com.plexapp.android", "com.plexapp.plex.activities.SplashActivity", "Plex")

        val apps = AndroidLauncherActivitySource(pm).leanbackActivities()

        // Our own HomeActivity is a leanback launcher too; LaunchableAppsRepository drops it.
        assertEquals(
            setOf(
                LaunchableApp("com.edde746.plezy", "com.edde746.plezy.PlezyActivity", "Plezy"),
                LaunchableApp("com.plexapp.android", "com.plexapp.plex.activities.SplashActivity", "Plex"),
                LaunchableApp(
                    "com.phonoodlesramen.actioncenter",
                    "com.phonoodlesramen.actioncenter.HomeActivity",
                    "Action Center",
                ),
            ),
            apps.toSet(),
        )
    }
}
