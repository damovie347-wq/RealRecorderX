package com.recorderx.app.adaptive

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.recorderx.app.encoder.VideoEncoderPipeline

/**
 * "Kademeli optimize et" (optimize gradually), not "panic and stop at the
 * first sign of warmth": bitrate steps down in stages as thermal pressure
 * rises, with an fps cap layered on for the worse stages, and recording is
 * only force-stopped at the platform's most extreme state (EMERGENCY/
 * SHUTDOWN) to protect the device and still save what was captured, rather
 * than let the OS kill things uncontrolled.
 *
 * Two independent layers feed the same [VideoEncoderPipeline.applyBitrate]
 * call, and the lower (more conservative) of the two always wins -- see
 * [applyCombinedBitrate]:
 *  - **Reactive** ([onThermalStatusChanged]): [PowerManager]'s
 *    [PowerManager.OnThermalStatusChangedListener], the platform telling us
 *    a discrete status has *already* been reached.
 *  - **Proactive** ([pollThermalHeadroom]): [PowerManager.getThermalHeadroom],
 *    polled every few seconds, which *forecasts* headroom a few seconds
 *    ahead rather than reporting where things stand right now. This is what
 *    lets bitrate ease down gently *before* the reactive listener ever has
 *    to fire -- which in turn is what keeps the FPS cap further down from
 *    having to engage as often. A dropped FPS cap is the *visible* symptom
 *    of this governor doing its job under real thermal pressure; the less
 *    often the reactive layer needs its harder steps, the less often FPS
 *    actually has to drop, which is the real fix for "60 seçtim ama 30
 *    alıyorum" -- generate less heat in the first place, don't just react
 *    to it faster.
 *
 * The proactive layer only ever touches bitrate, never FPS -- it's a soft,
 * early nudge layered *underneath* the reactive thresholds below, not a
 * replacement for them.
 *
 * Reactive layer no-ops below API 29 (no thermal-status API at all);
 * proactive layer no-ops below API 30 (no [PowerManager.getThermalHeadroom]).
 * Recording still works on older platforms, it just doesn't get this
 * particular safety net -- see ARCHITECTURE.md's compatibility table.
 */
class ThermalBitrateGovernor(
    private val context: Context,
    private val videoEncoder: VideoEncoderPipeline,
    private val framePacer: com.recorderx.app.capture.FramePacer,
    private val baseBitrateBps: Int,
    private val configuredFps: Int,
    private val onEmergencyStop: () -> Unit
) {
    private var powerManager: PowerManager? = null
    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    private val handler = Handler(Looper.getMainLooper())

    private var reactiveFraction = 1.0
    private var proactiveFraction = 1.0
    @Volatile private var running = false

    private val headroomPoll = object : Runnable {
        override fun run() {
            if (!running) return
            pollThermalHeadroom()
            handler.postDelayed(this, HEADROOM_POLL_INTERVAL_MS)
        }
    }

    fun start() {
        running = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                val l = PowerManager.OnThermalStatusChangedListener { status -> onThermalStatusChanged(status) }
                pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), l)
                powerManager = pm
                listener = l
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handler.postDelayed(headroomPoll, HEADROOM_POLL_INTERVAL_MS)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(headroomPoll)
        val pm = powerManager
        val l = listener
        if (pm != null && l != null) pm.removeThermalStatusListener(l)
        powerManager = null
        listener = null
    }

    /**
     * [PowerManager.getThermalHeadroom] is normalized so `1.0` marks the
     * SEVERE throttling threshold. Below [PROACTIVE_TRIGGER] this layer
     * leaves bitrate alone entirely; between [PROACTIVE_TRIGGER] and `1.0`
     * it eases bitrate down toward (never past) [PROACTIVE_FLOOR], so some
     * of the rise toward SEVERE is already being absorbed *before* the
     * reactive listener's harder, discrete step at actual SEVERE would
     * otherwise have to do all the work alone.
     */
    private fun pollThermalHeadroom() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val headroom = try {
            pm.getThermalHeadroom(HEADROOM_FORECAST_SECONDS)
        } catch (e: Exception) {
            return
        }
        // NaN means "unsupported on this device" or "polled faster than the
        // platform allows" -- either way, nothing to react to this cycle.
        if (headroom.isNaN()) return

        proactiveFraction = if (headroom <= PROACTIVE_TRIGGER) {
            1.0
        } else {
            val t = ((headroom - PROACTIVE_TRIGGER) / (1.0 - PROACTIVE_TRIGGER)).coerceIn(0.0, 1.0)
            1.0 - t * (1.0 - PROACTIVE_FLOOR)
        }
        applyCombinedBitrate()
    }

    private fun onThermalStatusChanged(status: Int) {
        reactiveFraction = when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> 1.0
            PowerManager.THERMAL_STATUS_MODERATE -> 0.80
            PowerManager.THERMAL_STATUS_SEVERE -> 0.55
            PowerManager.THERMAL_STATUS_CRITICAL -> 0.40
            else -> 0.40 // EMERGENCY / SHUTDOWN -- moot once onEmergencyStop() below fires
        }
        applyCombinedBitrate()

        // videoEncoder.tryLimitInputFrameRate() stays as a defensive,
        // best-effort extra layer (see its own kdoc), but framePacer is what
        // actually enforces this now -- it owns delivery cadence to the
        // encoder outright, so this is the call that reliably reduces heat/
        // encode load under real thermal pressure rather than just hoping a
        // vendor driver honors a hint.
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            val capped = minOf(configuredFps, 30)
            videoEncoder.tryLimitInputFrameRate(capped)
            framePacer.setTargetFps(capped)
        }
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL) {
            val capped = minOf(configuredFps, 24)
            videoEncoder.tryLimitInputFrameRate(capped)
            framePacer.setTargetFps(capped)
        }
        if (status >= PowerManager.THERMAL_STATUS_EMERGENCY) {
            onEmergencyStop()
        }
    }

    /** Whichever layer currently wants the *lower* bitrate wins -- the two
     * are independent inputs to one shared ceiling, never additive. */
    private fun applyCombinedBitrate() {
        val fraction = minOf(reactiveFraction, proactiveFraction)
        val target = (baseBitrateBps * fraction).toInt().coerceAtLeast(MIN_BITRATE_BPS)
        videoEncoder.applyBitrate(target)
    }

    companion object {
        private const val MIN_BITRATE_BPS = 1_000_000

        // getThermalHeadroom() is documented as unreliable (NaN) if polled
        // faster than ~once/second; every few seconds is safely inside that
        // limit and plenty granular for a slow-moving quantity like device
        // temperature.
        private const val HEADROOM_POLL_INTERVAL_MS = 6_000L
        private const val HEADROOM_FORECAST_SECONDS = 8

        // Start easing bitrate down once the forecast crosses 70% of the
        // way to SEVERE, well before SEVERE actually hits.
        private const val PROACTIVE_TRIGGER = 0.70
        // Even right at the SEVERE mark, the proactive layer alone only
        // caps bitrate at 85% -- deliberately gentle; the reactive listener
        // still owns the harder cuts once SEVERE/CRITICAL is actually reached.
        private const val PROACTIVE_FLOOR = 0.85
    }
}
