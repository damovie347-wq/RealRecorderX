package com.recorderx.app.adaptive

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.recorderx.app.encoder.VideoEncoderPipeline

/**
 * "Kademeli optimize et" (optimize gradually), not "panic and stop at the
 * first sign of warmth": bitrate steps down in stages as PowerManager's
 * thermal status rises, with an fps cap layered on for the worse stages, and
 * recording is only force-stopped at the platform's most extreme state
 * (EMERGENCY/SHUTDOWN) to protect the device and still save what was
 * captured, rather than let the OS kill things uncontrolled.
 *
 * No-ops entirely below API 29, where PowerManager exposes no thermal
 * status API at all -- recording still works there, it just doesn't get
 * this particular safety net (see ARCHITECTURE.md's compatibility table).
 */
class ThermalBitrateGovernor(
    private val context: Context,
    private val videoEncoder: VideoEncoderPipeline,
    private val baseBitrateBps: Int,
    private val configuredFps: Int,
    private val onEmergencyStop: () -> Unit
) {
    private var powerManager: PowerManager? = null
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val l = PowerManager.OnThermalStatusChangedListener { status -> onThermalStatusChanged(status) }
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), l)
        powerManager = pm
        listener = l
    }

    fun stop() {
        val pm = powerManager ?: return
        val l = listener ?: return
        pm.removeThermalStatusListener(l)
        powerManager = null
        listener = null
    }

    private fun onThermalStatusChanged(status: Int) {
        val bitrateFraction = when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> 1.0
            PowerManager.THERMAL_STATUS_MODERATE -> 0.80
            PowerManager.THERMAL_STATUS_SEVERE -> 0.55
            PowerManager.THERMAL_STATUS_CRITICAL -> 0.40
            else -> 0.40 // EMERGENCY / SHUTDOWN -- moot once onEmergencyStop() below fires
        }
        val target = (baseBitrateBps * bitrateFraction).toInt().coerceAtLeast(MIN_BITRATE_BPS)
        videoEncoder.applyBitrate(target)

        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            videoEncoder.tryLimitInputFrameRate(minOf(configuredFps, 30))
        }
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL) {
            videoEncoder.tryLimitInputFrameRate(minOf(configuredFps, 24))
        }
        if (status >= PowerManager.THERMAL_STATUS_EMERGENCY) {
            onEmergencyStop()
        }
    }

    companion object {
        private const val MIN_BITRATE_BPS = 1_000_000
    }
}
