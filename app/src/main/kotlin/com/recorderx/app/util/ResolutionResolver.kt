package com.recorderx.app.util

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.recorderx.app.settings.OrientationOption
import com.recorderx.app.settings.ResolutionOption

/**
 * Pure resolution math, deliberately called from MainActivity (an Activity
 * Context always has a well-defined associated display) rather than from
 * RecordingService -- Context.getDisplay() throws UnsupportedOperationException
 * on plain Service/Application contexts on API 30+, so the safe place to read
 * "what's the panel size / current rotation" is here, once, before the
 * capture width/height is handed to the service as plain Intent extras.
 *
 * Always honors the user's exact pick. An earlier version silently
 * substituted the panel's native size whenever a requested tier (e.g. "4K")
 * exceeded it, reasoning that upscaling adds no real detail -- true, but it
 * meant "select 4K" silently produced whatever the panel actually was, with
 * no indication anything was overridden. `MediaProjection.createVirtualDisplay`
 * can target *any* width/height regardless of the physical panel (the
 * platform scales the mirrored content to fit), so there's no technical
 * reason to override the choice -- only a reason to disclose it, which
 * [isUpscaling] is for.
 *
 * Non-NATIVE picks use [ResolutionOption]'s fixed, exact standard pair
 * (e.g. 3840x2160 for "4K") rather than a size recomputed from the panel's
 * own aspect ratio -- see the kdoc on [ResolutionOption] for why: on any
 * panel that isn't exactly 16:9, aspect-correcting silently produced a size
 * that matched neither the panel nor the label.
 */
object ResolutionResolver {

    data class Target(val width: Int, val height: Int)

    fun resolve(context: Context, resolution: ResolutionOption, orientation: OrientationOption): Target {
        val (shortEdge, longEdge) = if (resolution == ResolutionOption.NATIVE) {
            val panel = DeviceTier.panelResolutionPx(context) // portrait-normalized: x <= y
            panel.x to panel.y
        } else {
            resolution.shortEdge to resolution.longEdge
        }

        val wantsLandscape = when (orientation) {
            OrientationOption.LANDSCAPE -> true
            OrientationOption.PORTRAIT -> false
            OrientationOption.AUTO -> isCurrentlyLandscape(context)
        }

        val target = if (wantsLandscape) Target(longEdge, shortEdge) else Target(shortEdge, longEdge)
        Log.i(TAG, "resolve(): option=$resolution -> target=${target.width}x${target.height}")
        return target
    }

    /** True when [resolution] exceeds the device's real panel resolution --
     * the recording will still be produced at the exact requested size, just
     * upscaled from fewer real source pixels. Used only to show an
     * informational note in the UI, never to change what gets recorded. */
    fun isUpscaling(context: Context, resolution: ResolutionOption): Boolean {
        if (resolution == ResolutionOption.NATIVE) return false
        val panel = DeviceTier.panelResolutionPx(context)
        return resolution.longEdge > panel.y
    }

    private fun isCurrentlyLandscape(context: Context): Boolean {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val rotation = wm.defaultDisplay.rotation
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }

    private const val TAG = "ResolutionResolver"
}
