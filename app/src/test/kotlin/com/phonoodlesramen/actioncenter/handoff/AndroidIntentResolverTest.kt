package com.phonoodlesramen.actioncenter.handoff

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import com.phonoodlesramen.actioncenter.handoff.IntentSpec.LaunchApp
import com.phonoodlesramen.actioncenter.handoff.IntentSpec.ViewUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidIntentResolverTest {
    private val pm = RuntimeEnvironment.getApplication().packageManager
    private val resolver = AndroidIntentResolver(pm)

    private fun addActivity(component: ComponentName, filter: IntentFilter) {
        shadowOf(pm).addActivityIfNotPresent(component)
        shadowOf(pm).addIntentFilterForActivity(component, filter)
    }

    private fun viewFilter(scheme: String) = IntentFilter(Intent.ACTION_VIEW).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        addDataScheme(scheme)
    }

    private fun leanbackFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    }

    @Test
    fun `a view uri resolves when its pinned app handles the scheme`() {
        addActivity(ComponentName(Handoffs.PLEZY, "com.edde746.plezy.PlezyActivity"), viewFilter("plezy"))

        assertTrue(resolver.canResolve(ViewUri("plezy://play?server=abc&id=1", Handoffs.PLEZY)))
    }

    @Test
    fun `a view uri pinned to an app that is not installed does not resolve`() {
        assertFalse(resolver.canResolve(ViewUri("plezy://play?server=abc&id=1", Handoffs.PLEZY)))
    }

    @Test
    fun `an unpinned view uri resolves through any app that handles the scheme`() {
        addActivity(ComponentName("com.google.android.youtube", "com.example.UrlActivity"), viewFilter("vnd.youtube"))

        assertTrue(resolver.canResolve(ViewUri("vnd.youtube:dQw4w9WgXcQ")))
    }

    @Test
    fun `launching an app resolves through its leanback launcher activity`() {
        addActivity(ComponentName(Handoffs.PLEX, "com.plexapp.plex.activities.SplashActivity"), leanbackFilter())

        assertTrue(resolver.canResolve(LaunchApp(Handoffs.PLEX)))
    }

    @Test
    fun `launching an app that is not installed does not resolve`() {
        assertFalse(resolver.canResolve(LaunchApp(Handoffs.PLEX)))
    }

    @Test
    fun `a view uri becomes an ACTION_VIEW intent for the pinned package`() {
        val intent = resolver.toIntent(ViewUri("https://www.youtube.com/watch?v=dQw4w9WgXcQ", Handoffs.YOUTUBE_TV))

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", intent.dataString)
        assertEquals(Handoffs.YOUTUBE_TV, intent.`package`)
    }
}
