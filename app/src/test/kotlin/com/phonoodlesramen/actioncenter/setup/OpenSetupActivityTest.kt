package com.phonoodlesramen.actioncenter.setup

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.phonoodlesramen.actioncenter.HomeActivity
import com.phonoodlesramen.actioncenter.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.xmlpull.v1.XmlPullParser

/**
 * HomeWatch's Configure in TV Settings › Accessibility: the service names an activity that
 * exists and TV Settings may start (a missing one crashes TV Settings, as Projectivy's did in
 * miproja1#673), and that activity opens Settings › Setup in the home.
 */
@RunWith(RobolectricTestRunner::class)
class OpenSetupActivityTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `HomeWatch's Configure names an exported activity in the manifest`() {
        val parser = context.resources.getXml(R.xml.home_watch_service)
        var settingsActivity: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "accessibility-service") {
                settingsActivity = parser.getAttributeValue(ANDROID, "settingsActivity")
            }
        }
        assertEquals(OpenSetupActivity::class.java.name, settingsActivity)
        val info = context.packageManager.getActivityInfo(ComponentName(context, settingsActivity!!), PackageManager.GET_META_DATA)
        assertTrue("TV Settings starts it from outside the app", info.exported)
    }

    @Test
    fun `it opens Settings › Setup in the home, and goes`() {
        val controller = Robolectric.buildActivity(OpenSetupActivity::class.java).create()
        val started = shadowOf(controller.get()).nextStartedActivity
        assertNotNull(started)
        assertEquals(HomeActivity::class.java.name, started.component?.className)
        assertEquals(OpenSetupActivity.ACTION_OPEN_SETUP, started.action)
        assertTrue(controller.get().isFinishing)
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
