package com.phonoodlesramen.actioncenter.ui.theme

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import com.phonoodlesramen.actioncenter.ui.home.weatherIcon
import com.phonoodlesramen.actioncenter.ui.player.PlayerIcons
import com.phonoodlesramen.actioncenter.weather.Sky
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IconsTest {
    /** Every icon [AppIcons] and [PlayerIcons] hold, found by their getters so a new one is checked too. */
    private val icons: List<ImageVector> = listOf(AppIcons, PlayerIcons).flatMap { holder ->
        holder.javaClass.methods
            .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .map { it.invoke(holder) as ImageVector }
    }

    @Test
    fun `every icon is a Material Symbol's 960-unit box drawn at 24 dp`() {
        assertTrue("found ${icons.size}", icons.size >= 38)
        assertEquals(icons.size, icons.map { it.name }.distinct().size)
        icons.forEach { icon ->
            assertEquals(icon.name, SYMBOL_BOX, icon.viewportWidth)
            assertEquals(icon.name, SYMBOL_BOX, icon.viewportHeight)
            assertEquals(icon.name, 24.dp, icon.defaultWidth)
            assertEquals(icon.name, 24.dp, icon.defaultHeight)
        }
    }

    @Test
    fun `every icon's path lands inside its box and fills most of it`() {
        icons.forEach { icon ->
            val group = icon.root.single() as VectorGroup
            val path = group.single() as VectorPath
            assertEquals(icon.name, 0f, group.rotation)
            val (left, top, right, bottom) = bounds(icon.name, path.pathData).let { (l, t, r, b) ->
                listOf(
                    l * group.scaleX + group.translationX,
                    t * group.scaleY + group.translationY,
                    r * group.scaleX + group.translationX,
                    b * group.scaleY + group.translationY,
                )
            }
            val inside = left >= -EDGE && top >= -EDGE && right <= SYMBOL_BOX + EDGE && bottom <= SYMBOL_BOX + EDGE
            assertTrue("${icon.name} spans ($left, $top)–($right, $bottom)", inside)
            assertTrue("${icon.name} is ${right - left}×${bottom - top}", maxOf(right - left, bottom - top) >= SYMBOL_BOX / 2)
        }
    }

    @Test
    fun `a partly cloudy night has its own icon, with the moon`() {
        assertSame(AppIcons.PartlyCloudyNight, weatherIcon(Sky.PARTLY_CLOUDY, isDay = false))
        assertSame(AppIcons.PartlyCloudy, weatherIcon(Sky.PARTLY_CLOUDY, isDay = true))
    }

    /**
     * The box round a path's points and control points (left, top, right, bottom). A quadratic
     * stays inside its control points, so this holds the drawn shape. Material Symbols use only
     * M, L, H, V, Q, T and Z.
     */
    private fun bounds(name: String, nodes: List<PathNode>): List<Float> {
        val xs = mutableListOf<Float>()
        val ys = mutableListOf<Float>()
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        var controlX = 0f
        var controlY = 0f
        var lastWasQuad = false
        fun control(cx: Float, cy: Float) {
            controlX = cx
            controlY = cy
            xs += cx
            ys += cy
        }
        nodes.forEach { node ->
            var quad = false
            when (node) {
                is PathNode.Close -> { x = startX; y = startY }
                is PathNode.MoveTo -> { x = node.x; y = node.y; startX = x; startY = y }
                is PathNode.RelativeMoveTo -> { x += node.dx; y += node.dy; startX = x; startY = y }
                is PathNode.LineTo -> { x = node.x; y = node.y }
                is PathNode.RelativeLineTo -> { x += node.dx; y += node.dy }
                is PathNode.HorizontalTo -> x = node.x
                is PathNode.RelativeHorizontalTo -> x += node.dx
                is PathNode.VerticalTo -> y = node.y
                is PathNode.RelativeVerticalTo -> y += node.dy
                is PathNode.QuadTo -> { control(node.x1, node.y1); x = node.x2; y = node.y2; quad = true }
                is PathNode.RelativeQuadTo -> { control(x + node.dx1, y + node.dy1); x += node.dx2; y += node.dy2; quad = true }
                is PathNode.ReflectiveQuadTo -> {
                    if (lastWasQuad) control(2 * x - controlX, 2 * y - controlY) else control(x, y)
                    x = node.x
                    y = node.y
                    quad = true
                }
                is PathNode.RelativeReflectiveQuadTo -> {
                    if (lastWasQuad) control(2 * x - controlX, 2 * y - controlY) else control(x, y)
                    x += node.dx
                    y += node.dy
                    quad = true
                }
                else -> fail("$name has a ${node::class.simpleName}, which Material Symbols don't use")
            }
            xs += x
            ys += y
            lastWasQuad = quad
        }
        return listOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private companion object {
        /** Rounding in the SVGs' numbers. */
        const val EDGE = 0.5f
    }
}
