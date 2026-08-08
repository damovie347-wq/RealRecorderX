package com.recorderx.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Surface
import com.recorderx.app.codec.CodecChoice
import com.recorderx.app.settings.BitrateMode
import java.util.concurrent.atomic.AtomicLong

/**
 * The zero-copy path the spec asks for: SurfaceFlinger composites frames
 * *directly* into [MediaCodec]'s input Surface via GPU (no Bitmap, no
 * ImageReader, no onscreen readback, no CPU copy anywhere in this class) --
 * MediaProjection's VirtualDisplay is pointed straight at [createInputSurface],
 * and this class only ever touches the *encoded* output on the other side.
 */
class VideoEncoderPipeline(
    private val muxerController: MuxerController,
    private val onError: (Throwable) -> Unit
) {
    private lateinit var codec: MediaCodec
    private lateinit var inputSurface: Surface
    private var trackIndex = -1
    private var drainThread: Thread? = null

    @Volatile private var stopRequested = false

    // Rolling byte counter AdaptiveBitrateController samples to see actual
    // recent output rate vs. the configured target.
    private val bytesSinceLastSample = AtomicLong(0)

    fun configure(choice: CodecChoice, bitrate: Int, fps: Int, bitrateMode: BitrateMode): Surface {
        val format = MediaFormat.createVideoFormat(choice.mimeType, choice.width, choice.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            val modeConst = if (bitrateMode == BitrateMode.VBR) {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            } else {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            }
            setInteger(MediaFormat.KEY_BITRATE_MODE, modeConst)
        }

        codec = MediaCodec.createByCodecName(choice.codecName)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        return inputSurface
    }

    fun startDraining() {
        val t = Thread({ drainLoop() }, "RecorderX-VideoEncoder")
        drainThread = t
        t.start()
    }

    private fun drainLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            loop@ while (true) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxerController.registerTrack(codec.outputFormat)
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Nothing ready yet; loop back and wait again. This is the
                        // normal idle state between frames, not an error.
                    }
                    outIndex >= 0 -> {
                        val isConfigBuffer = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (isConfigBuffer) {
                            // Config bytes are already captured via INFO_OUTPUT_FORMAT_CHANGED;
                            // writing them again as a sample would corrupt the stream.
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0) {
                            val encodedData = codec.getOutputBuffer(outIndex)
                            if (encodedData != null && trackIndex >= 0) {
                                if (!muxerController.isStarted()) muxerController.awaitStarted()
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                bytesSinceLastSample.addAndGet(bufferInfo.size.toLong())
                                muxerController.writeSample(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break@loop
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (!stopRequested) onError(t)
        } finally {
            releaseInternal()
        }
    }

    /** Reads and resets the byte counter -- called roughly every few seconds by
     * AdaptiveBitrateController to estimate the encoder's actual recent output rate. */
    fun pollBytesSinceLastSample(): Long = bytesSinceLastSample.getAndSet(0)

    /** Live bitrate nudge. Widely supported on hardware encoders; wrapped
     * defensively since not every device/codec honors mid-stream changes. */
    fun applyBitrate(newBitrateBps: Int) {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
            codec.setParameters(params)
        } catch (e: Exception) {
            // Not fatal -- some encoders simply ignore this. AdaptiveBitrateController
            // still has its bounded target math even if the live nudge is a no-op here.
        }
    }

    /** Best-effort thermal relief valve for severe thermal states: asks the
     * encoder to internally drop input frames above [maxFps]. This key
     * (API 31+, "max-fps-to-encoder") isn't honored by every encoder, so it's
     * used as a bonus on top of -- never instead of -- bitrate throttling. */
    fun tryLimitInputFrameRate(maxFps: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val params = Bundle()
            params.putInt("max-fps-to-encoder", maxFps)
            codec.setParameters(params)
        } catch (e: Throwable) {
            // Silently ignored by design -- see kdoc above.
        }
    }

    /** Signals end-of-stream on the *Surface* input (the correct mechanism for
     * surface-input encoders -- there's no accessible input ByteBuffer to stamp
     * an EOS flag on manually) and lets the drain loop above finish naturally. */
    fun requestStop() {
        stopRequested = true
        try {
            codec.signalEndOfInputStream()
        } catch (e: Exception) {
            // If the codec is already in a bad state there's nothing further to
            // signal; releaseInternal() in the drain loop's finally block still runs.
        }
    }

    fun awaitFinished(timeoutMs: Long) {
        drainThread?.join(timeoutMs)
    }

    private fun releaseInternal() {
        try { codec.stop() } catch (e: Exception) { /* already stopped/errored */ }
        try { codec.release() } catch (e: Exception) { /* already released */ }
        try { inputSurface.release() } catch (e: Exception) { /* already released */ }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}
