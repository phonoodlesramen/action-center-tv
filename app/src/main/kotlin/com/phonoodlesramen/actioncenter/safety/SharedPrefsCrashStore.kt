package com.phonoodlesramen.actioncenter.safety

import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPrefsCrashStore(private val prefs: SharedPreferences) : CrashStore {
    override fun read(): CrashRecord? {
        if (!prefs.contains(KEY_TIME)) return null
        return CrashRecord(prefs.getLong(KEY_TIME, 0L), prefs.getString(KEY_SUMMARY, null).orEmpty())
    }

    // commit, not apply: the crash handler runs just before the process is killed.
    override fun write(record: CrashRecord) = prefs.edit(commit = true) {
        putLong(KEY_TIME, record.timeMillis)
        putString(KEY_SUMMARY, record.summary)
    }

    override fun clear() = prefs.edit(commit = true) {
        remove(KEY_TIME)
        remove(KEY_SUMMARY)
    }

    private companion object {
        const val KEY_TIME = "crash_time_millis"
        const val KEY_SUMMARY = "crash_summary"
    }
}
