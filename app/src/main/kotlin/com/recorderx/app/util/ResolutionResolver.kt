package com.recorderx.app.util

import android.content.Context
import android.view.Surface
import android.view.WindowManager
import com.recorderx.app.settings.OrientationOption
import com.recorderx.app.settings.ResolutionOption
import kotlin.math.roundToInt

/**
 * Pure resolution math, deliberately called from MainActivity (an Activity
 * Context always has a well-defined associated display) rather than from
 * RecordingService -- Context.getDisplay() throws UnsupportedOperationException
 * on plain Service/Application contexts on API 30+, so the safe place to read
 * "what's the panel size / current rotation" is here, once, before the
 * capture width/height is handed to the service as plain Intent extras.
 */
object ResolutionResolver {

    data class Target(val width: Int, val height: Int)

    fun resolve(context: Context, resolution: ResolutionOption, orientation: OrientationOption): Target {
        val panel = DeviceTier.panelResolutionPx(context) // portrait-normalized: x <= y
        val panelShort = panel.x
        val panelLong = panel.y

        val (shortEdge, longEdge) = if (resolution == ResolutionOption.NATIVE || resolution.longEdge >= panelLong) {
            // NATIVE, or a requested tier that would upscale past the real panel
            // (e.g. "4K" on a 1080p panel): use the real panel size either way --
            // upscaling adds file size with zero real detail gained.
            panelShort to panelLong
        } else {
            val aspect = panelShort.toDouble() / panelLong.toDouble()
            val computedShort = (resolution.longEdge * aspect).roundToInt().coerceAtLeast(2)
            computedShort to resolution.longEdge
        }

        val wantsLandscape = when (orientation) {
            OrientationOption.LANDSCAPE -> true
            OrientationOption.PORTRAIT -> false
            OrientationOption.AUTO -> isCurrentlyLandscape(context)
        }

        return if (wantsLandscape) Target(longEdge, shortEdge) else Target(shortEdge, longEdge)
    }

    private fun isCurrentlyLandscape(context: Context): Boolean {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val rotation = wm.defaultDisplay.rotation
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }
}
