package com.recorderx.app.audio

import com.recorderx.app.settings.VoicePriority
import kotlin.math.sqrt

/**
 * Deliberately simple by design: a smoothed RMS envelope on the mic signal
 * decides "is the user talking right now," and the system-audio gain chases
 * a target (1.0 normally, [VoicePriority.duckFloor] while speaking) with
 * different attack and release time constants. Ducking down is faster than
 * recovering back up, which is what reads as "natural" rather than "pumping" --
 * the same asymmetry a broadcast compressor/ducker uses.
 */
class DuckingProcessor(priority: VoicePriority) {

    var priority: VoicePriority = priority
        set(value) {
            field = value
            if (value == VoicePriority.OFF) currentGain = 1f
        }

    private var smoothedRms = 0f
    private var currentGain = 1f

    /** Feeds one chunk of mono-summed mic samples (already downmixed by the
     * caller if the mic capture is stereo) and returns whether the smoothed
     * envelope currently reads as "speech." */
    fun updateSpeechState(micChunk: ShortArray, sampleCount: Int): Boolean {
        if (sampleCount <= 0) return smoothedRms > SPEECH_THRESHOLD
        var sumSquares = 0.0
        for (i in 0 until sampleCount) {
            val normalized = micChunk[i] / 32768f
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / sampleCount).toFloat()
        // Simple one-pole smoothing so a single loud consonant doesn't flip
        // the speech flag on/off within a single chunk.
        smoothedRms = smoothedRms * 0.75f + rms * 0.25f
        return smoothedRms > SPEECH_THRESHOLD
    }

    /** Advances the gain envelope by [chunkDurationMs] toward the target
     * implied by [isSpeaking] and the current [priority], and returns the
     * gain multiplier to apply to this chunk of system audio. */
    fun nextSystemGain(isSpeaking: Boolean, chunkDurationMs: Float): Float {
        if (priority == VoicePriority.OFF) return 1f

        val target = if (isSpeaking) priority.duckFloor else 1f
        val timeConstantMs = if (target < currentGain) ATTACK_MS else RELEASE_MS
        val step = (chunkDurationMs / timeConstantMs).coerceIn(0f, 1f)
        currentGain += (target - currentGain) * step
        return currentGain
    }

    companion object {
        private const val SPEECH_THRESHOLD = 0.018f
        private const val ATTACK_MS = 140f   // duck down quickly once speech starts
        private const val RELEASE_MS = 700f  // ease back up slowly once it stops
    }
}
