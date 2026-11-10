package com.phonoodlesramen.actioncenter.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** The current accent (spec §2): focus ring, focused buttons, progress, reason icon. */
val LocalAccent = staticCompositionLocalOf { Palette.DefaultAccent }

/**
 * tv-material's dark theme and type scale with the chosen accent. [layoutScale]
 * (Settings › Look, 90-115%) scales density, so text and icons grow while the grid
 * stays proportional to the screen [U4].
 */
@Composable
fun ActionCenterTheme(
    accent: Color = Palette.DefaultAccent,
    layoutScale: Float = LayoutScale.DEFAULT,
    content: @Composable () -> Unit,
) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * LayoutScale.clamp(layoutScale), base.fontScale),
        LocalAccent provides accent,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = accent,
                onPrimary = Palette.AccentInk,
                background = Palette.Background,
                surface = Palette.Surface,
                onSurface = Palette.Text,
                onSurfaceVariant = Palette.TextDim,
            ),
        ) {
            // Text outside a Surface (titles over art, captions) is light by default.
            CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
        }
    }
}
