package com.phonoodlesramen.actioncenter.ui.art

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.phonoodlesramen.actioncenter.plex.ServerAccess
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns a Plex art path into a URL for an image of exactly that many pixels. */
fun interface ArtUrls {
    fun url(path: String, widthPx: Int, heightPx: Int): String?

    /**
     * A title's logo (Phase 12) fitted inside that many pixels, its transparency kept. Null,
     * as here, means logos aren't shown, so the cards show the title.
     */
    fun logoUrl(path: String, widthPx: Int, heightPx: Int): String? = null
}

/** No server: every card shows its placeholder art (screenshots, sample content, before sign-in). */
val LocalArtUrls = staticCompositionLocalOf { ArtUrls { _, _, _ -> null } }

/**
 * Plex's photo transcoder (official PMS spec) at the tile's own pixel size, so nothing
 * bigger than the tile is ever decoded (spec §6). The token rides in the query, as the
 * spec asks for the inner `url`.
 *
 * Art is a JPEG that covers the tile (`minSize=1`), to be centre-cropped. A logo (Phase 12)
 * is a PNG, which keeps its transparency (the transcoder's `background` is none by
 * default), that fits inside the box (`minSize=0`). Official spec (`imageTranscode`):
 * `minSize` false scales the image "to fit within the width/height specified", true lets
 * it overflow one dimension.
 */
class PlexArtUrls(private val access: ServerAccess?) : ArtUrls {
    override fun url(path: String, widthPx: Int, heightPx: Int): String? = transcode(path, widthPx, heightPx, logo = false)

    override fun logoUrl(path: String, widthPx: Int, heightPx: Int): String? = transcode(path, widthPx, heightPx, logo = true)

    private fun transcode(path: String, widthPx: Int, heightPx: Int, logo: Boolean): String? {
        val server = access ?: return null
        if (widthPx <= 0 || heightPx <= 0) return null
        val source = "$path${if ('?' in path) '&' else '?'}X-Plex-Token=${server.token}"
        return "${server.baseUri.trimEnd('/')}/photo/:/transcode".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("width", widthPx.toString())
            ?.addQueryParameter("height", heightPx.toString())
            ?.addQueryParameter("minSize", if (logo) "0" else "1")
            ?.addQueryParameter("upscale", "1")
            ?.addQueryParameter("format", if (logo) "png" else "jpg")
            ?.addQueryParameter("url", source)
            ?.addQueryParameter("X-Plex-Token", server.token)
            ?.build()
            ?.toString()
    }
}

/**
 * Art from Plex's catalog (Phase 15): web addresses (TMDB's originals, Plex's own), resized by
 * Plex's public image service to the tile, so a 2 GB TV never downloads an original (checked
 * 11/03/2026: a 975 KB backdrop came back as 65 KB, with no token). No token goes with it.
 * Anything not a web address isn't the catalog's, and shows the placeholder.
 */
object CatalogArtUrls : ArtUrls {
    override fun url(path: String, widthPx: Int, heightPx: Int): String? {
        if (!path.startsWith("https://") || widthPx <= 0 || heightPx <= 0) return null
        return "https://images.plex.tv/photo".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("width", widthPx.toString())
            ?.addQueryParameter("height", heightPx.toString())
            ?.addQueryParameter("minSize", "1")
            ?.addQueryParameter("upscale", "1")
            ?.addQueryParameter("url", path)
            ?.build()
            ?.toString()
    }
}

/**
 * A title's art: the generated placeholder underneath (never a blank card), Plex's
 * image on top once it loads. Late night dims both (spec §2); [treatment] is the lens
 * card's sepia.
 */
@Composable
fun TitleArt(
    id: String,
    image: String?,
    lateNight: Boolean,
    modifier: Modifier = Modifier,
    treatment: ColorMatrix? = null,
) {
    BoxWithConstraints(modifier.titleArt(id, lateNight, treatment)) {
        val url = image?.let { LocalArtUrls.current.url(it, constraints.maxWidth, constraints.maxHeight) }
        if (url != null) {
            val filter = ArtFilters.combine(treatment, if (lateNight) ArtFilters.LateNight else null)
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .size(constraints.maxWidth, constraints.maxHeight)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = filter?.let(ColorFilter::colorMatrix),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
