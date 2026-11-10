package com.phonoodlesramen.actioncenter.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeWatchServiceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `HomeWatch is declared as an accessibility service only the system can bind, with its config`() {
        val services = context.packageManager.queryIntentServices(
            Intent("android.accessibilityservice.AccessibilityService").setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        )
        val info = services.single().serviceInfo

        assertEquals(HomeWatchService::class.java.name, info.name)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, info.permission)
        assertNotEquals(0, info.metaData.getInt("android.accessibilityservice"))
    }
}
