package com.phonoodlesramen.actioncenter.home

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.phonoodlesramen.actioncenter.HomeActivity

/**
 * The remote's Home button for a Google TV, where Google TV's launcher has to stay on
 * ([HomeWatch], `docs/install.md`). Off until it's turned
 * on in Settings › Accessibility or with `scripts/accessibility-fallback.ps1 -Enable`.
 *
 * It reads which app is on screen once, when it connects (a cold boot), and the class of
 * each window that comes to the front; nothing else. The log has key and app names only.
 */
class HomeWatchService : AccessibilityService() {
    private val watch = HomeWatch(SystemClock::uptimeMillis)

    /** An open failed: every key passes until the service connects again. */
    private var stopTakingHome = false

    override fun onServiceConnected() {
        stopTakingHome = false
        watch.stockHome = stockHome()
        Log.i(LOG_TAG, watch.stockHome?.let { "home watch: on, Home from ${it.packageName}" } ?: "home watch: Action Center is the Home; passing every key")
        val onScreen = rootInActiveWindow?.packageName?.toString()
        if (watch.onStart(onScreen)) {
            Log.i(LOG_TAG, "home watch: the TV's home is on screen at start; opening Action Center")
            open(HomeWatch.Open.ACTION_CENTER)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        // The Home can change (an update, or Action Center made the Home): read it on each press.
        if (down && event.repeatCount == 0 && event.keyCode == KeyEvent.KEYCODE_HOME && !stopTakingHome) watch.stockHome = stockHome()
        val result = watch.onKey(event.keyCode, down, event.repeatCount, event.eventTime - event.downTime, event.isCanceled)
        result.open?.let(::open)
        return result.consume
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: return
        when (watch.onWindow(packageName, className)) {
            HomeWatch.Window.OPEN -> {
                Log.i(LOG_TAG, "home watch: $packageName came up unasked; opening Action Center")
                open(HomeWatch.Open.ACTION_CENTER)
            }
            HomeWatch.Window.OWN_BUTTON -> {
                val key = watch.ownButton?.let(KeyEvent::keyCodeToString)
                Log.i(LOG_TAG, "home watch: $packageName stays, asked for by key=$key")
            }
            HomeWatch.Window.CHOSEN, HomeWatch.Window.OTHER -> Unit
        }
    }

    override fun onInterrupt() = Unit

    private fun stockHome(): HomeWatch.Screen? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        return HomeWatch.stockHomeOf(info?.packageName, info?.name, packageName)
    }

    private fun open(target: HomeWatch.Open) {
        val intent = when (target) {
            // As the system starts a Home: the home lands at the top with Play focused (U10).
            HomeWatch.Open.ACTION_CENTER -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(this, HomeActivity::class.java)
            HomeWatch.Open.ALL_APPS -> Intent(Intent.ACTION_ALL_APPS).setClass(this, HomeActivity::class.java)
            HomeWatch.Open.STOCK_HOME -> {
                val home = watch.stockHome ?: return
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(home.packageName, home.className)
            }
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            // Not found or not allowed. Home must never go dead: stop taking keys until the
            // service connects again, so the next press reaches the TV's own home.
            Log.w(LOG_TAG, "home watch: couldn't open $target: ${e.javaClass.simpleName}; passing every key from now on")
            watch.stockHome = null
            stopTakingHome = true
        }
    }

    private companion object {
        const val LOG_TAG = "ActionCenterHome"
    }
}
