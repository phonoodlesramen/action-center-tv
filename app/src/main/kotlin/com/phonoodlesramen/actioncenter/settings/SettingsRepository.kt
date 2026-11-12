package com.phonoodlesramen.actioncenter.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensMoveOn
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.context.ContextSchedule
import com.phonoodlesramen.actioncenter.context.Hours
import com.phonoodlesramen.actioncenter.handoff.PlayIn
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.suggest.QuickWatch
import com.phonoodlesramen.actioncenter.ui.theme.LayoutScale
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class Settings(
    val accentIndex: Int = 0,
    val layoutScale: Float = LayoutScale.DEFAULT,
    /** Drives the greeting. Profiles (Phase 5) replace it. */
    val name: String = "",
    /** Settings › Diagnostics: show made-up content instead of the first-run state. */
    val showSampleContent: Boolean = false,
    /** Apps hidden from the launchpad (still in All apps), by [com.phonoodlesramen.actioncenter.apps.key]. */
    val hiddenApps: Set<String> = emptySet(),
    /** Launches from Action Center, by app key: the launchpad order until Phase 4. */
    val launchCounts: Map<String, Int> = emptyMap(),
    /** Settings › Lenses (U19). Stored as the ones turned off, so new lenses start on. */
    val disabledLenses: Set<Lens> = emptySet(),
    /** Settings › Time & modes: Quick Watch fits before this (S6), in 15-minute steps. Every night but the weekend's (M4). */
    val quickWatchCutoff: LocalTime = QuickWatch.DEFAULT_CUTOFF,
    /** Settings › Time & modes: Quick Watch's cutoff on Friday and Saturday nights (M4). */
    val weekendCutoff: LocalTime = QuickWatch.WEEKEND_CUTOFF,
    /** Settings › Time & modes: when each context mode runs (M1, M2). */
    val contextSchedule: ContextSchedule = ContextSchedule.DEFAULT,
    /** Settings › AI blurbs (M7): nothing is sent until it's on and a key is stored. */
    val aiBlurbs: Boolean = false,
    /**
     * Settings › Plex › Play in (Phase 11, K4): where every library Play and the guide's
     * Watch in go, on this TV. Null until chosen: Plezy when it's installed, else Plex ([PlayIn.of]).
     */
    val playIn: PlayIn? = null,
    /**
     * Settings › Plex › Count guide viewing in Plex (Phase 11, K1, K2): a guide program watched
     * to its credits, three quarters of it in one sitting, is marked watched for whoever's
     * watching. On by default.
     */
    val countGuideViewing: Boolean = true,
    /** Settings › Lenses (Phase 14, L1-L5): rotating, its speed, Shuffle, and when a title moves on. */
    val lensPrefs: LensPrefs = LensPrefs(),
    /** Search's last searches (Phase 15), newest first, by profile id: on this TV only. */
    val recentSearches: Map<String, List<String>> = emptyMap(),
    /** Settings › Search › Include streaming apps (Phase 15, Q3): on by default. */
    val searchStreaming: Boolean = true,
    /** Settings › Look › Layout: how your home is laid out; a kid's keeps its own. Dashboard by default. */
    val homeLayout: HomeLayout = HomeLayout.DEFAULT,
    /** Settings › Look › Change with the time of day (Moments, H3): off by default. */
    val moments: Boolean = false,
    /** The chip's Keep the usual layout: the mode and evening it holds for ([com.phonoodlesramen.actioncenter.home.Moments.held]). */
    val momentsHeld: String? = null,
    /** Settings › Look › Show the side rail (H4): off by default. */
    val sideRail: Boolean = false,
) {
    val enabledLenses: Set<Lens> get() = Lens.entries.toSet() - disabledLenses
}

/** The app's settings (D10: DataStore). Values are clamped on the way in and out. */
class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<Settings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun setAccent(index: Int) = store.edit { it[ACCENT] = index.coerceIn(Palette.Accents.indices) }

    suspend fun setLayoutScale(scale: Float) = store.edit { it[SCALE] = LayoutScale.clamp(scale) }

    /** Settings › Look › Layout. */
    suspend fun setHomeLayout(layout: HomeLayout) = store.edit { it[HOME_LAYOUT] = layout.name }

    /** Settings › Look › Change with the time of day (Moments). Switching it forgets a Keep the usual layout. */
    suspend fun setMoments(on: Boolean) = store.edit {
        it[MOMENTS] = on
        it.remove(MOMENTS_HELD)
    }

    /** The chip's Keep the usual layout: no moment for [held] ([com.phonoodlesramen.actioncenter.home.Moments.held]). */
    suspend fun holdMoments(held: String) = store.edit { it[MOMENTS_HELD] = held }

    /** Settings › Look › Show the side rail (H4). */
    suspend fun setSideRail(on: Boolean) = store.edit { it[SIDE_RAIL] = on }

    suspend fun setName(name: String) = store.edit { it[NAME] = name.trim().take(MAX_NAME_LENGTH) }

    suspend fun setShowSampleContent(show: Boolean) = store.edit { it[SAMPLE] = show }

    suspend fun setHidden(appKey: String, hidden: Boolean) = store.edit {
        val current = it[HIDDEN].orEmpty()
        it[HIDDEN] = if (hidden) current + appKey else current - appKey
    }

    /** Turning off the last lens that's on does nothing: the card always has one. */
    suspend fun setLensEnabled(lens: Lens, enabled: Boolean) = store.edit {
        val disabled = it[DISABLED_LENSES].orEmpty()
        val next = if (enabled) disabled - lens.name else disabled + lens.name
        if (Lens.entries.any { entry -> entry.name !in next }) it[DISABLED_LENSES] = next
    }

    /** Settings › Lenses › Rotate on its own (L1). */
    suspend fun setLensRotate(on: Boolean) = store.edit { it[LENS_ROTATE] = on }

    /** Settings › Lenses › Speed (L2): a notch, 1 to 5. */
    suspend fun setLensSpeed(notch: Int) = store.edit { it[LENS_SPEED] = notch.coerceIn(1, LensPrefs.SPEEDS.size) }

    /** Settings › Lenses › Shuffle (L4). */
    suspend fun setLensShuffle(on: Boolean) = store.edit { it[LENS_SHUFFLE] = on }

    /** Settings › Lenses › Move on from a title (L5). */
    suspend fun setLensMoveOn(moveOn: LensMoveOn) = store.edit { it[LENS_MOVE_ON] = moveOn.name }

    /** Search (Phase 15): [text] leads [profileId]'s last searches; the same words again move up, and only [RECENT_SEARCHES] stay. */
    suspend fun rememberSearch(profileId: String, text: String) = store.edit {
        val words = text.trim().replace(Regex("\\s+"), " ").take(MAX_SEARCH_LENGTH)
        if (words.isEmpty()) return@edit
        val key = stringPreferencesKey(SEARCH_PREFIX + profileId)
        val before = it[key]?.split('\n').orEmpty().filter(String::isNotBlank)
        it[key] = (listOf(words) + before.filterNot { old -> old.equals(words, ignoreCase = true) }).take(RECENT_SEARCHES).joinToString("\n")
    }

    /** Rounded to the nearest quarter hour. */
    suspend fun setQuickWatchCutoff(cutoff: LocalTime) = store.edit { it[CUTOFF] = quarterHour(cutoff.toSecondOfDay() / 60) }

    /** Rounded to the nearest quarter hour. */
    suspend fun setWeekendCutoff(cutoff: LocalTime) = store.edit { it[WEEKEND_CUTOFF] = quarterHour(cutoff.toSecondOfDay() / 60) }

    /**
     * [mode]'s new hours, rounded to quarter hours. Refused, and the reason returned, when
     * the schedule would have a problem ([ContextSchedule.problem]); null once saved.
     */
    suspend fun setModeHours(mode: ContextMode, hours: Hours): String? {
        if (mode == ContextMode.DAYTIME) return null
        var problem: String? = null
        store.edit {
            val rounded = Hours(quarter(hours.start), quarter(hours.end))
            val next = it.schedule().with(mode, rounded)
            problem = next.problem()
            if (problem == null) {
                it[startKey(mode)] = rounded.start.toSecondOfDay() / 60
                it[endKey(mode)] = rounded.end.toSecondOfDay() / 60
            }
        }
        return problem
    }

    suspend fun setAiBlurbs(on: Boolean) = store.edit { it[AI_BLURBS] = on }

    /** Settings › Plex › Play in (K4); null goes back to "Plezy if it's installed". */
    suspend fun setPlayIn(app: PlayIn?) = store.edit { if (app == null) it.remove(PLAY_IN) else it[PLAY_IN] = app.name }

    /** Settings › Plex › Count guide viewing in Plex (K1, K2). */
    suspend fun setCountGuideViewing(on: Boolean) = store.edit { it[COUNT_GUIDE_VIEWING] = on }

    /** Settings › Search › Include streaming apps (Q3). */
    suspend fun setSearchStreaming(on: Boolean) = store.edit { it[SEARCH_STREAMING] = on }

    suspend fun recordLaunch(appKey: String) = store.edit {
        val key = intPreferencesKey(LAUNCH_PREFIX + appKey)
        it[key] = (it[key] ?: 0) + 1
    }

    private fun Preferences.toSettings() = Settings(
        accentIndex = (this[ACCENT] ?: 0).coerceIn(Palette.Accents.indices),
        layoutScale = LayoutScale.clamp(this[SCALE] ?: LayoutScale.DEFAULT),
        name = this[NAME].orEmpty(),
        showSampleContent = this[SAMPLE] ?: false,
        hiddenApps = this[HIDDEN].orEmpty(),
        launchCounts = asMap().entries
            .filter { it.key.name.startsWith(LAUNCH_PREFIX) }
            .associate { it.key.name.removePrefix(LAUNCH_PREFIX) to (it.value as Int) },
        disabledLenses = this[DISABLED_LENSES].orEmpty().mapNotNull { name -> Lens.entries.firstOrNull { it.name == name } }.toSet(),
        quickWatchCutoff = this[CUTOFF]?.let(::minutesToTime) ?: QuickWatch.DEFAULT_CUTOFF,
        weekendCutoff = this[WEEKEND_CUTOFF]?.let(::minutesToTime) ?: QuickWatch.WEEKEND_CUTOFF,
        contextSchedule = schedule(),
        aiBlurbs = this[AI_BLURBS] ?: false,
        playIn = this[PLAY_IN]?.let { name -> PlayIn.entries.firstOrNull { it.name == name } },
        countGuideViewing = this[COUNT_GUIDE_VIEWING] ?: true,
        lensPrefs = LensPrefs(
            rotate = this[LENS_ROTATE] ?: true,
            speed = (this[LENS_SPEED] ?: LensPrefs.DEFAULT_SPEED).coerceIn(1, LensPrefs.SPEEDS.size),
            shuffle = this[LENS_SHUFFLE] ?: true,
            moveOn = this[LENS_MOVE_ON]?.let { name -> LensMoveOn.entries.firstOrNull { it.name == name } } ?: LensMoveOn.THREE_DAYS,
        ),
        recentSearches = asMap().entries
            .filter { it.key.name.startsWith(SEARCH_PREFIX) }
            .associate { it.key.name.removePrefix(SEARCH_PREFIX) to (it.value as String).split('\n').filter(String::isNotBlank) },
        searchStreaming = this[SEARCH_STREAMING] ?: true,
        homeLayout = HomeLayout.of(this[HOME_LAYOUT]),
        moments = this[MOMENTS] ?: false,
        momentsHeld = this[MOMENTS_HELD],
        sideRail = this[SIDE_RAIL] ?: false,
    )

    /** The stored hours, each mode falling back to its default; the default schedule if they no longer fit together. */
    private fun Preferences.schedule(): ContextSchedule {
        val stored = SCHEDULED.fold(ContextSchedule.DEFAULT) { schedule, mode ->
            val start = this[startKey(mode)] ?: return@fold schedule
            val end = this[endKey(mode)] ?: return@fold schedule
            schedule.with(mode, Hours(minutesToTime(start), minutesToTime(end)))
        }
        return if (stored.problem() == null) stored else ContextSchedule.DEFAULT
    }

    private companion object {
        /** Search keeps this many last searches a profile (Phase 15). */
        const val RECENT_SEARCHES = 5
        const val MAX_SEARCH_LENGTH = 60
        const val SEARCH_PREFIX = "recent_searches:"
        const val MAX_NAME_LENGTH = 24
        const val DAY_MINUTES = 24 * 60

        /** Minutes since midnight, to the nearest quarter hour, within the day. */
        fun quarterHour(minutes: Int) = ((minutes + 7) / 15 * 15).mod(DAY_MINUTES)

        fun minutesToTime(minutes: Int): LocalTime = LocalTime.MIN.plusMinutes(quarterHour(minutes).toLong())

        fun quarter(time: LocalTime): LocalTime = minutesToTime(time.toSecondOfDay() / 60)

        val SCHEDULED = ContextMode.entries - ContextMode.DAYTIME

        fun startKey(mode: ContextMode) = intPreferencesKey("mode_${mode.name.lowercase()}_start")

        fun endKey(mode: ContextMode) = intPreferencesKey("mode_${mode.name.lowercase()}_end")

        val WEEKEND_CUTOFF = intPreferencesKey("quick_watch_weekend_cutoff_minutes")
        val AI_BLURBS = booleanPreferencesKey("ai_blurbs")
        val PLAY_IN = stringPreferencesKey("play_in")
        val COUNT_GUIDE_VIEWING = booleanPreferencesKey("count_guide_viewing")
        val LENS_ROTATE = booleanPreferencesKey("lens_rotate")
        val LENS_SPEED = intPreferencesKey("lens_speed")
        val LENS_SHUFFLE = booleanPreferencesKey("lens_shuffle")
        val LENS_MOVE_ON = stringPreferencesKey("lens_move_on")
        val SEARCH_STREAMING = booleanPreferencesKey("search_streaming")
        val HOME_LAYOUT = stringPreferencesKey("home_layout")
        val MOMENTS = booleanPreferencesKey("home_moments")
        val MOMENTS_HELD = stringPreferencesKey("home_moments_held")
        val SIDE_RAIL = booleanPreferencesKey("home_side_rail")

        val CUTOFF = intPreferencesKey("quick_watch_cutoff_minutes")
        const val LAUNCH_PREFIX = "launch:"
        val ACCENT = intPreferencesKey("accent_index")
        val SCALE = floatPreferencesKey("layout_scale")
        val NAME = stringPreferencesKey("name")
        val SAMPLE = booleanPreferencesKey("show_sample_content")
        val HIDDEN = stringSetPreferencesKey("hidden_apps")
        val DISABLED_LENSES = stringSetPreferencesKey("disabled_lenses")
    }
}
