package com.phonoodlesramen.actioncenter.apps

data class LaunchableApp(val packageName: String, val activityName: String, val label: String)

fun interface LauncherActivitySource {
    fun leanbackActivities(): List<LaunchableApp>
}

/**
 * The All Apps list: every TV-launchable app, so nothing becomes unreachable once
 * the stock launcher is disabled.
 */
class LaunchableAppsRepository(
    private val source: LauncherActivitySource,
    private val ownPackage: String,
) {
    fun apps(): List<LaunchableApp> = source.leanbackActivities()
        .filter { it.packageName != ownPackage }
        .distinctBy { it.packageName to it.activityName }
        .map { if (it.label.isBlank()) it.copy(label = it.packageName) else it }
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, LaunchableApp::label)
                .thenBy(LaunchableApp::packageName)
                .thenBy(LaunchableApp::activityName),
        )
}
