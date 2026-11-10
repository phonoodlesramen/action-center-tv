package com.phonoodlesramen.actioncenter.ui.wallpaper

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.Legibility
import com.phonoodlesramen.actioncenter.ui.wallpaper.still.StillBackdrop
import com.phonoodlesramen.actioncenter.wallpaper.CardStyle
import com.phonoodlesramen.actioncenter.wallpaper.WallpaperPrefs

/** Draws the background of a card without art (Quick Watch, the Guide, top bar chips): L4. */
@Stable
interface CardSurface {
    fun Modifier.surface(shape: Shape, color: Color): Modifier

    /** Where still wallpapers publish their frosted copy for these cards; only Frosted glass has one. */
    val backdrop: StillBackdrop? get() = null
}

object SolidCardSurface : CardSurface {
    override fun Modifier.surface(shape: Shape, color: Color) = clip(shape).background(color)
}

val LocalCardSurface = staticCompositionLocalOf<CardSurface> { SolidCardSurface }

/** A card's background from the screen's [CardSurface]: `Modifier.cardSurface(LocalCardSurface.current, shape)`. */
fun Modifier.cardSurface(surface: CardSurface, shape: Shape, color: Color = Palette.Surface): Modifier =
    with(surface) { surface(shape, color) }

/**
 * The card surface for [style] over [scene]'s wallpaper. Frosted glass draws the still
 * wallpaper's blurred copy behind each card, then the user's dim, then the card's colour
 * as a tint; over Midnight and the moving styles it's the tint alone.
 */
@Composable
fun rememberCardSurface(style: CardStyle, scene: WallpaperScene): CardSurface {
    val backdrop = remember { StillBackdrop() }
    val dim = scene.prefs.dim.coerceIn(0f, WallpaperPrefs.MAX_DIM)
    return remember(style, dim, backdrop) {
        when (style) {
            CardStyle.SOLID -> SolidCardSurface
            CardStyle.GLASS -> FrostedGlass(backdrop, dim)
        }
    }
}

/**
 * Frosted glass (L4). Text stays readable (spec §6): a still wallpaper is at most
 * [Legibility.Limit] bright, and [GLASS_TINT] of the card's colour over that brings it
 * down to [Legibility.DimLimit], where the dim text inside the card keeps 4.5:1.
 */
class FrostedGlass(override val backdrop: StillBackdrop, private val dim: Float) : CardSurface {
    override fun Modifier.surface(shape: Shape, color: Color) = clip(shape) then GlassElement(backdrop, color, dim, shape)

    companion object {
        /**
         * The card's colour over the frosted picture: the least that keeps dim text at
         * 4.5:1 over the brightest still wallpaper, for every card colour, and never
         * under 0.55, so the card still reads as a pane.
         */
        val GLASS_TINT: Float = maxOf(
            0.55f,
            listOf(Palette.Surface, Palette.Surface2).maxOf { Legibility.alphaFor(listOf(Legibility.BrightestStill), Legibility.DimLimit, it) },
        )

        /** The card's colour alone, over Midnight and the moving styles. */
        const val PLAIN_TINT = 0.72f

        /** A hairline along the edge, so glass reads as a pane. */
        const val EDGE_ALPHA = 0.08f
    }
}

private data class GlassElement(
    val backdrop: StillBackdrop,
    val color: Color,
    val dim: Float,
    val shape: Shape,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(backdrop, color, dim, shape)

    override fun InspectorInfo.inspectableProperties() {
        name = "frostedGlass"
        properties["color"] = color
        properties["dim"] = dim
        properties["shape"] = shape
    }

    override fun update(node: GlassNode) {
        node.backdrop = backdrop
        node.color = color
        node.dim = dim
        node.shape = shape
        node.invalidateDraw()
    }
}

/**
 * Draws the wallpaper's frosted copy at this card's place on the screen. The backdrop's
 * picture and bounds are state, read while drawing, so the card redraws when they change;
 * a move (the home scrolling) redraws it too.
 */
private class GlassNode(
    var backdrop: StillBackdrop,
    var color: Color,
    var dim: Float,
    var shape: Shape,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var lastPosition = Offset.Unspecified

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val position = coordinates.positionInRoot()
        if (position != lastPosition) {
            lastPosition = position
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val painter = backdrop.painter
        if (painter == null) {
            drawRect(color.copy(alpha = FrostedGlass.PLAIN_TINT))
        } else {
            val here = requireLayoutCoordinates().positionInRoot()
            val wallpaper = backdrop.bounds
            translate(wallpaper.left - here.x, wallpaper.top - here.y) { painter(wallpaper.size) }
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
            drawRect(color.copy(alpha = FrostedGlass.GLASS_TINT))
            drawOutline(
                outline = shape.createOutline(size, layoutDirection, this),
                color = Color.White.copy(alpha = FrostedGlass.EDGE_ALPHA),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        drawContent()
    }
}
