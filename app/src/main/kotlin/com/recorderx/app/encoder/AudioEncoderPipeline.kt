package com.recorderx.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import java.nio.ByteOrder

/**
 * Unlike the video path, audio has no Surface -- AudioMixEngine hands us
 * already-mixed, already-leveled PCM16 samples and this class's only job is
 * to push them through the AAC-LC encoder and into the shared muxer.
 */
class AudioEncoderPipeline(
    private val muxerController: MuxerController,
    private val onError: (Throwable) -> Unit
) {
    private lateinit var codec: MediaCodec
    private var trackIndex = -1
    private var drainThread: Thread? = null

    @Volatile private var stopRequested = false

    fun configure(sampleRate: Int, channelCount: Int, bitrateBps: Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun startDraining() {
        val t = Thread({ drainLoop() }, "RecorderX-AudioEncoder")
        drainThread = t
        t.start()
    }

    /**
     * Pushes one chunk of interleaved PCM16 samples (mono or stereo per the
     * resolved [com.recorderx.app.settings.AudioChannelMode]) into the encoder.
     * [presentationTimeUs] must be computed by the caller from the running
     * audio-frame count, not wall-clock time, so drift can't creep in chunk by chunk.
     * Non-blocking by design: if the encoder has no free input buffer right now,
     * this chunk is dropped rather than stalling the realtime mixer thread --
     * losing an occasional ~20ms chunk under heavy load is inaudible; a stalled
     * mixer thread causing a growing capture backlog is not.
     */
    fun feedPcm(pcm: ShortArray, sampleCount: Int, presentationTimeUs: Long) {
        if (stopRequested) return
        val inputIndex = try {
            codec.dequeueInputBuffer(0)
        } catch (e: IllegalStateException) {
            return
        }
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        // Bulk short-buffer put instead of a manual per-sample byte loop --
        // identical output bytes (explicit little-endian, matching what the
        // old manual loop wrote: low byte first), just without a Kotlin-level
        // loop iteration + two put() calls per sample, 50 times/sec for the
        // life of the recording. A small, free reduction in per-chunk CPU work.
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.asShortBuffer().put(pcm, 0, sampleCount)
        val byteCount = sampleCount * 2
        codec.queueInputBuffer(inputIndex, 0, byteCount, presentationTimeUs, 0)
    }

    fun requestStop() {
        stopRequested = true
        try {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        } catch (e: Exception) {
            // Best-effort EOS; the drain loop's finally block still cleans up either way.
        }
    }

    private fun drainLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            loop@ while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxerController.registerTrack(codec.outputFormat)
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Idle between chunks -- normal, keep waiting.
                    }
                    outIndex >= 0 -> {
                        val isConfigBuffer = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (isConfigBuffer) bufferInfo.size = 0
                        if (bufferInfo.size != 0) {
                            val encodedData = codec.getOutputBuffer(outIndex)
                            if (encodedData != null && trackIndex >= 0) {
                                if (!muxerController.isStarted()) muxerController.awaitStarted()
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxerController.writeSample(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break@loop
                    }
                }
            }
        } catch (t: Throwable) {
            if (!stopRequested) onError(t)
        } finally {
            releaseInternal()
        }
    }

    fun awaitFinished(timeoutMs: Long) {
        drainThread?.join(timeoutMs)
    }

    private fun releaseInternal() {
        try { codec.stop() } catch (e: Exception) { /* already stopped/errored */ }
        try { codec.release() } catch (e: Exception) { /* already released */ }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
