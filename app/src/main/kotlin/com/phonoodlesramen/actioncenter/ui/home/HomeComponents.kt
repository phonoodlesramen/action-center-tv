package com.phonoodlesramen.actioncenter.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.AppArt
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.LocalAppArtSource
import com.phonoodlesramen.actioncenter.apps.key
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Layout
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.onWallpaper

/** Spec §3: 2.5 dp accent ring and a 4% lift on focus. */
@Composable
fun focusRing(shape: Shape = RoundedCornerShape(Layout.TileRadius)) = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(2.5.dp, LocalAccent.current), shape = shape),
)

val cardLift @Composable get() = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)

@Composable
fun tileColors(container: Color = Palette.Surface2) = ClickableSurfaceDefaults.colors(
    containerColor = container,
    contentColor = Palette.Text,
    focusedContainerColor = Palette.Surface3,
    focusedContentColor = Palette.Text,
)

/** Buttons fill with the accent when focused (spec §3). */
@Composable
fun pillButtonColors(
    container: Color = Color.White.copy(alpha = 0.12f),
    content: Color = Palette.Text,
    focused: Color = LocalAccent.current,
    focusedInk: Color = Palette.AccentInk,
) = ButtonDefaults.colors(
    containerColor = container,
    contentColor = content,
    focusedContainerColor = focused,
    focusedContentColor = focusedInk,
)

val pillButtonScale @Composable get() = ButtonDefaults.scale(focusedScale = 1.04f)

/** A pill button: 36 dp, or 30 dp when [small] (spec §2). */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    small: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    colors: ButtonColors = pillButtonColors(),
) {
    Button(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(if (small) 30.dp else 36.dp),
        colors = colors,
        scale = pillButtonScale,
        contentPadding = PaddingValues(horizontal = if (small) 12.dp else 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** The remote's menu button opens quick actions, like holding OK [U11]. */
fun Modifier.onMenuKey(action: () -> Unit): Modifier = onKeyEvent {
    if (it.key == Key.Menu && it.type == KeyEventType.KeyUp) {
        action()
        true
    } else {
        false
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Palette.TextDim) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp).onWallpaper(),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A dashed empty card for the first-run state (spec §5). Not focusable. */
@Composable
fun PlaceholderCard(text: String, modifier: Modifier = Modifier, radius: Dp = Layout.CardRadius) {
    Box(
        modifier = modifier.drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(Color.White.copy(alpha = 0.03f), cornerRadius = CornerRadius(radius.toPx()))
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(radius.toPx()),
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextFaint,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * An app tile: its TV banner, or the fallback (icon or initial over the name) when
 * the app has no banner [U7].
 */
@Composable
fun AppTile(
    app: LaunchableApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val source = LocalAppArtSource.current
    val art by produceState<AppArt?>(null, app.key) { value = source.art(app) }
    val shape = RoundedCornerShape(Layout.TileRadius)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = tileColors(),
        scale = cardLift,
        border = focusRing(shape),
    ) {
        when (val loaded = art) {
            is AppArt.Banner -> Image(
                bitmap = loaded.bitmap,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> FallbackTile(app, (loaded as? AppArt.Icon)?.bitmap)
        }
    }
}

@Composable
private fun FallbackTile(app: LaunchableApp, icon: ImageBitmap?) {
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

/** The All apps tile at the end of the launchpad, and of a kid's My apps (rounder, [radius]). */
@Composable
fun AllAppsTile(onClick: () -> Unit, modifier: Modifier = Modifier, radius: Dp = Layout.TileRadius) {
    val shape = RoundedCornerShape(radius)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = tileColors(),
        scale = cardLift,
        border = focusRing(shape),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Grid, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("All apps", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
