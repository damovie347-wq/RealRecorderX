package com.recorderx.app.util

import android.app.ActivityManager
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * There's no official Android API for "is this a low/mid/high-end device" --
 * this is a deliberately simple, documented heuristic (RAM class + core count),
 * used only to pick a *safe default*, never to hard-block a feature. The user
 * can always override the codec manually regardless of what this returns.
 */
object DeviceTier {

    enum class Tier { LOW, MID, HIGH }

    fun classify(context: Context): Tier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val isLowRam = am?.isLowRamDevice == true
        val cores = Runtime.getRuntime().availableProcessors()

        return when {
            isLowRam || totalRamGb < 3.0 || cores <= 4 -> Tier.LOW
            totalRamGb < 6.0 -> Tier.MID
            else -> Tier.HIGH
        }
    }

    /** Real panel resolution in pixels, portrait-normalized (width <= height).
     * Used for the "Native" resolution option and to seed BitrateAdvisor. */
    fun panelResolutionPx(context: Context): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            point.set(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
        }
        if (point.x > point.y) {
            val tmp = point.x
            point.x = point.y
            point.y = tmp
        }
        return point
    }

    fun screenDensityDpi(context: Context): Int {
        val metrics: DisplayMetrics = context.resources.displayMetrics
        return metrics.densityDpi
    }
}
