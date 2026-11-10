package com.phonoodlesramen.actioncenter

import android.app.Application
import android.content.ComponentCallbacks2
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath

open class ActionCenterApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    /** Tests replace the parts that reach the network. */
    protected open fun createContainer() = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        // Record every crash first, so the next launch opens safe mode.
        Thread.setDefaultUncaughtExceptionHandler(
            container.crashGuard.handler(previous = Thread.getDefaultUncaughtExceptionHandler()),
        )
    }

    /** Artwork (spec §6): a 24 MB memory cache on a 3 GB device that shares RAM with playback. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(ART_MEMORY_BYTES).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("art").toOkioPath()).maxSizeBytes(ART_DISK_BYTES).build() }
        .build()

    /** Once the home screen is hidden (playback in Plezy), give the art's memory back. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) SingletonImageLoader.get(this).memoryCache?.clear()
    }

    private companion object {
        const val ART_MEMORY_BYTES = 24L * 1024 * 1024
        const val ART_DISK_BYTES = 100L * 1024 * 1024
    }
}
