package com.phonoodlesramen.actioncenter.setup

import android.Manifest

/**
 * How the remote's Home button reaches Action Center on this TV (setup without a computer).
 */
enum class HomeRoute {
    /** Home opens Action Center itself: the stock launcher is off (the computer route). */
    HOME,

    /** HomeWatch, the accessibility service, takes Home to Action Center. */
    HOMEWATCH,

    /** Neither. */
    OFF,

    /** HomeWatch was on and Android has turned it off (an update, a TV's "auto-start" guard). */
    WENT_OFF,
}

/** The permissions Settings › Setup asks for, each with what it's for. */
enum class SetupPermission {
    /** Continue in your apps: Android's Watch Next (L9). */
    TV_LISTINGS,

    /** Your wallpapers and app banners from the TV's storage (L2, L7). */
    PICTURES;

    /** What to ask Android for on [sdk]: Android 13 split storage into images and video. */
    fun permissions(sdk: Int): List<String> = when (this) {
        TV_LISTINGS -> listOf(READ_TV_LISTINGS)
        PICTURES -> if (sdk >= TIRAMISU) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    companion object {
        /** `android.permission.READ_TV_LISTINGS`, spelt out: it isn't in [Manifest.permission]. */
        const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        private const val TIRAMISU = 33
    }
}

/**
 * Where setup stands on this TV, read on every resume ([SetupMonitor]). The defaults are a TV
 * that's all set up, so previews, tests and screenshots show no setup chip.
 */
data class SetupStatus(
    val home: HomeRoute = HomeRoute.HOME,
    val tvListings: Boolean = true,
    val pictures: Boolean = true,
    val usageAccess: Boolean = true,
    /** This TV opens Accessibility from an app; the SHIELD doesn't (its link is a stub). */
    val accessibilityLink: Boolean = true,
    /** Settings › Setup › Show in the top bar is off, on this TV. */
    val chipHidden: Boolean = false,
) {
    val homeDone: Boolean get() = home == HomeRoute.HOME || home == HomeRoute.HOMEWATCH

    /**
     * The steps still to do: the chip's "2 left". Plex isn't one of them: until it's linked,
     * the home's own first card asks for it.
     */
    val left: Int get() = listOf(homeDone, tvListings, pictures, usageAccess).count { !it }

    /** What the top bar says, if anything: for grown-ups, until it's done or hidden. */
    fun chip(kid: Boolean): SetupChip? = when {
        kid || chipHidden -> null
        home == HomeRoute.WENT_OFF -> SetupChip.HomeButtonOff
        left > 0 -> SetupChip.FinishSetup(left)
        else -> null
    }
}

/** The top bar's setup chip: it opens Settings › Setup. */
sealed interface SetupChip {
    val label: String

    data class FinishSetup(val left: Int) : SetupChip {
        override val label: String get() = "Finish setup · $left left"
    }

    data object HomeButtonOff : SetupChip {
        override val label: String get() = "Home button is off"
    }
}
