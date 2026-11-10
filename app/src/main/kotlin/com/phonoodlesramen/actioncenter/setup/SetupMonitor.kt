package com.phonoodlesramen.actioncenter.setup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Settings › Setup and the top bar's setup chip (setup without a computer): where each step
 * stands, read again on every resume, since all of them change in TV Settings while
 * Action Center is away.
 */
class SetupMonitor(
    private val checks: SetupChecks,
    private val store: SetupStore,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val read = MutableStateFlow(SetupStatus())

    val status: StateFlow<SetupStatus> = combine(read, store.chipHidden) { status, hidden -> status.copy(chipHidden = hidden) }
        .stateIn(scope, SharingStarted.Eagerly, SetupStatus())

    /** Reads every step again. Never throws: a check that fails reads as not done. */
    suspend fun refresh() {
        read.value = withContext(io) {
            val homeWatch = runCatching { checks.homeWatchOn() }.getOrDefault(false)
            if (homeWatch) store.setHomeWatchWasOn(true)
            SetupStatus(
                home = route(runCatching { checks.homeIsOurs() }.getOrDefault(false), homeWatch, store.homeWatchWasOn()),
                tvListings = runCatching { checks.granted(SetupPermission.TV_LISTINGS) }.getOrDefault(false),
                pictures = runCatching { checks.granted(SetupPermission.PICTURES) }.getOrDefault(false),
                usageAccess = runCatching { checks.usageAccess() }.getOrDefault(false),
                accessibilityLink = runCatching { checks.accessibilityLink() }.getOrDefault(false),
            )
        }
    }

    suspend fun setChipHidden(hidden: Boolean) = store.setChipHidden(hidden)

    /**
     * What Settings › Setup's Allow does for [permission]: ask Android, or, once Android has
     * stopped showing its question (asked before, refused, no rationale), open App info.
     */
    suspend fun allowRoute(permission: SetupPermission, showsRationale: Boolean): AllowRoute {
        if (store.asked(permission) && !showsRationale) return AllowRoute.APP_INFO
        store.markAsked(permission)
        return AllowRoute.ASK
    }

    companion object {
        /** Home itself wins; then HomeWatch; then whether HomeWatch was on before. */
        fun route(homeIsOurs: Boolean, homeWatchOn: Boolean, homeWatchWasOn: Boolean): HomeRoute = when {
            homeIsOurs -> HomeRoute.HOME
            homeWatchOn -> HomeRoute.HOMEWATCH
            homeWatchWasOn -> HomeRoute.WENT_OFF
            else -> HomeRoute.OFF
        }
    }
}

/** Where Allow goes ([SetupMonitor.allowRoute]). */
enum class AllowRoute { ASK, APP_INFO }
