package com.recorderx.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.annotation.RequiresApi
import com.recorderx.app.settings.MicGainMode

/**
 * Captures OTHER apps' playback (music, game audio, video...) directly from
 * the audio pipeline -- never through the microphone/speaker -- which is
 * exactly what keeps it from doubling up with mic input. Requires the same
 * [MediaProjection] token the screen capture uses; the platform itself
 * enforces per-app opt-out (apps that mark their audio ALLOW_CAPTURE_BY_NONE,
 * or that target < Android 10 without opting in, are silently excluded --
 * RecorderX doesn't try to work around that, by design).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioSource(
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int,
    private val channelMask: Int
) {
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller before this is ever constructed
    fun start(): Boolean {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // Deliberately NOT included: USAGE_VOICE_COMMUNICATION, USAGE_NOTIFICATION*,
            // USAGE_ALARM. We only want media/game playback, never call audio or
            // intrusive system sounds, and the platform blocks call-audio capture
            // through this API regardless.
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: UnsupportedOperationException) {
            null
        }

        audioRecord = record
        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            release()
            return false
        }
        record.startRecording()
        return true
    }

    /** Blocking read of interleaved PCM16 samples. Returns the number of
     * shorts actually read (0 or negative on underrun/error/stopped). */
    fun read(buffer: ShortArray, sampleCount: Int): Int =
        audioRecord?.read(buffer, 0, sampleCount) ?: 0

    fun release() {
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            // Wasn't recording -- nothing to stop.
        }
        audioRecord?.release()
        audioRecord = null
    }
}

/**
 * Microphone capture on its own dedicated AudioRecord, with platform echo
 * cancellation / noise suppression / (optionally) automatic gain control
 * layered on when the device offers them. This is what keeps speaker output
 * from being picked back up by the mic and re-mixed a second time on top of
 * the direct system-audio tap above.
 */
class MicAudioSource(
    private val sampleRate: Int,
    private val channelMask: Int,
    private val gainMode: MicGainMode
) {
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller before this is ever constructed
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false

        // VOICE_COMMUNICATION routes through the platform's voice processing
        // chain (built for exactly the echo/noise problem this app has to
        // solve), which is a better starting point for "clean, professional
        // mix" than the plain MIC source. Not every device exposes a usable
        // AudioRecord for it, so we fall back to MIC if it fails to initialize.
        var record = tryCreate(MediaRecorder.AudioSource.VOICE_COMMUNICATION, minBuf)
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            record = tryCreate(MediaRecorder.AudioSource.MIC, minBuf)
        }

        audioRecord = record
        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            release()
            return false
        }

        val sessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        }
        if (gainMode == MicGainMode.AUTO && AutomaticGainControl.isAvailable()) {
            automaticGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
        }

        record.startRecording()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun tryCreate(source: Int, minBuf: Int): AudioRecord? = try {
        AudioRecord(source, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
    } catch (e: Exception) {
        null
    }

    fun read(buffer: ShortArray, sampleCount: Int): Int =
        audioRecord?.read(buffer, 0, sampleCount) ?: 0

    fun release() {
        echoCanceler?.release()
        noiseSuppressor?.release()
        automaticGainControl?.release()
        echoCanceler = null
        noiseSuppressor = null
        automaticGainControl = null
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            // Wasn't recording -- nothing to stop.
        }
        audioRecord?.release()
        audioRecord = null
    }
}
