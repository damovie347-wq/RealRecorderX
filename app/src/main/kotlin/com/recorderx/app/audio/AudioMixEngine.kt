package com.recorderx.app.audio

import android.media.AudioFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import com.recorderx.app.encoder.AudioEncoderPipeline
import com.recorderx.app.settings.AudioChannelMode
import com.recorderx.app.settings.MicGainMode
import com.recorderx.app.settings.RecordingSettings
import kotlin.math.abs
import kotlin.math.sign

/**
 * Owns the whole audio side of a recording session: opens whichever of
 * [SystemAudioSource] / [MicAudioSource] the settings call for (never both
 * through the same AudioRecord -- that's the actual mechanism behind "system
 * and mic audio are never taken from the same channel"), mixes them down to
 * the resolved output channel count, and feeds the result to [audioEncoder].
 */
class AudioMixEngine(
    private val mediaProjection: MediaProjection?,
    private val settings: RecordingSettings,
    private val audioEncoder: AudioEncoderPipeline,
    private val sampleRate: Int,
    private val onError: (Throwable) -> Unit
) {
    private var systemSource: SystemAudioSource? = null
    private var micSource: MicAudioSource? = null
    private val ducking = DuckingProcessor(settings.voicePriority)

    private var mixThread: Thread? = null
    @Volatile private var running = false

    /** Resolved once at [start] time from the settings + what hardware is
     * actually available, per "Auto doesn't fake stereo when the mic can't
     * offer it, and preserves real stereo for system audio when it can." */
    var effectiveChannelCount: Int = 1
        private set

    fun start(): Boolean {
        val wantsSystem = settings.audioSource.wantsSystem &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mediaProjection != null
        val wantsMic = settings.audioSource.wantsMic

        effectiveChannelCount = resolveChannelCount(settings.audioChannel, systemStereoAvailable = wantsSystem)

        if (wantsSystem) {
            val src = SystemAudioSource(mediaProjection!!, sampleRate, AudioFormat.CHANNEL_IN_STEREO)
            if (src.start()) {
                systemSource = src
            } else {
                // System-audio tap failed to init (rare, but device-dependent) --
                // keep going with whatever else was requested instead of failing
                // the whole recording over a non-essential audio path.
                src.release()
            }
        }
        if (wantsMic) {
            val src = MicAudioSource(sampleRate, AudioFormat.CHANNEL_IN_MONO, settings.micGain)
            if (src.start()) {
                micSource = src
            } else {
                src.release()
            }
        }

        if (systemSource == null && micSource == null) {
            // Nothing to record (either Audio Source == OFF, or every capture
            // path we tried failed to initialize). Caller treats this as "no
            // audio track," not as a fatal recording error.
            return false
        }

        running = true
        val t = Thread({ mixLoop() }, "RecorderX-AudioMixer")
        mixThread = t
        t.start()
        return true
    }

    fun stop() {
        running = false
        mixThread?.join(1000)
        systemSource?.release()
        micSource?.release()
    }

    private fun mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val framesPerChunk = sampleRate / 50 // 20ms chunks
        val systemBuf = ShortArray(framesPerChunk * 2) // stereo interleaved
        val micBuf = ShortArray(framesPerChunk)
        val outBuf = ShortArray(framesPerChunk * effectiveChannelCount)

        val systemLevel = settings.systemLevelPercent / 100f
        val micLevelBase = settings.micLevelPercent / 100f
        val micGainMultiplier = if (settings.micGain == MicGainMode.AUTO) 1f else settings.micGain.linearGain
        val micLevel = micLevelBase * micGainMultiplier

        var framesWritten = 0L
        val chunkDurationMs = framesPerChunk * 1000f / sampleRate

        try {
            while (running) {
                val haveSystem = systemSource?.read(systemBuf, systemBuf.size)?.let { it > 0 } == true
                val haveMic = micSource?.read(micBuf, micBuf.size)?.let { it > 0 } == true

                val isSpeaking = if (haveMic) ducking.updateSpeechState(micBuf, framesPerChunk) else false
                val duckGain = ducking.nextSystemGain(isSpeaking, chunkDurationMs)

                when (effectiveChannelCount) {
                    1 -> mixToMono(systemBuf, haveSystem, micBuf, haveMic, outBuf, framesPerChunk, systemLevel * duckGain, micLevel)
                    else -> mixToStereo(systemBuf, haveSystem, micBuf, haveMic, outBuf, framesPerChunk, systemLevel * duckGain, micLevel)
                }

                val presentationTimeUs = framesWritten * 1_000_000L / sampleRate
                audioEncoder.feedPcm(outBuf, outBuf.size, presentationTimeUs)
                framesWritten += framesPerChunk
            }
        } catch (t: Throwable) {
            onError(t)
        } finally {
            audioEncoder.requestStop()
        }
    }

    private fun mixToMono(
        systemBuf: ShortArray, haveSystem: Boolean,
        micBuf: ShortArray, haveMic: Boolean,
        out: ShortArray, frames: Int,
        systemGain: Float, micGain: Float
    ) {
        for (i in 0 until frames) {
            val sys = if (haveSystem) {
                val l = systemBuf[i * 2]
                val r = systemBuf[i * 2 + 1]
                ((l + r) * 0.5f) * systemGain
            } else 0f
            val mic = if (haveMic) micBuf[i] * micGain else 0f
            out[i] = softClip(sys + mic)
        }
    }

    private fun mixToStereo(
        systemBuf: ShortArray, haveSystem: Boolean,
        micBuf: ShortArray, haveMic: Boolean,
        out: ShortArray, frames: Int,
        systemGain: Float, micGain: Float
    ) {
        for (i in 0 until frames) {
            val sysL = if (haveSystem) systemBuf[i * 2] * systemGain else 0f
            val sysR = if (haveSystem) systemBuf[i * 2 + 1] * systemGain else 0f
            // Mono mic is centered (equal in both channels), never artificially
            // widened -- see class kdoc / DuckingProcessor for the reasoning.
            val mic = if (haveMic) micBuf[i] * micGain else 0f
            out[i * 2] = softClip(sysL + mic)
            out[i * 2 + 1] = softClip(sysR + mic)
        }
    }

    /** Soft-knee limiter: leaves normal-level audio untouched but compresses
     * anything approaching full scale into the ceiling instead of hard-clipping
     * it, which is what keeps transient peaks (a sudden loud game explosion,
     * clapping into the mic) from producing audible digital distortion. */
    private fun softClip(x: Float): Short {
        val ax = abs(x)
        val limited = if (ax <= SOFT_CLIP_THRESHOLD) {
            x
        } else {
            val over = ax - SOFT_CLIP_THRESHOLD
            val compressed = SOFT_CLIP_THRESHOLD + over / (1f + over / SOFT_CLIP_KNEE)
            sign(x) * compressed
        }
        return limited.coerceIn(-32768f, 32767f).toInt().toShort()
    }

    companion object {
        private const val SOFT_CLIP_THRESHOLD = 26000f
        private const val SOFT_CLIP_KNEE = 6000f

        /** Public so RecordingService can resolve the same channel count *before*
         * constructing the AAC encoder (which needs it up front) and this engine
         * (which resolves it again internally) -- both calls are guaranteed to
         * agree because they run the same pure function. */
        fun resolveChannelCount(mode: AudioChannelMode, systemStereoAvailable: Boolean): Int = when (mode) {
            AudioChannelMode.MONO -> 1
            AudioChannelMode.STEREO -> 2
            AudioChannelMode.AUTO -> if (systemStereoAvailable) 2 else 1
        }
    }
}
