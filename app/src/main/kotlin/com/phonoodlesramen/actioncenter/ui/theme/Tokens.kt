package com.phonoodlesramen.actioncenter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Colour tokens from docs/design/ui-spec.md §2. */
object Palette {
    val Background = Color(0xFF0E1318)
    val Surface = Color(0xFF161D25)
    val Surface2 = Color(0xFF1F2832)
    val Surface3 = Color(0xFF2A3542)
    val Text = Color(0xFFE6EDF3)
    val TextDim = Color(0xFF9AA7B4)
    val TextFaint = Color(0xFF6B7886)
    val AccentInk = Color(0xFF06202B)
    val Scrim = Color(0xFF080B0F)
    val WarmDark = Color(0xFF2B1F17)
    val WarmLight = Color(0xFF3A2A1D)
    val WarmText = Color(0xFFF2D6B8)
    val WarmEyebrow = Color(0xFFE7B98A)
    val WarmButton = Color(0xFFF6C08F)
    val WarmButtonInk = Color(0xFF2B1A0D)
    val Good = Color(0xFF81C784)
    val Bad = Color(0xFFE57373)
    /** Something to look at, not an outage: the top bar's "Plex sync failed". */
    val Caution = Color(0xFFFFB74D)
    val Warning = Color(0xFF5C1F1F)
    val SheetBackground = Color(0xFF121920)

    /** Settings › Look offers these [U3]; cyan is the default. */
    val Accents = listOf(
        Color(0xFF4FC3F7), // cyan
        Color(0xFFFFB74D), // amber
        Color(0xFFB39DDB), // violet
        Color(0xFF81C784), // green
        Color(0xFFFF8A65), // coral
    )
    val DefaultAccent = Accents.first()

    /** Late night dims the accent toward slate (spec §2). */
    fun lateNight(accent: Color): Color = lerp(accent, Color(0xFF4A5563), 0.38f)
}

/**
 * Geometry from spec §1 on the 960×540 canvas. Rows and columns are weights (their dp
 * sizes on the 864×486 safe area), so they stay proportional at every layout scale.
 *
 * Phase 2.5 (L5): Rows A and B give up 30 dp (236→224, 174→156), so the first row below
 * the dashboard peeks into the bottom margin: its label whole, the top of its art cut.
 */
object Layout {
    val CardRadius = 10.dp
    val TileRadius = 8.dp

    /** The design canvas and its safe margins, in dp (spec §1). */
    const val CANVAS_WIDTH = 960f
    const val CANVAS_HEIGHT = 540f
    const val SAFE_SIDE = 48f
    const val SAFE_TOP = 27f

    const val TOP_BAR_WEIGHT = 44f
    const val ROW_A_WEIGHT = 224f
    const val ROW_B_WEIGHT = 156f
    const val GAP_WEIGHT = 16f

    /** The dashboard below the top margin: 456 of the canvas's 540 dp, so 57 dp are left for the peek. */
    const val DASHBOARD_WEIGHT = TOP_BAR_WEIGHT + GAP_WEIGHT + ROW_A_WEIGHT + GAP_WEIGHT + ROW_B_WEIGHT

    /** The 7/12 + 5/12 columns of the 12-column grid with 16 dp gutters. */
    const val LEFT_WEIGHT = 497.33f
    const val RIGHT_WEIGHT = 350.67f

    /** Row A's right column: the lens card over Quick Watch. The lens card keeps its line from 112 dp up. */
    const val LENS_CARD_WEIGHT = 116f
    const val QUICK_WATCH_WEIGHT = 92f
}

/** Settings › Look layout scale [U4]. */
object LayoutScale {
    const val MIN = 0.90f
    const val DEFAULT = 1.00f
    const val MAX = 1.15f
    val Options = listOf(0.90f, 1.00f, 1.15f)

    fun clamp(scale: Float): Float = scale.coerceIn(MIN, MAX)
}
