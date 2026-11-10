package com.phonoodlesramen.actioncenter.setup

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Setup without a computer: what counts as left, what the chip says, and the system's answers read right. */
@RunWith(RobolectricTestRunner::class)
class SetupStatusTest {
    private val newTv = SetupStatus(home = HomeRoute.OFF, tvListings = false, pictures = false, usageAccess = false)

    @Test
    fun `a new TV has four steps left, and the chip says so`() {
        assertEquals(4, newTv.left)
        assertEquals(SetupChip.FinishSetup(4), newTv.chip(kid = false))
        assertEquals("Finish setup · 4 left", newTv.chip(kid = false)!!.label)
    }

    @Test
    fun `HomeWatch counts as Home done, and a set-up TV shows no chip`() {
        assertEquals(3, newTv.copy(home = HomeRoute.HOMEWATCH).left)
        assertEquals(0, SetupStatus().left)
        assertNull(SetupStatus().chip(kid = false))
    }

    @Test
    fun `HomeWatch turned off by Android is a warning, even with nothing else left`() {
        val wentOff = SetupStatus(home = HomeRoute.WENT_OFF)
        assertEquals(1, wentOff.left)
        assertEquals(SetupChip.HomeButtonOff, wentOff.chip(kid = false))
    }

    @Test
    fun `no chip for a kid, or once it's hidden`() {
        assertNull(newTv.chip(kid = true))
        assertNull(newTv.copy(chipHidden = true).chip(kid = false))
        assertNull(SetupStatus(home = HomeRoute.WENT_OFF, chipHidden = true).chip(kid = false))
    }

    @Test
    fun `Home itself beats HomeWatch, which beats having been on`() {
        assertEquals(HomeRoute.HOME, SetupMonitor.route(homeIsOurs = true, homeWatchOn = true, homeWatchWasOn = true))
        assertEquals(HomeRoute.HOMEWATCH, SetupMonitor.route(homeIsOurs = false, homeWatchOn = true, homeWatchWasOn = false))
        assertEquals(HomeRoute.WENT_OFF, SetupMonitor.route(homeIsOurs = false, homeWatchOn = false, homeWatchWasOn = true))
        assertEquals(HomeRoute.OFF, SetupMonitor.route(homeIsOurs = false, homeWatchOn = false, homeWatchWasOn = false))
    }

    @Test
    fun `HomeWatch is on when Android's list names it, short or long`() {
        val service = ComponentName("com.phonoodlesramen.actioncenter", "com.phonoodlesramen.actioncenter.home.HomeWatchService")
        assertTrue(HomeWatchSetting.isOn("com.phonoodlesramen.actioncenter/.home.HomeWatchService", service))
        assertTrue(HomeWatchSetting.isOn("com.example.other/.Watcher:com.phonoodlesramen.actioncenter/com.phonoodlesramen.actioncenter.home.HomeWatchService", service))
        assertFalse(HomeWatchSetting.isOn("com.example.other/.Watcher", service))
        assertFalse(HomeWatchSetting.isOn("", service))
        assertFalse(HomeWatchSetting.isOn(null, service))
    }

    @Test
    fun `the SHIELD's Accessibility link is a stub, a Google TV's is real`() {
        assertFalse(SettingsLinks.usable("com.google.android.tv.frameworkpackagestubs", "com.google.android.tv.frameworkpackagestubs.Stubs\$SettingsStub"))
        assertTrue(SettingsLinks.usable("com.android.tv.settings", "com.android.tv.settings.oemlink.AccessibilitySettingsActivity"))
        assertFalse(SettingsLinks.usable(null, null))
    }

    @Test
    fun `pictures are the storage permission up to Android 12, images and video from 13`() {
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), SetupPermission.PICTURES.permissions(30))
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), SetupPermission.PICTURES.permissions(32))
        assertEquals(
            listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"),
            SetupPermission.PICTURES.permissions(33),
        )
        assertEquals(listOf("android.permission.READ_TV_LISTINGS"), SetupPermission.TV_LISTINGS.permissions(30))
    }
}
