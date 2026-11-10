package com.phonoodlesramen.actioncenter.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchableAppsRepositoryTest {
    private fun app(packageName: String, label: String, activity: String = "$packageName.Main") =
        LaunchableApp(packageName = packageName, activityName = activity, label = label)

    private fun repository(vararg apps: LaunchableApp) =
        LaunchableAppsRepository(source = { apps.toList() }, ownPackage = OWN_PACKAGE)

    @Test
    fun `sorts by label ignoring case`() {
        val apps = repository(
            app("z.pkg", "Zeta"),
            app("a.pkg", "alpha"),
            app("b.pkg", "Beta"),
        ).apps()

        assertEquals(listOf("alpha", "Beta", "Zeta"), apps.map { it.label })
    }

    @Test
    fun `equal labels are ordered by package name`() {
        val apps = repository(app("b.pkg", "Player"), app("a.pkg", "Player")).apps()

        assertEquals(listOf("a.pkg", "b.pkg"), apps.map { it.packageName })
    }

    @Test
    fun `leaves out our own app`() {
        val apps = repository(app(OWN_PACKAGE, "Action Center"), app("a.pkg", "Plezy")).apps()

        assertEquals(listOf("a.pkg"), apps.map { it.packageName })
    }

    @Test
    fun `lists each activity once`() {
        val apps = repository(app("a.pkg", "Plezy"), app("a.pkg", "Plezy")).apps()

        assertEquals(1, apps.size)
    }

    @Test
    fun `keeps two different activities of the same app`() {
        val apps = repository(
            app("a.pkg", "Plex", activity = "a.pkg.Main"),
            app("a.pkg", "Plex Live", activity = "a.pkg.Live"),
        ).apps()

        assertEquals(2, apps.size)
    }

    @Test
    fun `falls back to the package name when the label is blank`() {
        val apps = repository(app("a.pkg", "  ")).apps()

        assertEquals("a.pkg", apps.single().label)
    }

    @Test
    fun `no launchable apps gives an empty list`() {
        assertTrue(repository().apps().isEmpty())
    }

    private companion object {
        const val OWN_PACKAGE = "com.phonoodlesramen.actioncenter"
    }
}
