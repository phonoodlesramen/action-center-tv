package com.phonoodlesramen.actioncenter.setup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.phonoodlesramen.actioncenter.HomeActivity

/**
 * HomeWatch's "Configure" in TV Settings › Accessibility (`android:settingsActivity` in
 * `home_watch_service.xml`): opens Settings › Setup in the home, then gets out of the way.
 * Exported so TV Settings can start it; all it can do is show Setup. A test checks the
 * service names it, since a missing one crashes TV Settings (Projectivy's miproja1#673).
 */
class OpenSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, HomeActivity::class.java).setAction(ACTION_OPEN_SETUP))
        finish()
    }

    companion object {
        /** What the home opens Settings › Setup for. */
        const val ACTION_OPEN_SETUP = "com.phonoodlesramen.actioncenter.action.OPEN_SETUP"
    }
}
