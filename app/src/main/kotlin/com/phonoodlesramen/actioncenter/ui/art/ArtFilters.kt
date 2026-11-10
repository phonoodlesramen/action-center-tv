package com.phonoodlesramen.actioncenter.ui.art

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

/** Colour treatments for artwork (spec §2, §4). */
object ArtFilters {
    /** Late night: artwork at 75% brightness and 80% saturation. */
    val LateNight: ColorMatrix = ColorMatrix().apply { setToSaturation(0.8f) }.also { it *= brightness(0.75f) }

    /** Throwback and Classic lenses: sepia 0.55 at 80% brightness. */
    val Sepia: ColorMatrix = sepia(0.55f).also { it *= brightness(0.8f) }

    fun combine(first: ColorMatrix?, second: ColorMatrix?): ColorMatrix? = when {
        first == null -> second
        second == null -> first
        else -> ColorMatrix(first.values.copyOf()).also { it *= second }
    }

    private fun brightness(amount: Float) = ColorMatrix().apply { setToScale(amount, amount, amount, 1f) }

    /** The CSS `sepia()` matrix. */
    private fun sepia(amount: Float): ColorMatrix {
        val k = 1f - amount
        return ColorMatrix(
            floatArrayOf(
                0.393f + 0.607f * k, 0.769f - 0.769f * k, 0.189f - 0.189f * k, 0f, 0f,
                0.349f - 0.349f * k, 0.686f + 0.314f * k, 0.168f - 0.168f * k, 0f, 0f,
                0.272f - 0.272f * k, 0.534f - 0.534f * k, 0.131f + 0.869f * k, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
}

/** Draws everything after it in the chain through [matrix]. Null draws as is. */
fun Modifier.colorMatrix(matrix: ColorMatrix?): Modifier = if (matrix == null) {
    this
} else {
    drawWithCache {
        val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(matrix) }
        onDrawWithContent {
            drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), paint) }
            drawContent()
            drawIntoCanvas { it.restore() }
        }
    }
}

/** A title's art (placeholder until Phase 2), dimmed in late night. */
fun Modifier.titleArt(id: String, lateNight: Boolean, treatment: ColorMatrix? = null): Modifier =
    colorMatrix(ArtFilters.combine(treatment, if (lateNight) ArtFilters.LateNight else null)).placeholderArt(id)
