package com.phonoodlesramen.actioncenter.handoff

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

class AndroidIntentResolver(private val pm: PackageManager) : IntentResolver {
    override fun canResolve(spec: IntentSpec): Boolean = when (spec) {
        // MATCH_DEFAULT_ONLY mirrors what startActivity will accept for an implicit intent.
        is IntentSpec.ViewUri -> pm.resolveActivity(toIntent(spec), PackageManager.MATCH_DEFAULT_ONLY) != null
        is IntentSpec.LaunchApp -> toIntent(spec) != null
    }

    /** The intent to start, or null for an app with no launch activity. */
    fun toIntent(spec: IntentSpec.ViewUri): Intent =
        Intent(Intent.ACTION_VIEW, spec.uri.toUri()).setPackage(spec.packageName)

    fun toIntent(spec: IntentSpec.LaunchApp): Intent? =
        pm.getLeanbackLaunchIntentForPackage(spec.packageName)
            ?: pm.getLaunchIntentForPackage(spec.packageName)

    fun toIntent(spec: IntentSpec): Intent? = when (spec) {
        is IntentSpec.ViewUri -> toIntent(spec)
        is IntentSpec.LaunchApp -> toIntent(spec)
    }
}
