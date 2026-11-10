package com.phonoodlesramen.actioncenter.home

import android.view.KeyEvent

/**
 * HomeWatch's rules: the Home button on a TV whose own home can't be turned off (a
 * Google TV, `docs/install.md`). [HomeWatchService] is a
 * thin shell around them.
 *
 * - **Home:** a press opens Action Center. The key is taken before the system sees it, so
 *   the TV's home never shows. Held, it opens the TV's own home, which then stays until
 *   Home is pressed again.
 * - **All apps:** the button opens Action Center's All apps.
 * - **The TV's home some other way** (Back out of an app, waking, the Assistant): Action
 *   Center opens over it. That's not so right after one of the TV's own buttons (Guide,
 *   profiles…): you asked for its home, and it stays.
 * - **Inactive** while [stockHome] is null, where Action Center is the Home (the Shield):
 *   every key passes.
 */
class HomeWatch(private val clock: () -> Long) {
    /** An activity, by package and class. */
    data class Screen(val packageName: String, val className: String)

    enum class Open { ACTION_CENTER, ALL_APPS, STOCK_HOME }

    /** [consume]: the system never sees the key. [open]: what to start. */
    data class KeyResult(val consume: Boolean, val open: Open? = null)

    /** What a window coming to the front means. */
    enum class Window {
        /** Not the TV's home. */
        OTHER,

        /** The TV's home, unasked: Action Center opens over it. */
        OPEN,

        /** The TV's home, which you chose: it stays. */
        CHOSEN,

        /** The TV's home, right after one of its own buttons ([ownButton]): it stays from now on. */
        OWN_BUTTON,
    }

    /** The TV's own home while it isn't Action Center; null passes every key. */
    var stockHome: Screen? = null
        set(value) {
            field = value
            if (value == null) chosen = false
        }

    /** You asked for the TV's own home (held Home, or one of its buttons): it stays until Home. */
    private var chosen = false
    private var lastKey: Int? = null
    private var lastKeyAt = 0L

    /** The key that last made the TV's home stay ([Window.OWN_BUTTON]), for the log. */
    var ownButton: Int? = null
        private set

    /** [heldMillis]: on a key's release, how long it was down. */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, heldMillis: Long, canceled: Boolean): KeyResult {
        if (stockHome == null) return PASS
        if (down && repeatCount == 0) {
            lastKey = keyCode
            lastKeyAt = clock()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME -> when {
                down || canceled -> CONSUME
                heldMillis >= HOLD_MILLIS -> {
                    chosen = true
                    KeyResult(consume = true, open = Open.STOCK_HOME)
                }
                else -> {
                    chosen = false
                    KeyResult(consume = true, open = Open.ACTION_CENTER)
                }
            }
            KeyEvent.KEYCODE_ALL_APPS -> if (down || canceled) {
                CONSUME
            } else {
                chosen = false
                KeyResult(consume = true, open = Open.ALL_APPS)
            }
            else -> PASS
        }
    }

    fun onWindow(packageName: String, className: String): Window {
        val home = stockHome ?: return Window.OTHER
        if (packageName != home.packageName || className != home.className) return Window.OTHER
        if (chosen) return Window.CHOSEN
        val key = lastKey
        if (key != null && key !in GIVES_WAY && clock() - lastKeyAt <= OWN_BUTTON_MILLIS) {
            chosen = true
            ownButton = key
            return Window.OWN_BUTTON
        }
        return Window.OPEN
    }

    /** Connected with [packageName] on screen: true when that's the TV's home, unasked (a cold boot). */
    fun onStart(packageName: String?): Boolean {
        val home = stockHome ?: return false
        return packageName == home.packageName && !chosen
    }

    companion object {
        /** Holding Home this long opens the TV's own home. */
        const val HOLD_MILLIS = 700L

        /** The TV's home this soon after one of its own buttons is that button's doing. */
        const val OWN_BUTTON_MILLIS = 3_000L

        private val PASS = KeyResult(consume = false)
        private val CONSUME = KeyResult(consume = true)

        /**
         * The TV's home that shows up after these keys gives way to Action Center: none of
         * them asks for it. Any other key (Guide, notifications, profiles, a maker's own
         * button) is taken to be one of the TV's own buttons.
         */
        val GIVES_WAY: Set<Int> = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_ALL_APPS, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_CAPTIONS,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SLEEP,
        ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)

        /**
         * The TV's own home, from what HOME resolves to: null when that's Action Center itself
         * (the Shield) or Android's chooser (no single Home).
         */
        fun stockHomeOf(resolvedPackage: String?, resolvedClass: String?, ourPackage: String): Screen? {
            if (resolvedPackage == null || resolvedClass == null) return null
            if (resolvedPackage == ourPackage || resolvedPackage == "android") return null
            return Screen(resolvedPackage, resolvedClass)
        }
    }
}
