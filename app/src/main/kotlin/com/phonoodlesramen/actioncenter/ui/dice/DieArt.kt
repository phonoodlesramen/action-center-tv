package com.phonoodlesramen.actioncenter.ui.dice

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.phonoodlesramen.actioncenter.dice.DiceTable

/**
 * The top bar's die (spec §4, the die): the face it last landed on, whether its tray is open,
 * and its button, where Back from the tray lands. The home provides it once your library is on
 * this TV ([LocalDie]); without one, the top bar has no die.
 */
@Stable
class DieHandle(face: Int = DiceTable.DEFAULT_FACE) {
    var face by mutableIntStateOf(face)
    var open by mutableStateOf(false)
    val button = FocusRequester()
}

val LocalDie = compositionLocalOf<DieHandle?> { null }

/**
 * A die's face, drawn as Material Symbols' `casino` draws its five (`ui/theme/Icons.kt`), so
 * the top bar's die sits with its icons and the tray's matches the Roll again button: a
 * rounded square over the middle three quarters of the box, pips on a three-by-three grid.
 */
object DieGeometry {
    /** Of the box: the square's inset, its corner, a pip's radius, and the grid's three lines. */
    const val INSET = 120f / 960f
    const val CORNER = 80f / 960f
    const val PIP = 60f / 960f
    val LINES = floatArrayOf(300f / 960f, 480f / 960f, 660f / 960f)

    /** Each face's pips as (column, row) on the grid. */
    fun pips(face: Int): List<Pair<Int, Int>> = when (face) {
        1 -> listOf(1 to 1)
        2 -> listOf(0 to 0, 2 to 2)
        3 -> listOf(0 to 0, 1 to 1, 2 to 2)
        4 -> listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2)
        5 -> listOf(0 to 0, 2 to 0, 1 to 1, 0 to 2, 2 to 2)
        else -> listOf(0 to 0, 2 to 0, 0 to 1, 2 to 1, 0 to 2, 2 to 2)
    }
}

/**
 * [face] in the middle of [area]'s largest square (from the draw area's top left): the [body],
 * and the pips in [pip], or punched through to what's behind when [pip] is null (draw it on
 * an offscreen layer).
 */
fun DrawScope.drawDie(face: Int, body: Color, pip: Color?, area: Size = size) {
    val box = area.minDimension
    val left = (area.width - box) / 2
    val top = (area.height - box) / 2
    drawRoundRect(
        color = body,
        topLeft = Offset(left + box * DieGeometry.INSET, top + box * DieGeometry.INSET),
        size = Size(box * (1 - 2 * DieGeometry.INSET), box * (1 - 2 * DieGeometry.INSET)),
        cornerRadius = CornerRadius(box * DieGeometry.CORNER),
    )
    DieGeometry.pips(face).forEach { (column, row) ->
        drawCircle(
            color = pip ?: Color.Black,
            radius = box * DieGeometry.PIP,
            center = Offset(left + box * DieGeometry.LINES[column], top + box * DieGeometry.LINES[row]),
            blendMode = if (pip == null) BlendMode.Clear else BlendMode.SrcOver,
        )
    }
}

/** The top bar's die: [face] in [color], its pips punched through like the symbol's. */
@Composable
fun DieGlyph(face: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawDie(face, color, pip = null)
    }
}
