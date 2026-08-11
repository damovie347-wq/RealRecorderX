package com.recorderx.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.recorderx.app.settings.MicGainMode

/**
 * Captures OTHER apps' playback (music, game audio, video...) directly from
 * the audio pipeline -- never through the microphone/speaker -- which is
 * exactly what keeps it from doubling up with mic input. Requires the same
 * [MediaProjection] token the screen capture uses; the platform itself
 * enforces per-app opt-out (apps that mark their audio ALLOW_CAPTURE_BY_NONE,
 * or that target < Android 10 without opting in, are silently excluded --
 * RecorderX doesn't try to work around that, by design. If a specific app's
 * audio never shows up, check what API level *that app* targets first).
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
        if (minBuf <= 0) {
            Log.w(TAG, "start(): getMinBufferSize returned $minBuf for rate=$sampleRate mask=$channelMask -- aborting")
            return false
        }

        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "start(): AudioRecord.Builder().build() threw", e)
            null
        }

        audioRecord = record
        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "start(): AudioRecord state=${record?.state} (not STATE_INITIALIZED) -- system audio capture unavailable this session")
            release()
            return false
        }
        record.startRecording()
        val recording = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        Log.i(TAG, "start(): system audio capture started, recordingState=${record.recordingState} (recording=$recording)")
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

    companion object {
        private const val TAG = "SystemAudioSource"
    }
}

/**
 * Microphone capture on its own dedicated AudioRecord, with platform echo
 * cancellation / noise suppression / (optionally) automatic gain control
 * layered on when the device offers them. This is what keeps speaker output
 * from being picked back up by the mic and re-mixed a second time on top of
 * the direct system-audio tap above.
 *
 * Uses [MediaRecorder.AudioSource.MIC] rather than VOICE_COMMUNICATION. An
 * earlier version tried VOICE_COMMUNICATION first for its built-in
 * echo-cancellation reputation, but that source is designed around two-way
 * call audio and expects [android.media.AudioManager]'s mode to be set to
 * MODE_IN_COMMUNICATION to route correctly -- this app never touches
 * AudioManager's mode (doing so has broader side effects on the device's
 * audio routing while recording, which a screen recorder shouldn't be
 * causing). Without that mode set, VOICE_COMMUNICATION can report
 * STATE_INITIALIZED successfully while actually capturing silence or
 * heavily-attenuated audio on some OEM audio HALs -- a failure mode that
 * previous initialization-only health checks couldn't detect. Plain MIC has
 * no such coupling; AcousticEchoCanceler/NoiseSuppressor below are attached
 * explicitly regardless of source and don't depend on call-mode state.
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
        if (minBuf <= 0) {
            Log.w(TAG, "start(): getMinBufferSize returned $minBuf for rate=$sampleRate mask=$channelMask -- aborting")
            return false
        }

        var record = tryCreate(MediaRecorder.AudioSource.MIC, minBuf)
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "start(): AudioSource.MIC failed to initialize (state=${record?.state}), trying CAMCORDER as fallback")
            record?.release()
            record = tryCreate(MediaRecorder.AudioSource.CAMCORDER, minBuf)
        }

        audioRecord = record
        if (record?.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "start(): no mic AudioSource could initialize -- mic capture unavailable this session")
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
        val recording = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        Log.i(
            TAG,
            "start(): mic capture started, recordingState=${record.recordingState} (recording=$recording), " +
                "aec=${echoCanceler != null} ns=${noiseSuppressor != null} agc=${automaticGainControl != null}"
        )
        return true
    }

    @SuppressLint("MissingPermission")
    private fun tryCreate(source: Int, minBuf: Int): AudioRecord? = try {
        AudioRecord(source, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
    } catch (e: Exception) {
        Log.w(TAG, "tryCreate(source=$source) threw", e)
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

    companion object {
        private const val TAG = "MicAudioSource"
    }
}
