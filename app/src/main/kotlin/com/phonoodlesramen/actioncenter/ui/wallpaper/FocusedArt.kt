package com.phonoodlesramen.actioncenter.ui.wallpaper

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * The art (a Plex path) of the card that last had focus, for Ambient light (L1). Cards
 * with art report it with [reportsArt]; the home screen provides one per screen.
 */
@Stable
class FocusedArt {
    var image: String? by mutableStateOf(null)
}

val LocalFocusedArt = staticCompositionLocalOf { FocusedArt() }

/** Tells [art] about [image] when this card, or anything inside it, gets focus. */
fun Modifier.reportsArt(art: FocusedArt, image: String?): Modifier =
    if (image == null) this else onFocusChanged { if (it.hasFocus) art.image = image }
