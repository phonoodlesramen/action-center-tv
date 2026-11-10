package com.phonoodlesramen.actioncenter.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle

/**
 * A soft dark halo under text that sits straight on a wallpaper (the operator's choice,
 * 10/27/2026). Still wallpapers may be as bright as primary text allows at 4.5:1, so the
 * dimmer labels on them (the top bar's status, eyebrows, captions) get this halo.
 *
 * Null on Midnight and off the home, which leaves those screens exactly as they were.
 */
val LocalWallpaperTextShadow = staticCompositionLocalOf<Shadow?> { null }

/** Barely offset, widely blurred and near-black: it reads as a halo, not a drop shadow. */
val WallpaperTextShadow = Shadow(color = Color.Black.copy(alpha = 0.9f), offset = Offset(0f, 2f), blurRadius = 12f)

/** This style with the wallpaper's halo, when the home has one. */
@Composable
@ReadOnlyComposable
fun TextStyle.onWallpaper(): TextStyle = LocalWallpaperTextShadow.current?.let { copy(shadow = it) } ?: this
