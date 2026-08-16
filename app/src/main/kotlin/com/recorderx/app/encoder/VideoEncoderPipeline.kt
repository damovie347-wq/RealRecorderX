package com.recorderx.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Process
import android.util.Log
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

    // Surface-input frames arrive with whatever timestamp SurfaceFlinger
    // stamped on them -- typically nanoseconds since *boot*, not since this
    // recording started. Left unrebased, the muxer ends up with a video
    // track whose PTS values are enormous (hours), which is exactly what
    // produced garbage durations like "222:03:25" in players/galleries.
    // Rebasing every sample against the first real frame's timestamp fixes
    // this; audio's PTS is already computed relative to 0 on the input side
    // (see AudioMixEngine), so only video needs this correction.
    private var sessionBaseUs: Long = -1L

    // Pause/resume releases and later recreates the VirtualDisplay against the
    // same input Surface (see RecordingService#pauseInternal), but the *raw*
    // timestamps SurfaceFlinger stamps on frames keep advancing in real time
    // regardless -- without correction, the first frame after a resume would
    // jump forward by however long the pause actually lasted. pauseOffsetUs
    // absorbs that gap; requestPauseRebase() arms it for the next real frame.
    private var pauseOffsetUs: Long = 0L
    private var lastEmittedPtsUs: Long = -1L
    @Volatile private var awaitingResumeRebase = false

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

            // Without this, several vendor drivers silently configure() into
            // their *lowest* profile/level (e.g. AVC Baseline) whenever the
            // format doesn't ask for one explicitly -- which caps both the
            // encoding tools available (no B-frames, no CABAC on Baseline)
            // and, via the paired level, the maximum bitrate the stream is
            // even allowed to reach, regardless of KEY_BIT_RATE above. This
            // is the actual mechanism behind "resolution and bitrate are
            // both set high, but it still doesn't look sharp." CodecSelector
            // already resolved the highest (profile, level) *this* encoder
            // actually advertises, so this can never request a combination
            // the hardware didn't list; 0 means "codec reported nothing,
            // leave its own default alone."
            if (choice.profile != 0) setInteger(MediaFormat.KEY_PROFILE, choice.profile)
            if (choice.level != 0) setInteger(MediaFormat.KEY_LEVEL, choice.level)

            // 10-bit needs no separate handling here beyond the profile line
            // above: CodecSelector already resolved a Main10-family profile
            // into choice.profile when ColorDepthOption.TEN_BIT was requested
            // and achievable, and KEY_COLOR_FORMAT stays COLOR_FormatSurface
            // either way -- Surface input's format is the same regardless of
            // sample depth, the codec's internal converter handles the
            // 8-to-10-bit widening from whatever SurfaceFlinger actually
            // composited (still 8-bit RGBA8888 for the overwhelming majority
            // of Android content -- see ColorDepthOption's kdoc for why this
            // is a wider container, not new detail, outside genuine HDR content).

            // Cap the *actual* input rate up front, not just reactively under
            // thermal stress -- see tryLimitInputFrameRate's kdoc for why this
            // is needed at all (KEY_FRAME_RATE alone is a bitrate-calculation
            // hint, not an enforced cap, on surface input).
            try {
                setFloat(KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            } catch (e: Exception) {
                // Some codecs reject unknown keys at configure time rather
                // than ignoring them -- setParameters() after start() below
                // is the reliable fallback.
            }

            // Tells the encoder the throughput it actually needs to sustain,
            // so it can pick a clock/power operating point sized for *that*
            // instead of defaulting to a max-throughput point "just in case."
            // Documented for high-speed capture (record fast, encode slow),
            // but the mechanism is symmetric: any operating-rate hint lets
            // the driver's power management plan around a known target
            // instead of the worst case -- directly in service of "ısınmayı
            // ve güç tüketimini azalt." Best-effort: some drivers reject
            // unknown keys at configure() time rather than ignoring them.
            try {
                setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
            } catch (e: Exception) {
                // Not fatal -- purely a power-planning hint.
            }
        }

        Log.i(TAG, "configure(): codec=${choice.codecName} mime=${choice.mimeType} " +
            "size=${choice.width}x${choice.height} fps=$fps bitrate=$bitrate mode=$bitrateMode " +
            "hw=${choice.isHardware} profile=${choice.profile} level=${choice.level}")

        codec = MediaCodec.createByCodecName(choice.codecName)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        tryLimitInputFrameRate(fps)
        return inputSurface
    }

    fun startDraining() {
        val t = Thread({ drainLoop() }, "RecorderX-VideoEncoder")
        drainThread = t
        t.start()
    }

    /** Call once, right before recreating the VirtualDisplay on resume (see
     * RecordingService#resumeInternal). Arms the rebase so the next real
     * frame's raw timestamp becomes the new reference point, continuing the
     * output timeline from [lastEmittedPtsUs] instead of jumping forward by
     * however long the pause actually lasted in wall-clock time. */
    fun requestPauseRebase() {
        awaitingResumeRebase = true
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
                            if (sessionBaseUs < 0) {
                                sessionBaseUs = bufferInfo.presentationTimeUs
                                Log.i(TAG, "First video frame PTS=$sessionBaseUs us (boot-relative) -- rebasing track to start at 0")
                            }
                            val rawRelativeUs = bufferInfo.presentationTimeUs - sessionBaseUs

                            if (awaitingResumeRebase) {
                                // This is the first frame after a resume: make its
                                // rebased PTS pick up right after the last one we
                                // emitted (plus a tiny nudge to stay strictly
                                // increasing), absorbing the pause's real-world
                                // duration into pauseOffsetUs from here on.
                                pauseOffsetUs = rawRelativeUs - lastEmittedPtsUs - RESUME_FRAME_NUDGE_US
                                awaitingResumeRebase = false
                            }

                            var rebasedUs = rawRelativeUs - pauseOffsetUs
                            if (rebasedUs <= lastEmittedPtsUs) {
                                rebasedUs = lastEmittedPtsUs + RESUME_FRAME_NUDGE_US
                            }
                            bufferInfo.presentationTimeUs = rebasedUs
                            lastEmittedPtsUs = rebasedUs

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

    /**
     * Asks the encoder to internally drop input frames above [maxFps]. This is
     * the actual enforcement mechanism for the user's FPS choice on a
     * Surface-input encoder -- [MediaFormat.KEY_FRAME_RATE] alone is only a
     * bitrate-calculation hint; SurfaceFlinger keeps delivering frames at the
     * display's native refresh rate regardless of it, which is why FPS
     * selection previously had no visible effect on the output.
     *
     * The float type here matters: [MediaFormat.KEY_MAX_FPS_TO_ENCODER]'s
     * documented value type is float, not int -- passing an int silently
     * does nothing (Bundle lookups are type-specific), which is exactly the
     * bug this replaces. Called once at configure() time and again,
     * defensively, by ThermalBitrateGovernor under thermal stress.
     */
    fun tryLimitInputFrameRate(maxFps: Int) {
        try {
            val params = Bundle()
            params.putFloat(KEY_MAX_FPS_TO_ENCODER, maxFps.toFloat())
            codec.setParameters(params)
            Log.i(TAG, "tryLimitInputFrameRate($maxFps) applied")
        } catch (e: Throwable) {
            Log.w(TAG, "tryLimitInputFrameRate($maxFps) not honored by this encoder", e)
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
        private const val TAG = "VideoEncoderPipeline"
        private const val TIMEOUT_US = 10_000L

        // Tiny (1ms, inaudible/invisible) forced gap used to guarantee
        // strictly-increasing PTS values across a pause/resume boundary --
        // MediaMuxer requires non-decreasing timestamps per track.
        private const val RESUME_FRAME_NUDGE_US = 1_000L

        // MediaFormat.KEY_MAX_FPS_TO_ENCODER as a raw string: using the typed
        // SDK constant would require gating this whole class behind an API
        // check, but the *key* is just a string the platform either
        // recognizes or safely ignores, so it's used directly and tried on
        // every API level (26+) via try/catch rather than an SDK_INT gate.
        private const val KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder"
    }
}
