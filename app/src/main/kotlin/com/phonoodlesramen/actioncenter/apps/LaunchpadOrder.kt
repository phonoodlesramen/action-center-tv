package com.phonoodlesramen.actioncenter.apps

/** Stable id for an app's launch activity, used for launch counts and hidden apps. */
val LaunchableApp.key: String get() = "$packageName/$activityName"

/**
 * Launchpad order [U7, U8]: by [usage] (M5, by package: what you open around this time),
 * then apps launched from Action Center the most, then A–Z.
 */
object LaunchpadOrder {
    const val LAUNCHPAD_SLOTS = 5
    const val MOST_USED_SLOTS = 5

    private val byLabel = compareBy(String.CASE_INSENSITIVE_ORDER, LaunchableApp::label)

    private fun order(counts: Map<String, Int>, usage: Map<String, Double>) =
        compareByDescending<LaunchableApp> { usage[it.packageName] ?: 0.0 }
            .thenByDescending { counts[it.key] ?: 0 }
            .then(byLabel)

    fun launchpad(
        apps: List<LaunchableApp>,
        counts: Map<String, Int>,
        hidden: Set<String>,
        usage: Map<String, Double> = emptyMap(),
    ): List<LaunchableApp> =
        apps.filter { it.key !in hidden }.sortedWith(order(counts, usage)).take(LAUNCHPAD_SLOTS)

    fun mostUsed(apps: List<LaunchableApp>, counts: Map<String, Int>, usage: Map<String, Double> = emptyMap()): List<LaunchableApp> =
        apps.filter { (counts[it.key] ?: 0) > 0 || (usage[it.packageName] ?: 0.0) > 0.0 }
            .sortedWith(order(counts, usage))
            .take(MOST_USED_SLOTS)
}
