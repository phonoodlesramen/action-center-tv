package com.phonoodlesramen.actioncenter.apps

import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A TV banner's pixel size: 160×90 dp at xhdpi. Custom banners (L7) are decoded at this
 * size too, never larger than the card (spec §6).
 */
object BannerSize {
    const val WIDTH_PX = 320
    const val HEIGHT_PX = 180
}

/** A tile's art: the app's TV banner, or its icon for the fallback tile (spec §4, U7). */
sealed interface AppArt {
    data class Banner(val bitmap: ImageBitmap) : AppArt
    data class Icon(val bitmap: ImageBitmap) : AppArt
}

fun interface AppArtSource {
    suspend fun art(app: LaunchableApp): AppArt?
}

/** Defaults to no art (screenshots, previews): tiles fall back to the app's name. */
val LocalAppArtSource = staticCompositionLocalOf { AppArtSource { null } }

/** Banners are decoded once at their native 320×180 and cached (about 230 KB each). */
class AndroidAppArtSource(private val packageManager: PackageManager) : AppArtSource {
    private val cache = LruCache<String, AppArt>(64)

    override suspend fun art(app: LaunchableApp): AppArt? = withContext(Dispatchers.IO) {
        cache.get(app.key) ?: load(app)?.also { cache.put(app.key, it) }
    }

    private fun load(app: LaunchableApp): AppArt? = try {
        val component = ComponentName(app.packageName, app.activityName)
        val banner = packageManager.getActivityBanner(component) ?: packageManager.getApplicationBanner(app.packageName)
        if (banner != null) {
            AppArt.Banner(banner.toBitmap(BannerSize.WIDTH_PX, BannerSize.HEIGHT_PX).asImageBitmap())
        } else {
            AppArt.Icon(packageManager.getActivityIcon(component).toBitmap(ICON_SIZE, ICON_SIZE).asImageBitmap())
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val ICON_SIZE = 96
    }
}
