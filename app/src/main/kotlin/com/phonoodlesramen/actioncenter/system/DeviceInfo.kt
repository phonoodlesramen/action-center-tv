package com.phonoodlesramen.actioncenter.system

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import kotlin.math.roundToInt

/** The real device values Phase 0 needs to confirm (density, size, RAM, build). */
data class DeviceInfo(
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val buildDisplay: String,
    val buildIncremental: String,
    val displayPx: String,
    val densityDpi: Int,
    val screenDp: String,
    val totalRamMb: Long,
    val memoryClassMb: Int,
    val lowRamDevice: Boolean,
)

fun interface DeviceInfoSource {
    fun read(): DeviceInfo
}

class AndroidDeviceInfoSource(private val context: Context) : DeviceInfoSource {
    override fun read(): DeviceInfo {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val mode = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY).mode
        val config = context.resources.configuration
        return DeviceInfo(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            buildDisplay = Build.DISPLAY,
            buildIncremental = Build.VERSION.INCREMENTAL,
            displayPx = "${mode.physicalWidth}×${mode.physicalHeight} @ ${mode.refreshRate.roundToInt()} Hz",
            densityDpi = config.densityDpi,
            screenDp = "${config.screenWidthDp}×${config.screenHeightDp} dp",
            totalRamMb = memory.totalMem / (1024 * 1024),
            memoryClassMb = activityManager.memoryClass,
            lowRamDevice = activityManager.isLowRamDevice,
        )
    }
}
