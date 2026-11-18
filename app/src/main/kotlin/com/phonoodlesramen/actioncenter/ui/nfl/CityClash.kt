package com.phonoodlesramen.actioncenter.ui.nfl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.phonoodlesramen.actioncenter.nfl.CityArt
import com.phonoodlesramen.actioncenter.nfl.Clash
import com.phonoodlesramen.actioncenter.nfl.NflCities
import com.phonoodlesramen.actioncenter.nfl.NflCityImages
import com.phonoodlesramen.actioncenter.nfl.NflGame
import com.phonoodlesramen.actioncenter.nfl.NflTeam
import com.phonoodlesramen.actioncenter.nfl.NflView
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/** Where city pictures come from: the app's own ([NflCityImages.Bundled]); previews and tests have none unless they say. */
val LocalNflCities = staticCompositionLocalOf { NflCityImages.None }

/** How a game's two cities meet: [Clash.seam], and whose city is dark ([Clash.dimmed]). */
@Immutable
data class ClashLook(val seam: Clash.Seam = Clash.Seam.FADE, val dimmed: String? = null) {
    companion object {
        fun of(game: NflGame, hidden: Boolean) = ClashLook(Clash.seam(game), Clash.dimmed(game, hidden))
    }
}

/**
 * Two cities (ui-spec "Two cities"): the away team's city on the left and the home team's on the
 * right, each a picture shipped with the app, tinted towards its team's colour at its outer edge, meeting in the middle across
 * the [look]'s seam: a fade before kickoff, a zig-zag once it's on (the clash). Without a picture,
 * a side is its team's colour washed over whatever's behind (a card's own surface), as the bands
 * were before.
 *
 * A zig-zag gets a light line along it where nothing's written over the middle: [line] is the
 * part of the height it's drawn in, fading in and out at its ends; null draws none, and the
 * cities meet at the cut alone. Each side is a little more than half the width, so the teeth
 * have a city behind them; each picture is decoded at that size times [detail], never larger.
 */
@Composable
fun CityClash(
    away: NflTeam?,
    awayAbbr: String,
    home: NflTeam?,
    homeAbbr: String,
    look: ClashLook,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    /** A zig-zag's tooth, top to top; the height is split into whole teeth near this. */
    tooth: Dp = 22.dp,
    line: ClosedFloatingPointRange<Float>? = 0f..1f,
    /** How much of its drawn size a picture is decoded at: a backdrop stretched to the screen takes half. */
    detail: Float = 1f,
    /** How dark the pictures are kept, for the words over them. */
    shade: Float = SHADE,
) {
    BoxWithConstraints(modifier.clip(shape)) {
        val density = LocalDensity.current
        val px = with(density) { Size((maxWidth.toPx() * SIDE * detail).coerceAtLeast(1f), (maxHeight.toPx() * detail).coerceAtLeast(1f)) }
        val side = Modifier.fillMaxHeight().fillMaxWidth(SIDE)
        CitySide(awayAbbr, away, fromLeft = true, dim = look.dimmed == awayAbbr, px, shade, side.align(Alignment.CenterStart))
        Box(Modifier.fillMaxSize().seamMask(look.seam, tooth)) {
            CitySide(homeAbbr, home, fromLeft = false, dim = look.dimmed == homeAbbr, px, shade, side.align(Alignment.CenterEnd))
        }
        if (look.seam == Clash.Seam.ZIGZAG && line != null) SeamLine(tooth, line, Modifier.fillMaxSize())
    }
}

/** A game's two cities, its seam and its dark side by [ClashLook.of]. */
@Composable
fun CityClash(
    game: NflGame,
    view: NflView,
    nowMillis: Long,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    tooth: Dp = 22.dp,
    line: ClosedFloatingPointRange<Float>? = 0f..1f,
    detail: Float = 1f,
    shade: Float = SHADE,
) = CityClash(
    view.team(game.away), game.away, view.team(game.home), game.home,
    ClashLook.of(game, view.hidden(game, nowMillis)),
    modifier, shape, tooth, line, detail, shade,
)

/** How much of the width a side covers: half, and room for the teeth and the fade. */
private const val SIDE = 0.62f

/** How dark the pictures are kept by default: the band's words sit on them. */
internal const val SHADE = 0.5f

/** How much darker the loser's city goes once it's over. */
private const val DIM = 0.38f

/** The fade's width, a fifth of the band's, centred. */
private const val FADE = 0.2f

/** A zig-zag tooth's reach either side of the middle, as a share of its height. */
private const val REACH = 0.32f

/** The home side kept to the right of the middle: cut along the zig-zag, or faded in across it. */
private fun Modifier.seamMask(seam: Clash.Seam, tooth: Dp): Modifier = when (seam) {
    Clash.Seam.ZIGZAG -> drawWithContent {
        val path = zigzag(size, tooth.toPx()).apply {
            lineTo(size.width, size.height)
            lineTo(size.width, 0f)
            close()
        }
        clipPath(path) { this@drawWithContent.drawContent() }
    }
    Clash.Seam.FADE -> graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(0.5f - FADE / 2 to Color.Transparent, 0.5f + FADE / 2 to Color.Black), blendMode = BlendMode.DstIn)
    }
}

/**
 * The zig-zag down the middle, top to bottom: whole teeth near [tooth] tall, each reaching
 * [REACH] of its height either side, starting and ending to the left.
 */
internal fun zigzag(size: Size, tooth: Float): Path {
    val teeth = (size.height / tooth).toInt().coerceAtLeast(2)
    val step = size.height / teeth
    val reach = step * REACH
    val x = size.width / 2
    return Path().apply {
        moveTo(x - reach, 0f)
        for (half in 1..teeth * 2) {
            lineTo(if (half % 2 == 1) x + reach else x - reach, step / 2 * half)
        }
    }
}

/** The light line along the zig-zag, over a soft dark one so it holds on a bright picture, drawn in [band] of the height. */
@Composable
private fun SeamLine(tooth: Dp, band: ClosedFloatingPointRange<Float>, modifier: Modifier) {
    Box(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            val path = zigzag(size, tooth.toPx())
            drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(width = 5.dp.toPx(), join = StrokeJoin.Miter))
            drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Miter))
            if (band.start > 0f || band.endInclusive < 1f) {
                val edge = LINE_EDGE
                drawRect(
                    Brush.verticalGradient(
                        (band.start - edge).coerceAtLeast(0f) to Color.Transparent,
                        band.start to Color.Black,
                        band.endInclusive to Color.Black,
                        (band.endInclusive + edge).coerceAtMost(1f) to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        },
    )
}

/** How far the line takes to fade in and out at the ends of its band. */
private const val LINE_EDGE = 0.06f

/**
 * One side: the city's picture, a little desaturated, with the dark of the surface over it and
 * the team's colour strongest at the outer edge; darker still when [dim]. Without a picture, the
 * team's colour alone.
 */
@Composable
private fun CitySide(abbr: String, team: NflTeam?, fromLeft: Boolean, dim: Boolean, px: Size, shade: Float, modifier: Modifier) {
    val image = LocalNflCities.current.image(abbr)
    val colour = NflColors.wash(team)
    Box(modifier) {
        if (image != null) {
            CityImage(image, NflCities.art[abbr], px, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Palette.Background.copy(alpha = shade)))
            Box(Modifier.fillMaxSize().background(tint(colour, fromLeft, outer = TINT_OUTER, inner = TINT_INNER)))
        } else {
            Box(Modifier.fillMaxSize().background(tint(colour, fromLeft, outer = WASH_OUTER, inner = WASH_INNER)))
        }
        if (dim) Box(Modifier.fillMaxSize().background(Palette.Background.copy(alpha = DIM)))
    }
}

/** A picture's team colour, outer edge and inner. */
private const val TINT_OUTER = 0.42f
private const val TINT_INNER = 0.1f

/** Without a picture, the colour's a little stronger, as the bands' washes were. */
private const val WASH_OUTER = 0.5f
private const val WASH_INNER = 0.12f

private fun tint(colour: Color, fromLeft: Boolean, outer: Float, inner: Float): Brush =
    if (fromLeft) {
        Brush.horizontalGradient(0f to colour.copy(alpha = outer), 1f to colour.copy(alpha = inner))
    } else {
        Brush.horizontalGradient(0f to colour.copy(alpha = inner), 1f to colour.copy(alpha = outer))
    }

/** The picture, cropped to fill with its interest in view, at 60% colour so the cities sit together. */
@Composable
private fun CityImage(image: Any, art: CityArt?, px: Size, modifier: Modifier) {
    val context = LocalContext.current
    val width = px.width.toInt()
    val height = px.height.toInt()
    AsyncImage(
        model = remember(image, width, height) { ImageRequest.Builder(context).data(image).size(width, height).build() },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment((art?.focusX ?: 0.5f) * 2 - 1, (art?.focusY ?: 0.5f) * 2 - 1),
        colorFilter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(SATURATION) }) },
        modifier = modifier,
    )
}

private const val SATURATION = 0.6f
