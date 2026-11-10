package com.phonoodlesramen.actioncenter.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt

/**
 * The TV safe area (48 dp left/right, 27 dp top/bottom on the 960×540 canvas) as a
 * fraction of the screen, so it stays put when the layout scale changes density [U4].
 */
fun Modifier.safeArea(): Modifier = layout { measurable, constraints ->
    val horizontal = (constraints.maxWidth * SAFE_HORIZONTAL).roundToInt()
    val vertical = (constraints.maxHeight * SAFE_VERTICAL).roundToInt()
    val placeable = measurable.measure(constraints.offset(-2 * horizontal, -2 * vertical))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(horizontal, vertical) }
}

private const val SAFE_HORIZONTAL = 48f / 960f
private const val SAFE_VERTICAL = 27f / 540f
