package com.phonoodlesramen.actioncenter.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.phonoodlesramen.actioncenter.apps.LocalCustomBanners
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.ui.art.ArtFilters
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import com.phonoodlesramen.actioncenter.ui.art.colorMatrix
import com.phonoodlesramen.actioncenter.ui.theme.ChannelGlyphIcons
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.io.File
import kotlin.math.abs

/** A guide channel's icon, [size] square, as [ChannelMark] below draws it. */
@Composable
fun ChannelMark(channel: GuideChannel, size: Dp, lateNight: Boolean, modifier: Modifier = Modifier) =
    ChannelMark(channel.id, channel.name, channel.glyph, channel.logo, size, lateNight, modifier)

/**
 * A guide channel's icon (I1-I7), [size] square, on a tile in its glyph's muted tone (I2):
 * - the image in the Icons folder's `Channels` subfolder named after it (I3), fitted;
 * - else its [logo], a one-show channel's show poster, cropped;
 * - under either, and alone without them, its [glyph], else its [monogram].
 *
 * Images are decoded at the tile's own size and fade in over the glyph or monogram, so the
 * tile is never blank (spec §6). Late night dims it with the rest of the art.
 */
@Composable
fun ChannelMark(
    id: String,
    name: String,
    glyph: String?,
    logo: String?,
    size: Dp,
    lateNight: Boolean,
    modifier: Modifier = Modifier,
) {
    val icon = ChannelGlyphIcons.of(glyph)
    val banners by LocalCustomBanners.current.state.collectAsState()
    val file = remember(banners, name) { banners.channelIconFor(name) }
    val tone = ChannelTones.of(if (icon != null) glyph else null, id)
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * CORNER))
            .colorMatrix(if (lateNight) ArtFilters.LateNight else null)
            .background(Brush.linearGradient(listOf(lerp(tone, Color.White, 0.10f), lerp(tone, Color.Black, 0.18f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(size * GLYPH))
        } else {
            val letters = monogram(name)
            val share = when (letters.length) {
                1 -> 0.52f
                2 -> 0.42f
                else -> 0.34f
            }
            Text(
                text = letters,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = with(density) { (size * share).toSp() },
                    lineHeight = with(density) { (size * share).toSp() },
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = Palette.Text,
                maxLines = 1,
            )
        }
        val context = LocalContext.current
        val urls = LocalArtUrls.current
        val request = remember(file?.path, logo, px, urls) {
            when {
                file != null -> ImageRequest.Builder(context).data(File(file.path))
                logo != null -> urls.url(logo, px, px)?.let { ImageRequest.Builder(context).data(it) }
                else -> null
            }?.size(px, px)?.precision(Precision.EXACT)?.crossfade(true)?.build()
        }
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = if (file != null) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The letters for a channel nothing else fits (I7): one for one word, two for more, filler
 * words left out and a leading number kept whole. "Sci-Fi" → "SF", "90's Family" → "90s",
 * "TV Mix" → "M", "Ridge Line Marathon" → "RL", "Movies" → "M".
 */
internal fun monogram(name: String): String {
    val words = name.split(NOT_WORD).map { it.replace("'", "").replace("’", "") }.filter { it.isNotEmpty() }
    val kept = words.filterNot { it.lowercase() in FILLER }.ifEmpty { words }
    val first = kept.firstOrNull() ?: return "#"
    NUMBER.matchEntire(first)?.let { number ->
        val digits = number.groupValues[1]
        val decade = number.groupValues[2]
        return if (digits.length > 2) digits.takeLast(2) + decade else digits + decade
    }
    return kept.take(2).joinToString("") { it.take(1).uppercase() }
}

/** A tile's muted tone (I2): its glyph's, or one picked from the channel's id for a monogram. */
internal object ChannelTones {
    private val BY_GLYPH: Map<String, Color> = mapOf(
        "movie" to Color(0xFF2B4F7A),
        "tv_gen" to Color(0xFF3B4A5C),
        "theaters" to Color(0xFF5E5440),
        "local_fire_department" to Color(0xFF8A3F14),
        "explore" to Color(0xFF3E5A2E),
        "cruelty_free" to Color(0xFF1D5E7A),
        "toys" to Color(0xFF1D5E7A),
        "sentiment_very_satisfied" to Color(0xFF7A6014),
        "fingerprint" to Color(0xFF3A3F4A),
        "public" to Color(0xFF2F6A5A),
        "theater_comedy" to Color(0xFF5B3A72),
        "family_restroom" to Color(0xFF8A4A3A),
        "castle" to Color(0xFF4A2E6E),
        "history_edu" to Color(0xFF5E4A2E),
        "featured_seasonal_and_gifts" to Color(0xFF2D5B3B),
        "skull" to Color(0xFF5E1A1A),
        "music_note" to Color(0xFF6A2E4A),
        "visibility" to Color(0xFF2A4A5A),
        "favorite" to Color(0xFF7A2E5A),
        "rocket_launch" to Color(0xFF2E3F7A),
        "emoji_events" to Color(0xFF1E5E3A),
        "sports_football" to Color(0xFF1E5E3A),
        "military_tech" to Color(0xFF4A4A2E),
        "landscape" to Color(0xFF7A4A14),
        "newspaper" to Color(0xFF3B4A5C),
        "mic" to Color(0xFF7A6014),
        "restaurant" to Color(0xFF7A3A20),
        "animation" to Color(0xFF4A2E6E),
    )

    /** For monograms: the glyphs' tones, without repeats. */
    private val BY_ID: List<Color> = BY_GLYPH.values.distinct()

    fun of(glyph: String?, id: String): Color = glyph?.let(BY_GLYPH::get) ?: BY_ID[(abs(id.hashCode().toLong()) % BY_ID.size).toInt()]
}

/** The corners, as a share of the tile: 6 dp on 26, as before. */
private const val CORNER = 0.23f

/** The glyph's share of the tile. */
private const val GLYPH = 0.66f

private val NOT_WORD = Regex("[^\\p{L}\\p{N}'’]+")
private val NUMBER = Regex("(\\d+)(s?)")
private val FILLER = setOf("the", "a", "an", "and", "of", "tv", "channel", "marathon")
