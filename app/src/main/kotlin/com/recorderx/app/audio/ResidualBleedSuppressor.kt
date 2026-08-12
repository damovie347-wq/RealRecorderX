package com.recorderx.app.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Second-stage defense against system audio "bleeding" back into the
 * microphone through the device's own speaker -- layered on top of whatever
 * [android.media.audiofx.AcousticEchoCanceler] already removes (see
 * [MicAudioSource]), not a replacement for it.
 *
 * AcousticEchoCanceler is a *blind* adaptive filter tuned around phone-call
 * echo: it only ever sees the mic's own feed and has no access to the actual
 * far-end signal, so it has to *estimate* what's echo. Loud, bass-heavy game
 * or media audio at high volume is exactly the content that estimate holds
 * up worst against, which is the actual mechanism behind "oyunun sesini hem
 * dahili kaydediyor hem de mikrofon hoparlörden gelen sesi alıyor" even
 * though AEC is already enabled.
 *
 * This class has something AEC structurally can't: [AudioMixEngine.mixLoop]
 * already reads the *exact* system-audio PCM for the direct system-audio
 * track on every 20ms tick, which is the bleed's real source signal. That
 * lets it measure, chunk by chunk, how much of *this instant's* mic energy
 * is actually explained by *this instant's* system audio (a short, small-lag
 * normalized cross-correlation -- on the order of ten thousand multiply-adds
 * per 20ms chunk, not a full adaptive filter) and pull the mic down
 * specifically when that explanation is strong, leaving content the
 * reference *doesn't* explain (the user's own voice) untouched.
 *
 * Deliberately NOT a full NLMS/adaptive echo canceller: an under-tuned
 * adaptive filter can diverge and produce artifacts (warble, pumping) that
 * are worse than the bleed it's meant to remove, and that kind of tuning is
 * exactly the sort of thing that needs a real device/room to get right, not
 * a static review. This is the safer, bounded alternative -- a smoothed,
 * *capped* suppression gain that never fully mutes the mic, so speech
 * spoken *over* game audio still comes through, just less attenuated than
 * moments that are pure bleed with no voice in them at all.
 */
class ResidualBleedSuppressor {

    // 0 = no extra suppression right now, 1 = fully at MAX_SUPPRESSION_DB.
    private var smoothedSuppression = 0f

    /**
     * [referenceMono] is this tick's system-audio chunk, already downmixed
     * to mono and normalized to roughly -1..1 (see [AudioMixEngine.mixLoop]
     * for how it's built). [mic] is the raw mic chunk for the *same* tick;
     * both must be [frames] long. Returns a 0..1 multiplier for this chunk's
     * mic samples, applied on top of whatever gain the caller already uses.
     */
    fun nextMicSuppression(referenceMono: FloatArray, mic: ShortArray, frames: Int, chunkDurationMs: Float): Float {
        if (frames <= 0) return 1f

        val refRms = rms(referenceMono, frames)
        if (refRms < REFERENCE_GATE) {
            // System audio isn't loud enough right now to plausibly be the
            // source of any mic bleed -- relax back toward no suppression
            // rather than holding a stale value.
            return advance(0f, chunkDurationMs)
        }

        val correlation = maxAbsCorrelation(referenceMono, mic, frames)
        // Below the gate, today's mic content reads as independent of the
        // reference (real speech, room noise, silence) -- leave it alone.
        // Above it, map the remaining headroom to 0..1 suppression strength.
        val bleedLikelihood = ((correlation - CORRELATION_GATE) / (1f - CORRELATION_GATE)).coerceIn(0f, 1f)
        return advance(bleedLikelihood, chunkDurationMs)
    }

    private fun advance(target: Float, chunkDurationMs: Float): Float {
        val timeConstantMs = if (target > smoothedSuppression) ATTACK_MS else RELEASE_MS
        val step = (chunkDurationMs / timeConstantMs).coerceIn(0f, 1f)
        smoothedSuppression += (target - smoothedSuppression) * step
        val suppressionDb = smoothedSuppression * MAX_SUPPRESSION_DB
        return dbToLinear(-suppressionDb)
    }

    private fun rms(buf: FloatArray, frames: Int): Float {
        var sumSquares = 0.0
        for (i in 0 until frames) sumSquares += buf[i].toDouble() * buf[i]
        return sqrt(sumSquares / frames).toFloat()
    }

    /**
     * Normalized cross-correlation, maximized over a small window of lags
     * (covers the few-millisecond speaker-to-mic acoustic path plus
     * AudioRecord/AudioTrack pipeline latency, which isn't exactly zero or
     * perfectly stable chunk to chunk) rather than a single zero-lag sample.
     * Bounded to +/-[LAG_RANGE] samples either side so this stays a small,
     * fixed amount of work per chunk regardless of chunk size -- negligible
     * next to the actual encode/mix work already happening on this thread,
     * not a new source of the CPU/heat load issue 3 asks to reduce.
     */
    private fun maxAbsCorrelation(reference: FloatArray, mic: ShortArray, frames: Int): Float {
        var refEnergy = 0.0
        for (i in 0 until frames) refEnergy += reference[i].toDouble() * reference[i]
        if (refEnergy < MIN_ENERGY) return 0f

        var micEnergy = 0.0
        for (i in 0 until frames) {
            val m = mic[i] / 32768f
            micEnergy += m.toDouble() * m
        }
        if (micEnergy < MIN_ENERGY) return 0f

        val norm = sqrt(refEnergy * micEnergy)
        var best = 0.0
        for (lag in -LAG_RANGE..LAG_RANGE) {
            val start = max(0, -lag)
            val end = min(frames, frames - lag)
            if (end <= start) continue
            var dot = 0.0
            for (i in start until end) {
                val m = mic[i + lag] / 32768f
                dot += reference[i].toDouble() * m
            }
            val corr = abs(dot) / norm
            if (corr > best) best = corr
        }
        return best.toFloat().coerceIn(0f, 1f)
    }

    private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    companion object {
        // Samples of timing slop to search either side. At 48kHz this is
        // ~+/-0.2ms -- enough to cover realistic speaker-to-mic + pipeline
        // delay drift without turning this into an expensive wide search.
        private const val LAG_RANGE = 10

        // Below this reference RMS (normalized -1..1 scale), system audio
        // isn't loud enough right now to plausibly be bleeding into anything.
        private const val REFERENCE_GATE = 0.015f
        private const val MIN_ENERGY = 1e-6

        // Correlation below this reads as independent content (real speech,
        // ambient noise) and is left untouched; only the remaining headroom
        // above it maps into suppression strength.
        private const val CORRELATION_GATE = 0.35f

        // Never fully mutes the mic: caps how much this layer can pull it
        // down so speech spoken *over* game audio still comes through,
        // attenuated less than moments that are pure bleed with no voice in
        // them. Ear-tuning this against real content/devices -- like the
        // DSP constants in DuckingProcessor -- is exactly the sort of thing
        // ARCHITECTURE.md already flags as benefiting from real hardware.
        private const val MAX_SUPPRESSION_DB = 14f

        private const val ATTACK_MS = 60f   // clamp down quickly once bleed is detected
        private const val RELEASE_MS = 220f // release a bit slower to avoid audible chattering
    }
}
