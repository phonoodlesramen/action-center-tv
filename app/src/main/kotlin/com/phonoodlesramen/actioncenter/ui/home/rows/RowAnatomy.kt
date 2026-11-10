package com.phonoodlesramen.actioncenter.ui.home.rows

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.theme.Layout

/**
 * The rows below the dashboard share one anatomy (L5, L10): 16 dp above, an eyebrow, 8 dp,
 * then a Foundation `LazyRow` of 160×90 dp cards 16 dp apart inside the 48 dp side margins.
 * Those are sizes on the 960 dp canvas: they follow the screen like the dashboard's grid,
 * so five cards fill the content width at every layout scale while text grows [U4].
 *
 * @param scale the screen's width over the canvas's, 1 at 100%.
 */
@Immutable
data class RowMetrics(val scale: Float = 1f) {
    val margin: Dp get() = (Layout.SAFE_SIDE * scale).dp
    val gap: Dp get() = (16 * scale).dp
    val cardWidth: Dp get() = (160 * scale).dp
    val cardHeight: Dp get() = (90 * scale).dp
    val spaceAbove: Dp get() = (16 * scale).dp
    val labelGap: Dp get() = (8 * scale).dp

    companion object {
        fun forWidth(screenWidth: Dp) = RowMetrics(screenWidth.value / Layout.CANVAS_WIDTH)
    }
}

/** The home screen provides the metrics for its width; elsewhere they're the canvas's own. */
val LocalRowMetrics = staticCompositionLocalOf { RowMetrics() }

/** A card's size in a row: 160×90 dp on the canvas. */
fun Modifier.rowCard(metrics: RowMetrics): Modifier = size(metrics.cardWidth, metrics.cardHeight)

/**
 * One row: its label, then its cards in a `LazyRow` with `focusRestorer()`, so coming back
 * to the row returns to the card you left. A `LazyRow` is already a focus group (its
 * scrollable is one), so it takes no `focusGroup()` of its own: a second group outside
 * it would hide the cards from the restorer, which would then land on the first card
 * (`HomeFocusTest` catches it).
 */
@Composable
fun CardRow(
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val metrics = LocalRowMetrics.current
    Column(modifier.fillMaxWidth().padding(top = metrics.spaceAbove)) {
        RowLabel(title, Modifier.padding(start = metrics.margin, bottom = metrics.labelGap))
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(horizontal = metrics.margin),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
            content = content,
        )
    }
}

/**
 * A row's eyebrow, drawn only while it's wholly on screen. Text is never cut by the
 * screen's edge (TV-OV), so a label half in view stays blank until its row scrolls in;
 * art below it may peek.
 */
@Composable
fun RowLabel(text: String, modifier: Modifier = Modifier) {
    var whole by remember { mutableStateOf(true) }
    Eyebrow(
        text = text,
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val top = coordinates.positionInRoot().y
                whole = top >= 0f && top + coordinates.size.height <= coordinates.findRootCoordinates().size.height
            }
            .graphicsLayer { alpha = if (whole) 1f else 0f },
    )
}

/**
 * How a row scrolls to its focused card: as little as possible, keeping the card [margin]
 * px inside the row's edges. Cards stay put until focus passes the fifth, then the row
 * moves one card at a time and stays on the grid. Compose's TV default instead pivots
 * the focused card to 30% of the row (foundation `BringIntoViewSpec.android.kt`).
 */
class MarginBringIntoViewSpec(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leading = offset - margin
        val trailing = offset + size + margin - containerSize
        return when {
            leading >= 0f && trailing <= 0f -> 0f
            size + 2 * margin >= containerSize -> leading
            leading < 0f -> leading
            else -> trailing
        }
    }
}
