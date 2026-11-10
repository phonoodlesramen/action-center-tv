package com.phonoodlesramen.actioncenter.system

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidPackageStateSourceTest {
    private val pm = RuntimeEnvironment.getApplication().packageManager
    private val source = AndroidPackageStateSource(pm)

    private fun install(packageName: String, enabledInManifest: Boolean = true) {
        shadowOf(pm).installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    enabled = enabledInManifest
                }
            },
        )
    }

    private fun setEnabledSetting(state: Int) =
        pm.setApplicationEnabledSetting(TVLAUNCHER, state, 0)

    @Test
    fun `an installed package in its default state is enabled`() {
        install(TVLAUNCHER)

        assertEquals(PackageState.ENABLED, source.stateOf(TVLAUNCHER))
    }

    @Test
    fun `pm disable-user makes it disabled`() {
        install(TVLAUNCHER)
        setEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)

        assertEquals(PackageState.DISABLED, source.stateOf(TVLAUNCHER))
    }

    @Test
    fun `re-enabling it, as an update does, makes it enabled again`() {
        install(TVLAUNCHER)
        setEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        setEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        assertEquals(PackageState.ENABLED, source.stateOf(TVLAUNCHER))
    }

    @Test
    fun `a package disabled in its manifest is disabled`() {
        install(TVLAUNCHER, enabledInManifest = false)

        assertEquals(PackageState.DISABLED, source.stateOf(TVLAUNCHER))
    }

    @Test
    fun `an uninstalled package is missing`() {
        assertEquals(PackageState.MISSING, source.stateOf(TVLAUNCHER))
    }

    private companion object {
        const val TVLAUNCHER = "com.google.android.tvlauncher"
    }
}
