package com.phonoodlesramen.actioncenter.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * What Action Center's own screens say about whether anyone's using it, kept by the Home
 * activity and the guide player. Read from any thread.
 */
class ForegroundState {
    /** A Home screen of Action Center's is in front (the home, Settings, All apps…). */
    @Volatile var homeInFront: Boolean = false

    /** When a key was last pressed in it, or it last came to the front (wall clock). */
    @Volatile var lastTouchMillis: Long = 0

    /** The guide player is showing: it plays in Action Center's own process. */
    @Volatile var guidePlayerOpen: Boolean = false
}

/** Whether the TV is in use, at one moment. */
data class IdleSnapshot(
    val screenOff: Boolean = false,
    val dreaming: Boolean = false,
    /** Sound is playing in any app, as Android counts it: video apps' sound counts. */
    val playing: Boolean = false,
    val guidePlayerOpen: Boolean = false,
    val homeInFront: Boolean = false,
    val lastTouchMillis: Long = 0,
)

/**
 * When Install automatically may install by itself (A3): the TV is asleep, its screensaver is
 * on, or Action Center has sat untouched in front for [UNTOUCHED_MILLIS]; never while anything
 * plays or the guide player is open. Installing restarts Action Center, and Android then
 * compiles the new version, which takes CPU and memory a 2 GB TV's playback needs.
 */
object IdleRule {
    const val UNTOUCHED_MILLIS = 30 * 60_000L

    fun mayInstall(now: IdleSnapshot, nowMillis: Long): Boolean = when {
        now.playing || now.guidePlayerOpen -> false
        now.screenOff || now.dreaming -> true
        else -> now.homeInFront && nowMillis - now.lastTouchMillis >= UNTOUCHED_MILLIS
    }
}

/** Reads an [IdleSnapshot] now. */
fun interface IdleSignals {
    fun snapshot(): IdleSnapshot
}

/**
 * [IdleSignals] on a TV: the screen from [PowerManager], the screensaver from Android's
 * dreaming broadcasts (heard while the process runs), sound from [AudioManager], and the rest
 * from [ForegroundState].
 */
class AndroidIdleSignals(context: Context, private val foreground: ForegroundState) : IdleSignals {
    private val power = context.getSystemService(PowerManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile private var dreaming = false

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                dreaming = intent.action == Intent.ACTION_DREAMING_STARTED
            }
        }
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    override fun snapshot() = IdleSnapshot(
        screenOff = power?.isInteractive == false,
        dreaming = dreaming,
        playing = audio?.isMusicActive == true,
        guidePlayerOpen = foreground.guidePlayerOpen,
        homeInFront = foreground.homeInFront,
        lastTouchMillis = foreground.lastTouchMillis,
    )
}
