package com.phonoodlesramen.actioncenter.ui.art

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.max

/**
 * Generated art for a title until real artwork arrives (spec §6): two colours plus a
 * motif, always the same for the same id. A card is never blank.
 */
object PlaceholderArt {
    enum class Motif { RINGS, STRIPES, SUN, DOTS, WAVES }

    data class Spec(val from: Color, val to: Color, val motif: Motif)

    private val palettes = listOf(
        Color(0xFF1D4E6B) to Color(0xFF0A1A26),
        Color(0xFF7A4A14) to Color(0xFF1D1208),
        Color(0xFF3B4A5C) to Color(0xFF0F141B),
        Color(0xFF284A6E) to Color(0xFF0B1522),
        Color(0xFF6E2A1C) to Color(0xFF241008),
        Color(0xFF3C2A4F) to Color(0xFF120C19),
        Color(0xFF5A3A1E) to Color(0xFF1A120B),
        Color(0xFF2F6A5A) to Color(0xFF0D211B),
        Color(0xFF3E4A2E) to Color(0xFF12170D),
        Color(0xFF6B1D1D) to Color(0xFF1C0707),
        Color(0xFF2B5C8A) to Color(0xFF0D1D2D),
        Color(0xFF6A2E6A) to Color(0xFF1D0C1D),
    )

    fun specFor(id: String): Spec {
        val hash = abs(id.hashCode().toLong())
        val (from, to) = palettes[(hash % palettes.size).toInt()]
        val motif = Motif.entries[((hash / palettes.size) % Motif.entries.size).toInt()]
        return Spec(from, to, motif)
    }
}

fun Modifier.placeholderArt(id: String): Modifier = drawWithCache {
    val spec = PlaceholderArt.specFor(id)
    val w = size.width
    val h = size.height
    val big = max(w, h)
    val base = Brush.linearGradient(listOf(spec.from, spec.to), start = Offset.Zero, end = Offset(w, h))
    val faint = Color.White.copy(alpha = 0.08f)
    val dots = if (spec.motif == PlaceholderArt.Motif.DOTS) {
        Path().apply {
            val step = 14.dp2px(this@drawWithCache.density)
            var y = step / 2
            while (y < h) {
                var x = step / 2
                while (x < w) {
                    addOval(Rect(Offset(x, y), 1.5f * this@drawWithCache.density))
                    x += step
                }
                y += step
            }
        }
    } else {
        null
    }
    onDrawBehind {
        drawRect(base)
        when (spec.motif) {
            PlaceholderArt.Motif.RINGS -> {
                val center = Offset(w * 0.72f, h * 0.40f)
                drawCircle(Color.White.copy(alpha = 0.12f), radius = big * 0.16f, center = center, style = Stroke(width = big * 0.01f))
                drawCircle(faint, radius = big * 0.29f, center = center, style = Stroke(width = big * 0.01f))
            }
            PlaceholderArt.Motif.STRIPES -> {
                val step = 24f * density
                var x = -h
                while (x < w) {
                    drawLine(Color.White.copy(alpha = 0.07f), Offset(x, h), Offset(x + h * 0.47f, 0f), strokeWidth = 6f * density, cap = StrokeCap.Butt)
                    x += step
                }
            }
            PlaceholderArt.Motif.SUN -> drawCircle(
                Brush.radialGradient(
                    0f to Color(0x8CFFE6B4), 0.2f to Color(0x2EFFC878), 1f to Color.Transparent,
                    center = Offset(w * 0.72f, h * 0.38f), radius = big * 0.42f,
                ),
                radius = big * 0.42f,
                center = Offset(w * 0.72f, h * 0.38f),
            )
            PlaceholderArt.Motif.DOTS -> drawPath(dots!!, Color.White.copy(alpha = 0.13f))
            PlaceholderArt.Motif.WAVES -> {
                drawOval(Color.White.copy(alpha = 0.14f), topLeft = Offset(w * 0.05f, h * 0.70f), size = Size(w, h * 0.9f))
                drawOval(faint, topLeft = Offset(-w * 0.2f, h * 0.78f), size = Size(w * 0.9f, h))
            }
        }
    }
}

private fun Int.dp2px(density: Float) = this * density
