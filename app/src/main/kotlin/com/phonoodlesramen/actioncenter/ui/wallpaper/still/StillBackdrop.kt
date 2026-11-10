package com.phonoodlesramen.actioncenter.ui.wallpaper.still

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/** Draws a wallpaper's frosted copy into a canvas of the wallpaper's [Size]. */
typealias BackdropPainter = DrawScope.(wallpaper: Size) -> Unit

/**
 * Frosted glass (L4): what a still wallpaper publishes for the cards over it. Each glass
 * card draws [painter] shifted by its own place on the screen, so it shows the blurred
 * picture that's behind it. Null over Midnight and the moving styles: glass is a tint.
 */
@Stable
class StillBackdrop {
    /** Where the wallpaper is, in root coordinates. */
    var bounds: Rect by mutableStateOf(Rect.Zero)
        private set

    var painter: BackdropPainter? by mutableStateOf(null)
        private set

    internal fun place(bounds: Rect) {
        if (bounds != this.bounds) this.bounds = bounds
    }

    internal fun show(painter: BackdropPainter) {
        this.painter = painter
    }

    internal fun hide(painter: BackdropPainter) {
        if (this.painter === painter) this.painter = null
    }
}

/**
 * Publishes [painter] to [backdrop]'s glass cards while this wallpaper is on screen, and
 * tells them where the wallpaper is. A null [backdrop] (Solid cards) does nothing.
 * [painter] should be remembered: it reads its pictures from state, so the cards redraw
 * when they change.
 */
fun Modifier.publishesBackdrop(backdrop: StillBackdrop?, painter: BackdropPainter): Modifier =
    if (backdrop == null) this else this then PublishElement(backdrop, painter)

private data class PublishElement(val backdrop: StillBackdrop, val painter: BackdropPainter) : ModifierNodeElement<PublishNode>() {
    override fun create() = PublishNode(backdrop, painter)

    override fun InspectorInfo.inspectableProperties() {
        name = "publishesBackdrop"
    }

    override fun update(node: PublishNode) {
        if (node.isAttached) node.backdrop.hide(node.painter)
        node.backdrop = backdrop
        node.painter = painter
        if (node.isAttached) backdrop.show(painter)
    }
}

private class PublishNode(var backdrop: StillBackdrop, var painter: BackdropPainter) :
    Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onAttach() = backdrop.show(painter)

    override fun onDetach() = backdrop.hide(painter)

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) = backdrop.place(coordinates.boundsInRoot())
}
