package com.phonoodlesramen.actioncenter.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import com.phonoodlesramen.actioncenter.ActionCenterApp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hands a checked APK to Android's installer. */
interface UpdateInstaller {
    /**
     * Starts installing [apk]. Null once Android has it: Android's own dialog may come next
     * (always on Android 11), then the app is replaced. Otherwise why it couldn't start.
     * [byPress]: the person asked, so Android's dialog may open; Install automatically's own
     * install never opens one. Never throws but to cancel.
     */
    suspend fun install(apk: File, byPress: Boolean = true): String?
}

/**
 * [UpdateInstaller] with a [PackageInstaller] session. On Android 12 and later it asks for no
 * second dialog ([PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]): the person already
 * pressed Install, and Action Center is updating itself. Android 11 always asks. What Android
 * says next goes to [UpdateStatusReceiver].
 */
class PackageInstallerUpdates(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UpdateInstaller {
    override suspend fun install(apk: File, byPress: Boolean): String? = withContext(io) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var id = -1
        try {
            id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(SPLIT, 0, apk.length()).use { output ->
                        input.copyTo(output, BUFFER)
                        session.fsync(output)
                    }
                }
                session.commit(UpdateStatusReceiver.pendingIntent(context, id, byPress).intentSender)
            }
            null
        } catch (e: IOException) {
            abandon(installer, id)
            "Android's installer couldn't take it (${e.message})"
        } catch (e: SecurityException) {
            abandon(installer, id)
            "Android didn't allow the install (${e.message})"
        } catch (e: IllegalStateException) {
            abandon(installer, id)
            "Android's installer couldn't take it (${e.message})"
        }
    }

    private fun abandon(installer: PackageInstaller, id: Int) {
        if (id >= 0) runCatching { installer.abandonSession(id) }
    }

    private companion object {
        const val SPLIT = "base.apk"
        const val BUFFER = 64 * 1024
    }
}

/**
 * What Android's installer says about an update [PackageInstallerUpdates] handed it:
 * - it wants to show its own dialog (always on Android 11): after a press, Action Center is in
 *   front, so the dialog opens over it; an install Install automatically started never opens
 *   one, and the update waits for a press instead;
 * - it was cancelled (the dialog's Cancel), or it failed: About says so.
 *
 * Success rarely arrives: Android replaces the app first, and the new one notices it's newer
 * when it starts ([UpdatesController.started]). This never throws: a crash here would put the
 * next start in safe mode.
 */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { handle(context, intent) }.onFailure { Log.w(TAG, "Couldn't handle the installer's answer", it) }
    }

    private fun handle(context: Context, intent: Intent) {
        val updates = (context.applicationContext as? ActionCenterApp)?.container?.updates ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "installer status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (!intent.getBooleanExtra(EXTRA_BY_PRESS, true)) {
                    updates.installerSaid(InstallerAnswer.NeedsPress)
                } else if (confirm == null) {
                    updates.installerSaid(InstallerAnswer.Failed("Android's installer didn't say what it needs"))
                } else {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    updates.installerSaid(InstallerAnswer.Asking)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updates.installerSaid(InstallerAnswer.Installed)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates.installerSaid(InstallerAnswer.Cancelled)
            else -> updates.installerSaid(InstallerAnswer.Failed(message ?: "Android's installer failed ($status)"))
        }
    }

    companion object {
        private const val TAG = "ActionCenterUpdates"
        const val ACTION = "com.phonoodlesramen.actioncenter.update.INSTALL_STATUS"
        private const val EXTRA_BY_PRESS = "com.phonoodlesramen.actioncenter.update.BY_PRESS"

        /** Mutable on Android 12 and later: the installer fills in its answer. */
        fun pendingIntent(context: Context, sessionId: Int, byPress: Boolean): PendingIntent {
            val intent = Intent(context, UpdateStatusReceiver::class.java).setAction(ACTION).putExtra(EXTRA_BY_PRESS, byPress)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        }
    }
}

/** What Android's installer said, for About's line. */
sealed interface InstallerAnswer {
    /** Android's own dialog is up. */
    data object Asking : InstallerAnswer

    /** Android wants a press, for an install nobody pressed for: the update waits for one. */
    data object NeedsPress : InstallerAnswer

    data object Cancelled : InstallerAnswer

    data object Installed : InstallerAnswer

    data class Failed(val why: String) : InstallerAnswer
}
