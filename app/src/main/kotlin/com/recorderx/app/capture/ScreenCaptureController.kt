package com.recorderx.app.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface

/**
 * Thin wrapper around [MediaProjection.createVirtualDisplay]. The Surface
 * passed in is always the encoder's own input surface (see
 * VideoEncoderPipeline.configure) -- this class never touches pixels itself,
 * it just tells SurfaceFlinger where to mirror the display to.
 */
class ScreenCaptureController(private val mediaProjection: MediaProjection) {

    private var virtualDisplay: VirtualDisplay? = null

    fun start(
        targetSurface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        onDisplayStopped: () -> Unit
    ) {
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "RecorderX-Capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            targetSurface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    onDisplayStopped()
                }
            },
            null
        )
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
    }
}
