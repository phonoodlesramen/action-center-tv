package com.phonoodlesramen.actioncenter.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.phonoodlesramen.actioncenter.apps.AppArt
import com.phonoodlesramen.actioncenter.apps.BannerFile
import com.phonoodlesramen.actioncenter.apps.BannerSize
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LocalAppArtSource
import com.phonoodlesramen.actioncenter.apps.key
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.tileColors
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import java.io.File

/**
 * An app's card (L7): its [custom] banner from the Icons folder, else its own TV banner,
 * else its icon and name. It's one focusable surface whatever it draws, so a banner that
 * loads late, or fails, never takes focus away. A kid's tiles are rounder ([radius], U13).
 */
@Composable
fun AppBanner(
    app: LaunchableApp,
    custom: BannerFile?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    radius: Dp = Layout.TileRadius,
) {
    BannerSurface(onClick, modifier, onLongClick, radius) { AppArtContent(app, custom) }
}

/** A 16:9 card with the focus ring and lift of spec §3, like every row card. */
@Composable
fun BannerSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    radius: Dp = Layout.TileRadius,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = tileColors(),
        scale = cardLift,
        border = focusRing(shape),
        content = content,
    )
}

/** What an app's card draws: [custom] once it loads, else the app's own banner or its name. */
@Composable
fun AppArtContent(app: LaunchableApp, custom: BannerFile?) {
    var customShown by remember(custom) { mutableStateOf(false) }
    var customFailed by remember(custom) { mutableStateOf(false) }
    val useCustom = custom != null && !customFailed
    val source = LocalAppArtSource.current
    val own by produceState<AppArt?>(null, app.key, useCustom) { value = if (useCustom) null else source.art(app) }
    // The name stays underneath until the custom banner has loaded: never a blank card.
    if (!customShown) {
        when (val art = own) {
            is AppArt.Banner -> Image(
                bitmap = art.bitmap,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> AppName(app, (art as? AppArt.Icon)?.bitmap)
        }
    }
    if (custom != null && useCustom) {
        BannerImage(
            file = custom,
            contentDescription = app.label,
            onSuccess = { customShown = true },
            onError = { customFailed = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A banner file decoded at 320×180 px (spec §6), cropped to its card. */
@Composable
fun BannerImage(
    file: BannerFile,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onSuccess: () -> Unit = {},
    onError: () -> Unit = {},
) {
    val context = LocalContext.current
    val request = remember(file.path) {
        ImageRequest.Builder(context)
            .data(File(file.path))
            .size(BannerSize.WIDTH_PX, BannerSize.HEIGHT_PX)
            .precision(Precision.EXACT)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        onSuccess = { onSuccess() },
        onError = { onError() },
        modifier = modifier,
    )
}

/** The fallback for an app without a banner: its icon (or initial) over its name [U7]. */
@Composable
fun AppName(app: LaunchableApp, icon: ImageBitmap?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        } else {
            Box(
                modifier = Modifier.size(24.dp).background(Color.White.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(app.label.take(1).uppercase(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
