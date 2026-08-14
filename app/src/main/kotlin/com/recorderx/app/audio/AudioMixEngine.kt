package com.recorderx.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
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
    private val context: Context,
    private val mediaProjection: MediaProjection?,
    private val settings: RecordingSettings,
    private val audioEncoder: AudioEncoderPipeline,
    private val sampleRate: Int,
    /** Audio frames already fed to the encoder in earlier (pre-pause) segments
     * of this same recording session. Without this, each new AudioMixEngine
     * instance created on resume would start computing PTS from 0 again,
     * overlapping the timestamps of audio already written before the pause --
     * see RecordingService#resumeInternal. */
    private val startFrameOffset: Long = 0L,
    private val onError: (Throwable) -> Unit
) {
    private var systemSource: SystemAudioSource? = null
    private var micSource: MicAudioSource? = null
    private val ducking = DuckingProcessor(settings.voicePriority)

    // Second-stage defense against system audio bleeding back into the mic
    // through the speaker, on top of MicAudioSource's platform AEC -- see
    // ResidualBleedSuppressor's kdoc for why AEC alone often isn't enough
    // for loud game/media content. Only ever engages when both sources are
    // actually active (see mixLoop); a no-op the rest of the time.
    private val bleedSuppressor = ResidualBleedSuppressor(sampleRate)

    private var mixThread: Thread? = null
    @Volatile private var running = false

    /** Total frames fed to the encoder so far (including [startFrameOffset]),
     * read by RecordingService when pausing so the *next* AudioMixEngine
     * instance can continue the same PTS timeline. */
    @Volatile var totalFramesWritten: Long = startFrameOffset
        private set

    /** Resolved once at [start] time from the settings + what hardware is
     * actually available, per "Auto doesn't fake stereo when the mic can't
     * offer it, and preserves real stereo for system audio when it can." */
    var effectiveChannelCount: Int = 1
        private set

    /** What actually ended up capturing, *after* [start] -- read this rather
     * than assuming the requested [RecordingSettings.audioSource] was
     * achieved. RecordingService surfaces a toast when this disagrees with
     * what the user asked for, instead of silently producing a quiet track. */
    var hasSystemAudio: Boolean = false
        private set
    var hasMicAudio: Boolean = false
        private set

    fun start(): Boolean {
        val hasRecordAudioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val wantsSystem = settings.audioSource.wantsSystem &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mediaProjection != null
        val wantsMic = settings.audioSource.wantsMic

        Log.i(
            TAG,
            "start(): audioSource=${settings.audioSource} wantsSystem=$wantsSystem wantsMic=$wantsMic " +
                "RECORD_AUDIO granted=$hasRecordAudioPermission sdk=${Build.VERSION.SDK_INT}"
        )

        if ((wantsSystem || wantsMic) && !hasRecordAudioPermission) {
            Log.w(TAG, "start(): RECORD_AUDIO not granted -- no audio will be captured this session")
        }

        effectiveChannelCount = resolveChannelCount(settings.audioChannel, systemStereoAvailable = wantsSystem)

        if (wantsSystem && hasRecordAudioPermission) {
            val src = SystemAudioSource(mediaProjection!!, sampleRate, AudioFormat.CHANNEL_IN_STEREO)
            if (src.start()) {
                systemSource = src
                hasSystemAudio = true
            } else {
                // System-audio tap failed to init -- often because the specific
                // foreground app opts out of (or predates) playback capture, not
                // a bug in this app. Keep going with whatever else was requested
                // instead of failing the whole recording over one audio path.
                src.release()
            }
        }
        if (wantsMic && hasRecordAudioPermission) {
            val src = MicAudioSource(sampleRate, AudioFormat.CHANNEL_IN_MONO, settings.micGain)
            if (src.start()) {
                micSource = src
                hasMicAudio = true
            } else {
                src.release()
            }
        }

        Log.i(TAG, "start(): result hasSystemAudio=$hasSystemAudio hasMicAudio=$hasMicAudio channelCount=$effectiveChannelCount")

        if (systemSource == null && micSource == null) {
            // Nothing to record (either Audio Source == OFF, permission missing,
            // or every capture path we tried failed to initialize). Caller
            // treats this as "no audio track," not as a fatal recording error.
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
        // Reused every chunk (never reallocated inside the loop) to keep the
        // realtime mixer thread free of per-chunk GC churn -- same reasoning
        // as systemBuf/micBuf/outBuf above.
        val referenceMono = FloatArray(framesPerChunk)

        val systemLevel = settings.systemLevelPercent / 100f
        val micLevelBase = settings.micLevelPercent / 100f
        val micGainMultiplier = if (settings.micGain == MicGainMode.AUTO) 1f else settings.micGain.linearGain
        val micLevel = micLevelBase * micGainMultiplier

        var framesWritten = startFrameOffset
        val chunkDurationMs = framesPerChunk * 1000f / sampleRate

        try {
            while (running) {
                val haveSystem = systemSource?.read(systemBuf, systemBuf.size)?.let { it > 0 } == true
                val haveMic = micSource?.read(micBuf, micBuf.size)?.let { it > 0 } == true

                val isSpeaking = if (haveMic) ducking.updateSpeechState(micBuf, framesPerChunk) else false
                val duckGain = ducking.nextSystemGain(isSpeaking, chunkDurationMs)

                // Only meaningful -- and only possible -- when both sources
                // are live this chunk: with no system-audio reference to
                // compare against, there's nothing to detect bleed *from*.
                val micSuppression = if (haveSystem && haveMic) {
                    for (i in 0 until framesPerChunk) {
                        referenceMono[i] = ((systemBuf[i * 2] + systemBuf[i * 2 + 1]) * 0.5f) / 32768f
                    }
                    bleedSuppressor.nextMicSuppression(referenceMono, micBuf, framesPerChunk, chunkDurationMs)
                } else {
                    1f
                }

                when (effectiveChannelCount) {
                    1 -> mixToMono(systemBuf, haveSystem, micBuf, haveMic, outBuf, framesPerChunk, systemLevel * duckGain, micLevel * micSuppression)
                    else -> mixToStereo(systemBuf, haveSystem, micBuf, haveMic, outBuf, framesPerChunk, systemLevel * duckGain, micLevel * micSuppression)
                }

                val presentationTimeUs = framesWritten * 1_000_000L / sampleRate
                audioEncoder.feedPcm(outBuf, outBuf.size, presentationTimeUs)
                framesWritten += framesPerChunk
                totalFramesWritten = framesWritten
            }
        } catch (t: Throwable) {
            onError(t)
        }
        // No audioEncoder.requestStop() here: this loop also exits on every
        // *pause* (via stop()), not just the final stop, and the encoder is a
        // long-lived object meant to keep accepting input across a resume.
        // RecordingService.handleStop() signals the encoder's real EOS
        // explicitly, exactly once, when the recording actually ends.
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
        private const val TAG = "AudioMixEngine"
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
