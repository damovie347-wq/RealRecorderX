package com.recorderx.app.audio

import com.recorderx.app.settings.BleedSuppressionMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Second-stage defense against system audio "bleeding" back into the
 * microphone through the device's own speaker -- layered on top of whatever
 * [android.media.audiofx.AcousticEchoCanceler] already removes (see
 * [MicAudioSource]), not a replacement for it. Needed because AEC isn't
 * guaranteed to exist at all (plenty of tablets, this app's primary device
 * class, ship with no AEC effect implementation whatsoever -- [MicAudioSource]
 * only enables it `if (AcousticEchoCanceler.isAvailable())`), and even where
 * it does exist, it's a *blind* adaptive filter tuned around phone-call echo
 * that only ever sees the mic's own feed and has to *estimate* what's echo --
 * loud, bass-heavy game/media audio is exactly the content that estimate
 * holds up worst against.
 *
 * This class has something AEC structurally can't: [AudioMixEngine.mixLoop]
 * already reads the *exact* system-audio PCM for the direct system-audio
 * track on every 20ms tick, which is the bleed's real source signal. That
 * lets it measure, chunk by chunk, how much of *this instant's* mic energy is
 * actually explained by system audio from *somewhere in the recent past* (a
 * normalized cross-correlation against a rolling history buffer, not a full
 * adaptive filter) and pull the mic down specifically when that explanation
 * is strong, leaving content the reference doesn't explain (the user's own
 * voice) untouched.
 *
 * ## Why a history buffer at all
 * An earlier version compared the mic chunk only against *that same tick's*
 * system-audio chunk, at lag offsets of a few tenths of a millisecond either
 * side. That's the acoustic speaker-to-mic air gap alone -- it ignores the
 * dominant source of delay entirely: Android's real playback-to-capture
 * round trip (AudioTrack -> HAL -> DAC -> speaker -> air -> mic -> ADC -> HAL
 * -> AudioRecord) commonly runs tens of milliseconds on typical hardware, and
 * well over 100ms on a non-"Pro Audio" device -- exactly the class of tablet
 * this app targets. Searching only +/-10 samples (~+/-0.2ms @48kHz) could
 * essentially never find where the real echo actually landed, so the
 * suppressor almost never engaged regardless of how loud or obvious the
 * bleed was -- the actual mechanism behind the echo persisting even with
 * this class already in the pipeline. [history] keeps the last [HISTORY_MS]
 * of reference audio so *this* mic chunk can be matched against reference
 * audio from up to that far back, not just the same instant.
 *
 * ## Cost
 * A cheap coarse sweep across the full history (stepped, not per-sample) plus
 * a fine, per-sample refinement around both the coarse winner and the
 * previous tick's locked-in lag (so a good lock stays cheap to track frame to
 * frame without needing the full sweep to "re-find" it every time) -- a few
 * hundred short dot-products per 20ms chunk, on the order of tens of millions
 * of multiply-adds/sec, negligible next to the actual encode/mix work already
 * happening on this thread and nowhere close to a new source of the CPU/heat
 * load issue 3 asks to reduce.
 *
 * Deliberately NOT a full NLMS/adaptive echo canceller: an under-tuned
 * adaptive filter can diverge and produce artifacts (warble, pumping) that
 * are worse than the bleed it's meant to remove, and that kind of tuning
 * needs a real device/room to get right, not a static review. This stays the
 * safer, bounded alternative -- a smoothed, *capped* suppression gain that
 * never fully mutes the mic, so speech spoken *over* game audio still comes
 * through, just less attenuated than moments that are pure bleed with no
 * voice in them at all.
 */
class ResidualBleedSuppressor(
    sampleRate: Int,
    /** How hard this layer is allowed to pull the mic down once it's
     * confident a chunk is bleed -- see [com.recorderx.app.settings.BleedSuppressionMode].
     * The original tuning (20dB, a 60ms attack) turned out to be too gentle
     * for the case that actually matters most: a loud, percussive game sound
     * (an explosion, gunfire) played through the phone's own speaker at
     * volume with no headset, a few cm from the mic -- reported back as
     * "the same explosion sound plays twice." NORMAL below raises the
     * ceiling and quickens the attack from those original values; STRONG
     * goes further for anyone whose device/content still isn't fully
     * cleaned up at NORMAL. */
    strength: BleedSuppressionMode = BleedSuppressionMode.NORMAL
) {
    private val maxSuppressionDb: Float
    private val correlationGate: Float
    private val attackMs: Float
    private val releaseMs: Float

    init {
        when (strength) {
            BleedSuppressionMode.OFF -> {
                // Never actually consulted -- AudioMixEngine skips this class
                // entirely at OFF -- but every field still needs a value.
                maxSuppressionDb = 0f
                correlationGate = 1f
                attackMs = ATTACK_MS_NORMAL
                releaseMs = RELEASE_MS_NORMAL
            }
            BleedSuppressionMode.NORMAL -> {
                maxSuppressionDb = MAX_SUPPRESSION_DB_NORMAL
                correlationGate = CORRELATION_GATE_NORMAL
                attackMs = ATTACK_MS_NORMAL
                releaseMs = RELEASE_MS_NORMAL
            }
            BleedSuppressionMode.STRONG -> {
                maxSuppressionDb = MAX_SUPPRESSION_DB_STRONG
                correlationGate = CORRELATION_GATE_STRONG
                attackMs = ATTACK_MS_STRONG
                releaseMs = RELEASE_MS_NORMAL
            }
        }
    }

    // Rolling history of reference (system-audio) samples, long enough to
    // cover HISTORY_MS of real-world playback+capture round-trip latency.
    // Shifted (not modulo-indexed) each chunk: simpler to reason about
    // correctly than ring-buffer index math, and a ~40KB arraycopy at 50Hz is
    // trivial next to the correlation search itself.
    private val historyCapacity = msToSamples(sampleRate, HISTORY_MS)
    private val history = FloatArray(historyCapacity)
    private var historyFilled = 0

    // 0 = no extra suppression right now, 1 = fully at maxSuppressionDb.
    private var smoothedSuppression = 0f

    // Last lag (in samples, "reference audio this far back explains today's
    // mic energy") that produced a confident match -- re-centers next tick's
    // fine search so a good lock is cheap to keep, not just cheap to find once.
    private var lockedLagSamples = 0

    private val coarseStepSamples = max(1, msToSamples(sampleRate, COARSE_STEP_MS))
    private val fineRangeSamples = max(4, msToSamples(sampleRate, FINE_RANGE_MS))

    /**
     * [referenceMono] is this tick's system-audio chunk, already downmixed to
     * mono and normalized to roughly -1..1 (see [AudioMixEngine.mixLoop] for
     * how it's built). [mic] is the raw mic chunk for the *same* tick; both
     * must be [frames] long. Returns a 0..1 multiplier for this chunk's mic
     * samples, applied on top of whatever gain the caller already uses.
     */
    fun nextMicSuppression(referenceMono: FloatArray, mic: ShortArray, frames: Int, chunkDurationMs: Float): Float {
        if (frames <= 0) return 1f
        pushHistory(referenceMono, frames)

        val refRms = rms(referenceMono, frames)
        if (refRms < REFERENCE_GATE || historyFilled < frames + fineRangeSamples) {
            // System audio isn't loud enough right now to plausibly be the
            // source of any mic bleed, or there isn't enough history yet
            // (first moment of a fresh recording/resume) -- relax back
            // toward no suppression rather than holding a stale value.
            return advance(0f, chunkDurationMs)
        }

        val (bestLag, correlation) = bestCorrelation(mic, frames)
        lockedLagSamples = bestLag

        // Below the gate, today's mic content reads as independent of the
        // reference (real speech, room noise, silence) -- leave it alone.
        // Above it, map the remaining headroom to 0..1 suppression strength.
        val bleedLikelihood = ((correlation - correlationGate) / (1f - correlationGate)).coerceIn(0f, 1f)
        return advance(bleedLikelihood, chunkDurationMs)
    }

    /** Appends [frames] new reference samples, dropping the oldest [frames]
     * to keep [history]'s length fixed. */
    private fun pushHistory(referenceMono: FloatArray, frames: Int) {
        if (frames >= historyCapacity) {
            // Pathological (chunk bigger than the whole history window) --
            // just keep the tail; never happens with this app's real 20ms
            // chunk sizes against a >=100ms history, but stay safe regardless.
            System.arraycopy(referenceMono, frames - historyCapacity, history, 0, historyCapacity)
            historyFilled = historyCapacity
            return
        }
        System.arraycopy(history, frames, history, 0, historyCapacity - frames)
        System.arraycopy(referenceMono, 0, history, historyCapacity - frames, frames)
        historyFilled = min(historyCapacity, historyFilled + frames)
    }

    /** Normalized cross-correlation of [mic] against [history], maximized over
     * every plausible echo delay. [lag] means "the reference audio that
     * echoes into today's mic instant sits [lag] samples before the most
     * recent history sample." Two passes: a stepped coarse sweep across the
     * *entire* history (cheap: skips most sample positions), then a
     * per-sample fine refinement around both that coarse winner and last
     * tick's locked lag (covers real drift between the two independently
     * clocked audio paths without needing the coarse sweep to re-find a lock
     * that's already good). */
    private fun bestCorrelation(mic: ShortArray, frames: Int): Pair<Int, Float> {
        val micNorm = FloatArray(frames)
        var micEnergy = 0.0
        for (i in 0 until frames) {
            val m = mic[i] / 32768f
            micNorm[i] = m
            micEnergy += m.toDouble() * m
        }
        if (micEnergy < MIN_ENERGY) return lockedLagSamples to 0f
        val micNormSqrt = sqrt(micEnergy)

        val maxLag = historyFilled - frames
        if (maxLag < 0) return lockedLagSamples to 0f

        var bestLag = 0
        var bestCorr = -1.0

        fun scoreAt(lag: Int) {
            if (lag < 0 || lag > maxLag) return
            val start = historyFilled - frames - lag
            var dot = 0.0
            var refEnergy = 0.0
            for (i in 0 until frames) {
                val r = history[start + i]
                dot += r.toDouble() * micNorm[i]
                refEnergy += r.toDouble() * r
            }
            if (refEnergy < MIN_ENERGY) return
            val corr = abs(dot) / (sqrt(refEnergy) * micNormSqrt)
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        var lag = 0
        while (lag <= maxLag) {
            scoreAt(lag)
            lag += coarseStepSamples
        }

        val coarseWinner = bestLag
        for (l in (coarseWinner - coarseStepSamples + 1) until (coarseWinner + coarseStepSamples)) scoreAt(l)
        for (l in (lockedLagSamples - fineRangeSamples)..(lockedLagSamples + fineRangeSamples)) scoreAt(l)

        return bestLag to bestCorr.toFloat().coerceIn(0f, 1f)
    }

    private fun advance(target: Float, chunkDurationMs: Float): Float {
        val timeConstantMs = if (target > smoothedSuppression) attackMs else releaseMs
        val step = (chunkDurationMs / timeConstantMs).coerceIn(0f, 1f)
        smoothedSuppression += (target - smoothedSuppression) * step
        val suppressionDb = smoothedSuppression * maxSuppressionDb
        return dbToLinear(-suppressionDb)
    }

    private fun rms(buf: FloatArray, frames: Int): Float {
        var sumSquares = 0.0
        for (i in 0 until frames) sumSquares += buf[i].toDouble() * buf[i]
        return sqrt(sumSquares / frames).toFloat()
    }

    private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    companion object {
        private fun msToSamples(sampleRate: Int, ms: Int): Int = sampleRate / 1000 * ms

        // Long enough to cover realistic non-"Pro Audio" Android round-trip
        // playback+capture latency with margin -- see class kdoc for why this
        // (not a sub-millisecond acoustic-only gap) is the number that matters.
        private const val HISTORY_MS = 220

        // ~1ms per coarse step and a ~1ms fine radius: enough resolution
        // that the fine pass always lands within one coarse step of the true
        // peak, cheap enough that the full sweep is a few hundred short
        // dot-products per chunk (see class kdoc's Cost section).
        private const val COARSE_STEP_MS = 1
        private const val FINE_RANGE_MS = 1

        private const val REFERENCE_GATE = 0.015f
        private const val MIN_ENERGY = 1e-6

        // Correlation below this reads as independent content (real speech,
        // ambient noise) and is left untouched; only the remaining headroom
        // above it maps into suppression strength. Lower at STRONG so more
        // borderline content still counts as "probably bleed."
        private const val CORRELATION_GATE_NORMAL = 0.30f
        private const val CORRELATION_GATE_STRONG = 0.22f

        // Never fully mutes the mic: caps how much this layer can pull it
        // down so speech spoken *over* game audio still comes through,
        // attenuated less than moments that are pure bleed with no voice in
        // them. The original single value here was 20dB, which real-world
        // reports ("a loud game explosion still audibly plays twice") showed
        // wasn't enough headroom for genuinely loud, percussive
        // speaker-to-mic bleed with no headset -- NORMAL below raises that
        // ceiling; STRONG goes further still. Ear-tuning these exact numbers
        // against real content/devices -- like the DSP constants in
        // DuckingProcessor -- is exactly the sort of thing ARCHITECTURE.md
        // already flags as benefiting from real hardware.
        private const val MAX_SUPPRESSION_DB_NORMAL = 26f
        private const val MAX_SUPPRESSION_DB_STRONG = 34f

        // How quickly this layer clamps down once bleed is detected. The
        // original 60ms attack was slow enough that a short, percussive
        // transient (an explosion, a gunshot) could already be most of the
        // way through before suppression caught up with it -- exactly the
        // content most likely to be reported as "plays twice." NORMAL below
        // is snappier; STRONG snappier still. Release is left alone (220ms)
        // at both strengths -- easing back up slowly is what avoids audible
        // pumping/chattering once the bleed passes, and that part wasn't
        // what was reported as wrong.
        private const val ATTACK_MS_NORMAL = 35f
        private const val ATTACK_MS_STRONG = 20f
        private const val RELEASE_MS_NORMAL = 220f
    }
}
