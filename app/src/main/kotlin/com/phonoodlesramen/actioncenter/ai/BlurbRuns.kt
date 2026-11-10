package com.phonoodlesramen.actioncenter.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** The last night's run (M11): when, how many blurbs and the tokens they took, or why it failed. */
data class BlurbRun(
    val atMillis: Long,
    val blurbs: Int = 0,
    val usage: TokenUsage = TokenUsage(),
    /** A sentence for Settings; null when it worked. Never the key or the request. */
    val error: String? = null,
)

/** The last [BlurbRun], in its own DataStore file: Settings › AI blurbs reads it. */
class BlurbRuns(private val store: DataStore<Preferences>) {
    val last: Flow<BlurbRun?> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            prefs[AT]?.let { at ->
                BlurbRun(at, prefs[COUNT] ?: 0, TokenUsage(prefs[INPUT] ?: 0, prefs[OUTPUT] ?: 0), prefs[ERROR])
            }
        }

    suspend fun save(run: BlurbRun) {
        store.edit {
            it[AT] = run.atMillis
            it[COUNT] = run.blurbs
            it[INPUT] = run.usage.input
            it[OUTPUT] = run.usage.output
            if (run.error != null) it[ERROR] = run.error else it.remove(ERROR)
        }
    }

    private companion object {
        val AT = longPreferencesKey("last_run_at")
        val COUNT = intPreferencesKey("last_run_blurbs")
        val INPUT = longPreferencesKey("last_run_input_tokens")
        val OUTPUT = longPreferencesKey("last_run_output_tokens")
        val ERROR = stringPreferencesKey("last_run_error")
    }
}

/**
 * Settings › AI blurbs' last-run line: "Last night at 4:02 AM: 28 blurbs, 7,530 tokens,
 * about $0.002", or when and why it failed. Pure.
 */
object BlurbRunLine {
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

    /** Before this hour, a run belongs to the night before: the 4 AM run is "last night". */
    private const val NIGHT_ENDS = 6

    /** After this hour, yesterday's run was "last night" too. */
    private const val NIGHT_STARTS = 18

    fun of(run: BlurbRun, now: ZonedDateTime): String {
        val at = Instant.ofEpochMilli(run.atMillis).atZone(now.zone)
        val what = run.error ?: buildString {
            append(if (run.blurbs == 1) "1 blurb" else "${run.blurbs} blurbs")
            if (run.usage.total > 0) {
                append(", ").append(String.format(Locale.US, "%,d", run.usage.total)).append(" tokens, ").append(dollars(run.usage.dollars))
            }
        }
        return "${whenText(at, now)}: $what"
    }

    fun of(run: BlurbRun, nowMillis: Long, zone: ZoneId): String = of(run, Instant.ofEpochMilli(nowMillis).atZone(zone))

    /** "Last night at 4:02 AM", "Today at 9:14 PM", "Yesterday at 3:00 PM" or "Mon, Oct 26 at 4:02 AM". */
    internal fun whenText(at: ZonedDateTime, now: ZonedDateTime): String {
        val day = at.toLocalDate()
        val today = now.toLocalDate()
        val time = at.format(CLOCK)
        return when {
            day == today && at.hour < NIGHT_ENDS && now.hour >= NIGHT_ENDS -> "Last night at $time"
            day == today -> "Today at $time"
            day == today.minusDays(1) && at.hour >= NIGHT_STARTS -> "Last night at $time"
            day == today.minusDays(1) -> "Yesterday at $time"
            else -> "${at.format(DATE)} at $time"
        }
    }

    /** "about $0.002", "about $0.05", or "under $0.001" for a few hundred tokens. */
    internal fun dollars(amount: Double): String = when {
        amount < 0.0005 -> "under $0.001"
        amount < 0.01 -> String.format(Locale.US, "about $%.3f", amount)
        else -> String.format(Locale.US, "about $%.2f", amount)
    }
}
