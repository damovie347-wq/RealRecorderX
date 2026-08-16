package com.recorderx.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.recorderx.app.MainActivity
import com.recorderx.app.R
import com.recorderx.app.adaptive.ThermalBitrateGovernor
import com.recorderx.app.audio.AudioMixEngine
import com.recorderx.app.bitrate.BitrateAdvisor
import com.recorderx.app.capture.FramePacer
import com.recorderx.app.capture.ScreenCaptureController
import com.recorderx.app.codec.CodecSelector
import com.recorderx.app.encoder.AudioEncoderPipeline
import com.recorderx.app.encoder.MuxerController
import com.recorderx.app.encoder.VideoEncoderPipeline
import com.recorderx.app.overlay.RecordingOverlayController
import com.recorderx.app.settings.Av1SoftwareFallback
import com.recorderx.app.settings.AudioSourceOption
import com.recorderx.app.settings.BitrateOption
import com.recorderx.app.settings.ColorDepthOption
import com.recorderx.app.settings.OverlayVisibilityMode
import com.recorderx.app.settings.RecordingSettings
import com.recorderx.app.settings.SettingsRepository
import com.recorderx.app.storage.RecordingOutputResolver
import java.util.Locale

class RecordingService : Service() {

    private lateinit var settingsRepository: SettingsRepository
    private val overlayController by lazy { RecordingOverlayController(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null

    private var muxerController: MuxerController? = null
    private var videoEncoder: VideoEncoderPipeline? = null
    private var audioEncoder: AudioEncoderPipeline? = null
    private var audioMixEngine: AudioMixEngine? = null
    private var captureController: ScreenCaptureController? = null
    private var framePacer: FramePacer? = null
    private var thermalGovernor: ThermalBitrateGovernor? = null
    private var outputTarget: RecordingOutputResolver.Output? = null

    private var currentSettings: RecordingSettings = RecordingSettings()
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0
    private var resolvedSampleRate = 48_000

    // The encoder's actual (alignment-adjusted) input size, as returned by
    // CodecSelector -- may differ slightly from the raw requested captureWidth/
    // captureHeight above. resumeInternal() must reuse this exact size when
    // recreating the VirtualDisplay, since the encoder's input Surface was
    // configured against it and can't be resized without reconfiguring the codec.
    private var resolvedCaptureWidth = 0
    private var resolvedCaptureHeight = 0

    private var isPaused = false
    private var recordingStartElapsedRealtime = 0L
    private var pausedAccumulatedMs = 0L
    private var audioFrameOffset = 0L

    // Tracks whether the user explicitly hid the floating control bubble
    // (RecordingOverlayController#hide via the overlay's own "eye" button) as
    // opposed to it simply never having been enabled in settings -- drives
    // whether buildNotification() offers a "Show controls" action, since the
    // bubble obviously can't offer its own way back once it's detached from
    // WindowManager. Reset whenever a fresh recording starts.
    private var overlayHiddenByUser = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isPaused) {
                val elapsed = SystemClock.elapsedRealtime() - recordingStartElapsedRealtime + pausedAccumulatedMs
                val text = formatElapsed(elapsed)
                RecordingSessionState.update(RecordingSessionState.Phase.RECORDING, elapsed)
                updateNotification(text, paused = false)
                overlayController.setElapsedText(text)
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    /** Only ever scheduled when OverlayVisibilityMode.AUTO_HIDE is selected
     * -- see showOverlayIfEnabled. Goes through the exact same path a manual
     * eye-icon tap would (overlayHiddenByUser = true, "Show controls" surfaced
     * on the notification), so there is only ever one "the bubble is gone"
     * code path to reason about, not two. */
    private val autoHideOverlayRunnable = Runnable { handleHideOverlay() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_TOGGLE_PAUSE -> handleTogglePause()
            ACTION_SHOW_OVERLAY -> handleShowOverlay()
        }
        return START_NOT_STICKY
    }

    // ---- Start -------------------------------------------------------

    private fun handleStart(intent: Intent) {
        if (mediaProjection != null) return // a session is already running

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        captureWidth = intent.getIntExtra(EXTRA_WIDTH, 1080)
        captureHeight = intent.getIntExtra(EXTRA_HEIGHT, 1920)
        captureDensity = intent.getIntExtra(EXTRA_DENSITY, 420)

        if (resultData == null) {
            stopSelf()
            return
        }

        // Must start the foreground state *before* touching MediaProjection APIs
        // (Android 14+ requires the mediaProjection foreground service type to
        // already be active at the moment the projection is created).
        startForegroundNotification()

        currentSettings = settingsRepository.load()
        resolvedSampleRate = currentSettings.audioQuality.sampleRate

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            null
        }
        if (projection == null) {
            stopSelf()
            return
        }
        mediaProjection = projection

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // Fires if the user stops sharing from the system's own UI
                // (quick-settings tile, system "stop recording" affordance) --
                // treat exactly like our own Stop button.
                mainHandler.post { handleStop() }
            }
        }
        mediaProjectionCallback = callback
        projection.registerCallback(callback, mainHandler)

        if (!beginPipelines()) {
            Toast.makeText(this, R.string.error_could_not_start, Toast.LENGTH_LONG).show()
            teardownAfterFailedStart()
            return
        }

        recordingStartElapsedRealtime = SystemClock.elapsedRealtime()
        pausedAccumulatedMs = 0
        isPaused = false
        RecordingSessionState.update(RecordingSessionState.Phase.RECORDING, 0)

        showOverlayIfEnabled()

        mainHandler.post(tickRunnable)
    }

    private fun beginPipelines(): Boolean {
        val output = RecordingOutputResolver.createOutputTarget(this, currentSettings.outputTemplate) ?: return false
        outputTarget = output

        val wantsAudio = currentSettings.audioSource != AudioSourceOption.OFF
        val muxer = MuxerController(output.fileDescriptor, expectedTrackCount = if (wantsAudio) 2 else 1)
        muxerController = muxer

        val codecChoice = CodecSelector.findBestEncoder(
            currentSettings.videoCodec,
            captureWidth,
            captureHeight,
            currentSettings.frameRate.fps,
            currentSettings.colorDepth,
            currentSettings.av1SoftwareFallback == Av1SoftwareFallback.ON
        ) ?: return false

        announceCodecResult(currentSettings.videoCodec, codecChoice)

        // From here down, every fps reference uses codecChoice.achievedFps --
        // the fps this exact device/codec/resolution combo can actually
        // sustain (see CodecSelector.findEncoderFor) -- not the raw slider
        // value, since that's the number FramePacer will really deliver.
        val effectiveFps = codecChoice.achievedFps

        val bitrateBps = if (currentSettings.bitrateOption == BitrateOption.AUTO) {
            BitrateAdvisor.suggestBitrateBps(codecChoice.width, codecChoice.height, effectiveFps, codecChoice.mimeType)
        } else {
            currentSettings.bitrateOption.bps
        }

        val video = VideoEncoderPipeline(muxer) { t -> handleFatalError(t) }
        val surface = video.configure(codecChoice, bitrateBps, effectiveFps, currentSettings.bitrateMode)
        resolvedCaptureWidth = codecChoice.width
        resolvedCaptureHeight = codecChoice.height
        video.startDraining()
        videoEncoder = video

        // FramePacer sits between the mirrored screen and the encoder's real
        // input surface, and is the actual enforcement mechanism for the
        // user's fps choice -- see its kdoc. MediaProjection now mirrors into
        // pacer.virtualDisplaySurface; `surface` (the encoder's own input
        // surface) is only ever touched by the pacer's GL thread from here on.
        val pacer = FramePacer(
            surface, effectiveFps, codecChoice.width, codecChoice.height
        ) { t -> handleFatalError(t) }
        pacer.start()
        framePacer = pacer

        val capture = ScreenCaptureController(mediaProjection ?: return false)
        capture.start(pacer.virtualDisplaySurface, codecChoice.width, codecChoice.height, captureDensity) {
            mainHandler.post { handleStop() }
        }
        captureController = capture

        val governor = ThermalBitrateGovernor(
            context = this,
            videoEncoder = video,
            framePacer = pacer,
            baseBitrateBps = bitrateBps,
            configuredFps = effectiveFps,
            onThrottleChanged = { fraction -> mainHandler.post { announceThermalThrottle(fraction) } },
            onEmergencyStop = { mainHandler.post { handleStop() } }
        )
        governor.start()
        thermalGovernor = governor

        if (wantsAudio) startAudioPipeline(muxer)

        return true
    }

    /** Compares what the user picked against what CodecSelector actually
     * resolved (post hardware-availability cascade) and says so plainly when
     * a fallback happened, instead of silently substituting a different
     * codec with no indication anything changed. Also flags it when the fps
     * itself had to be clipped (see CodecChoice.achievedFps) -- resolution is
     * never sacrificed for fps, so on a real hardware ceiling fps is what
     * gives, and the user should know that happened rather than just getting
     * a quietly slower recording than requested. Same treatment for
     * resolution (when the *chosen* codec's own capability, not a codec
     * fallback, is what forced a smaller size) and for color depth (when
     * 10-bit was requested but nothing in the cascade could actually do it). */
    private fun announceCodecResult(preference: com.recorderx.app.settings.VideoCodecOption, choice: com.recorderx.app.codec.CodecChoice) {
        val resolvedLabel = mimeToLabel(choice.mimeType)
        val fellBack = mimeToPreference(choice.mimeType) != preference
        val fpsClipped = choice.achievedFps < currentSettings.frameRate.fps
        val isSoftwareAv1 = !choice.isHardware && choice.mimeType == android.media.MediaFormat.MIMETYPE_VIDEO_AV1
        val resolutionCapped = !fellBack && (choice.width.toLong() * choice.height.toLong()) <
            (captureWidth.toLong() * captureHeight.toLong()) * RESOLUTION_CAP_REPORT_THRESHOLD_NUM / RESOLUTION_CAP_REPORT_THRESHOLD_DEN
        val colorDepthFellBack = currentSettings.colorDepth == ColorDepthOption.TEN_BIT &&
            choice.colorDepth == ColorDepthOption.EIGHT_BIT

        Log.i(TAG, "announceCodecResult(): preference=$preference resolved=$resolvedLabel " +
            "size=${choice.width}x${choice.height} requestedSize=${captureWidth}x${captureHeight} " +
            "requestedFps=${currentSettings.frameRate.fps} achievedFps=${choice.achievedFps} " +
            "fellBack=$fellBack isSoftwareAv1=$isSoftwareAv1 resolutionCapped=$resolutionCapped " +
            "colorDepthFellBack=$colorDepthFellBack isHardware=${choice.isHardware}")

        mainHandler.post {
            when {
                // A user-requested, opted-in software AV1 encode isn't a
                // fallback to apologize for -- it's exactly what
                // Av1SoftwareFallback.ON was turned on to get -- so it gets
                // its own, differently-worded toast instead of the generic
                // "no hardware encoder matched" one.
                isSoftwareAv1 -> Toast.makeText(this, R.string.toast_software_av1, Toast.LENGTH_LONG).show()
                !choice.isHardware -> Toast.makeText(this, R.string.toast_no_hardware_encoder, Toast.LENGTH_LONG).show()
            }
            if (colorDepthFellBack) {
                Toast.makeText(this, R.string.toast_color_depth_fallback, Toast.LENGTH_LONG).show()
            }
            if (fellBack) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_codec_fallback, preference.label, resolvedLabel),
                    Toast.LENGTH_LONG
                ).show()
            }
            if (fpsClipped) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_fps_capped, currentSettings.frameRate.fps, choice.achievedFps),
                    Toast.LENGTH_LONG
                ).show()
            }
            if (resolutionCapped) {
                Toast.makeText(
                    this,
                    getString(
                        R.string.toast_resolution_capped,
                        "${captureWidth}\u00D7${captureHeight}",
                        "${choice.width}\u00D7${choice.height}"
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
            val depthSuffix = if (choice.colorDepth == ColorDepthOption.TEN_BIT) " · 10-bit" else ""
            val summary = "$resolvedLabel$depthSuffix · ${choice.width}x${choice.height} · ${choice.achievedFps}fps"
            Toast.makeText(this, getString(R.string.toast_recording_summary, summary), Toast.LENGTH_SHORT).show()
        }
    }

    /** Wired to ThermalBitrateGovernor.onThrottleChanged -- see that class's
     * kdoc for why this exists at all. `fraction >= 1.0` is specifically the
     * "back to normal" case, so a person who noticed the earlier toast also
     * gets told when it's over instead of just guessing from the picture
     * quality recovering on its own. */
    private fun announceThermalThrottle(fraction: Double) {
        if (fraction >= 1.0) {
            Toast.makeText(this, R.string.toast_thermal_recovered, Toast.LENGTH_SHORT).show()
        } else {
            val percent = (fraction * 100).toInt()
            Toast.makeText(this, getString(R.string.toast_thermal_throttled, percent), Toast.LENGTH_LONG).show()
        }
    }

    private fun mimeToLabel(mime: String): String = when (mime) {
        android.media.MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
        android.media.MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265"
        else -> "H.264"
    }

    private fun mimeToPreference(mime: String): com.recorderx.app.settings.VideoCodecOption = when (mime) {
        android.media.MediaFormat.MIMETYPE_VIDEO_AV1 -> com.recorderx.app.settings.VideoCodecOption.AV1
        android.media.MediaFormat.MIMETYPE_VIDEO_HEVC -> com.recorderx.app.settings.VideoCodecOption.H265
        else -> com.recorderx.app.settings.VideoCodecOption.H264
    }

    /** Checks what actually got captured against what the user asked for and
     * says so -- silence with no explanation is exactly the "granted every
     * permission but got no audio, with no idea why" problem this replaces. */
    private fun announceAudioResult(mixer: AudioMixEngine) {
        val wantedSystem = currentSettings.audioSource.wantsSystem
        val wantedMic = currentSettings.audioSource.wantsMic
        val gotSystem = mixer.hasSystemAudio
        val gotMic = mixer.hasMicAudio
        Log.i(TAG, "announceAudioResult(): wantedSystem=$wantedSystem gotSystem=$gotSystem wantedMic=$wantedMic gotMic=$gotMic")

        val message = when {
            !wantedSystem && !wantedMic -> null
            !gotSystem && !gotMic -> getString(R.string.toast_no_audio_captured)
            wantedSystem && !gotSystem -> getString(R.string.toast_system_audio_unavailable)
            wantedMic && !gotMic -> getString(R.string.toast_mic_unavailable)
            else -> null
        }
        if (message != null) {
            mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun startAudioPipeline(muxer: MuxerController) {
        val wantsSystemAudio = currentSettings.audioSource.wantsSystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val channelCount = AudioMixEngine.resolveChannelCount(currentSettings.audioChannel, wantsSystemAudio)

        val encoder = AudioEncoderPipeline(muxer) { t -> handleFatalError(t) }
        encoder.configure(resolvedSampleRate, channelCount, currentSettings.audioQuality.aacBitrate)
        encoder.startDraining()
        audioEncoder = encoder

        val mixer = AudioMixEngine(
            context = this,
            mediaProjection = mediaProjection,
            settings = currentSettings,
            audioEncoder = encoder,
            sampleRate = resolvedSampleRate,
            onError = { t -> handleFatalError(t) }
        )
        if (mixer.start()) {
            audioMixEngine = mixer
        } else {
            // See MuxerController kdoc: this still lets the encoder reach a
            // valid EOS (format info is emitted before real data is required),
            // so the muxer's expected track count is satisfied either way.
            encoder.requestStop()
        }
        announceAudioResult(mixer)
        announceHeadphoneTipIfNeeded()
    }

    /** Complementary, zero-cost mitigation to ResidualBleedSuppressor (see its
     * kdoc): software suppression cleans up bleed after the fact, but a
     * headset removes it at the acoustic source entirely, so telling the
     * person *before* they record -- once, not on every session -- is worth
     * doing even though it can't be the only fix. Only relevant when both
     * system and mic audio are actually being captured together (mic-only or
     * system-only has no bleed to speak of) and there's currently no external
     * output route connected. */
    private fun announceHeadphoneTipIfNeeded() {
        val bothSources = currentSettings.audioSource.wantsSystem && currentSettings.audioSource.wantsMic
        if (!bothSources) return
        if (!AudioMixEngine.isLikelyUsingBuiltInSpeaker(this)) return
        if (settingsRepository.hasShownHeadphoneTip()) return
        settingsRepository.setHeadphoneTipShown()
        mainHandler.post { Toast.makeText(this, R.string.toast_headphones_recommended, Toast.LENGTH_LONG).show() }
    }

    // ---- Pause / resume ------------------------------------------------

    private fun handleTogglePause() {
        if (mediaProjection == null) return
        if (isPaused) resumeInternal() else pauseInternal()
    }

    /**
     * There's no "pause" primitive on MediaCodec/MediaProjection, so this
     * releases just the VirtualDisplay (SurfaceFlinger stops delivering new
     * frames into the encoder's input Surface) and the audio sources, while
     * the encoders, the muxer, and the encoder's input Surface itself all
     * stay alive untouched. Resuming recreates the VirtualDisplay against the
     * *same* input Surface, so frames simply resume flowing -- no new track,
     * no file-segment stitching, no PTS discontinuity to reason about.
     */
    private fun pauseInternal() {
        isPaused = true
        pausedAccumulatedMs = SystemClock.elapsedRealtime() - recordingStartElapsedRealtime + pausedAccumulatedMs
        captureController?.stop()
        captureController = null
        framePacer?.pause()
        audioFrameOffset = audioMixEngine?.totalFramesWritten ?: audioFrameOffset
        audioMixEngine?.stop()
        audioMixEngine = null

        val text = formatElapsed(pausedAccumulatedMs)
        RecordingSessionState.update(RecordingSessionState.Phase.PAUSED, pausedAccumulatedMs)
        overlayController.setPaused(true)
        updateNotification(text, paused = true)
    }

    private fun resumeInternal() {
        isPaused = false
        recordingStartElapsedRealtime = SystemClock.elapsedRealtime()

        videoEncoder?.requestPauseRebase()
        val pacer = framePacer
        val projection = mediaProjection
        if (pacer != null && projection != null) {
            pacer.resume()
            val capture = ScreenCaptureController(projection)
            capture.start(pacer.virtualDisplaySurface, videoEncoderCaptureWidth(), videoEncoderCaptureHeight(), captureDensity) {
                mainHandler.post { handleStop() }
            }
            captureController = capture
        }

        val encoder = audioEncoder
        if (currentSettings.audioSource != AudioSourceOption.OFF && encoder != null) {
            val mixer = AudioMixEngine(
                context = this,
                mediaProjection = mediaProjection,
                settings = currentSettings,
                audioEncoder = encoder,
                sampleRate = resolvedSampleRate,
                startFrameOffset = audioFrameOffset,
                onError = { t -> handleFatalError(t) }
            )
            if (mixer.start()) audioMixEngine = mixer
        }

        overlayController.setPaused(false)
        RecordingSessionState.update(RecordingSessionState.Phase.RECORDING, pausedAccumulatedMs)
    }

    private fun videoEncoderCaptureWidth(): Int = if (resolvedCaptureWidth != 0) resolvedCaptureWidth else captureWidth
    private fun videoEncoderCaptureHeight(): Int = if (resolvedCaptureHeight != 0) resolvedCaptureHeight else captureHeight

    // ---- Stop ----------------------------------------------------------

    private fun handleStop() {
        if (mediaProjection == null) return
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(autoHideOverlayRunnable)

        captureController?.stop()
        framePacer?.release()
        framePacer = null
        thermalGovernor?.stop()
        audioMixEngine?.stop()
        videoEncoder?.requestStop()
        audioEncoder?.requestStop()

        videoEncoder?.awaitFinished(2000)
        audioEncoder?.awaitFinished(2000)
        muxerController?.release()

        outputTarget?.finalizeAndGetUri(this) { uri ->
            mainHandler.post {
                if (uri != null) {
                    settingsRepository.setLastRecordingUri(uri.toString())
                    Toast.makeText(this, R.string.toast_recording_saved, Toast.LENGTH_SHORT).show()
                }
            }
        }

        overlayController.hide()
        overlayHiddenByUser = false

        try {
            mediaProjectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (e: Exception) {
            // Already unregistered / projection already gone -- nothing to do.
        }
        mediaProjection?.stop()
        mediaProjection = null

        RecordingSessionState.update(RecordingSessionState.Phase.IDLE, 0)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardownAfterFailedStart() {
        try {
            mediaProjectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (e: Exception) { /* nothing registered yet */ }
        captureController?.stop()
        framePacer?.release()
        framePacer = null
        mediaProjection?.stop()
        mediaProjection = null
        muxerController?.release()
        RecordingSessionState.update(RecordingSessionState.Phase.IDLE, 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleFatalError(t: Throwable) {
        Log.e(TAG, "Recording pipeline error", t)
        mainHandler.post { handleStop() }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (mediaProjection != null) {
            // Process death / task removal without a clean Stop tap -- best-effort teardown.
            captureController?.stop()
            framePacer?.release()
            framePacer = null
            thermalGovernor?.stop()
            audioMixEngine?.stop()
            try { videoEncoder?.requestStop() } catch (e: Exception) { }
            try { audioEncoder?.requestStop() } catch (e: Exception) { }
            muxerController?.release()
            overlayController.hide()
            mediaProjection?.stop()
        }
        RecordingSessionState.update(RecordingSessionState.Phase.IDLE, 0)
        super.onDestroy()
    }

    // ---- Overlay bubble --------------------------------------------------

    /** Used both by handleStart() (first show) and handleShowOverlay() (user
     * tapped "Show controls" in the notification after hiding it) -- the
     * three callbacks are identical either way. No-ops if the setting is
     * off, same as the original inline check in handleStart() did. */
    private fun showOverlayIfEnabled() {
        if (!currentSettings.floatingBubbleEnabled) return
        overlayHiddenByUser = false
        mainHandler.removeCallbacks(autoHideOverlayRunnable)
        overlayController.show(
            blackout = currentSettings.overlayVisibility == OverlayVisibilityMode.BLACKOUT,
            onTogglePauseResume = { handleTogglePause() },
            onStop = { handleStop() },
            onHide = { handleHideOverlay() }
        )
        // AUTO_HIDE: reachable for a few seconds (enough to actually see and
        // confirm it landed where expected), then detaches itself exactly
        // like a manual eye-icon tap would -- see OverlayVisibilityMode's
        // kdoc. Re-armed on every show(), including the notification's
        // "Show controls" action, so a person who explicitly asked to see it
        // again still gets the same few-second window before it's gone again.
        if (currentSettings.overlayVisibility == OverlayVisibilityMode.AUTO_HIDE) {
            mainHandler.postDelayed(autoHideOverlayRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    /** Wired to the overlay's own "eye" button (see RecordingOverlayController).
     * Fully detaches the window -- see that class's kdoc for why that's the
     * only way to actually guarantee it's out of the next captured frame,
     * unlike the old FLAG_SECURE approach. Surfaces a "Show controls" action
     * on the persistent notification since the bubble can't offer its own
     * way back once it's gone. */
    private fun handleHideOverlay() {
        mainHandler.removeCallbacks(autoHideOverlayRunnable)
        overlayController.hide()
        overlayHiddenByUser = true
        refreshNotification()
    }

    /** Wired to the notification's "Show controls" action (see buildNotification). */
    private fun handleShowOverlay() {
        if (mediaProjection == null) return // stale tap after recording already ended
        showOverlayIfEnabled()
        refreshNotification()
    }

    private fun currentElapsedMs(): Long =
        if (isPaused) pausedAccumulatedMs else SystemClock.elapsedRealtime() - recordingStartElapsedRealtime + pausedAccumulatedMs

    private fun refreshNotification() {
        updateNotification(formatElapsed(currentElapsedMs()), paused = isPaused)
    }

    // ---- Notification ----------------------------------------------------

    private fun startForegroundNotification() {
        val notification = buildNotification(formatElapsed(0), paused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(elapsedText: String, paused: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(elapsedText, paused))
    }

    private fun buildNotification(elapsedText: String, paused: Boolean): Notification {
        val contentText = if (paused) {
            getString(R.string.notification_paused_text, elapsedText)
        } else {
            getString(R.string.notification_recording_text, elapsedText)
        }
        val stopIntent = PendingIntent.getService(
            this, 0, buildStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_recording), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Only relevant if the bubble is actually enabled *and* the user hid
        // it themselves (see handleHideOverlay) -- otherwise there's nothing
        // to bring back, and this action would just be confusing clutter.
        if (overlayHiddenByUser && currentSettings.floatingBubbleEnabled) {
            val showOverlayIntent = PendingIntent.getService(
                this, 1, buildShowOverlayIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.show_controls_action), showOverlayIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun formatElapsed(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recorderx_recording"
        private const val NOTIFICATION_ID = 42

        // Below this fraction of the requested capture area, RecordingService
        // tells the user their resolution pick was capped by the codec's own
        // capability (not by a codec-mime fallback, which already gets its
        // own toast) -- see announceCodecResult. 9/10 rather than a float so
        // the comparison in announceCodecResult stays exact integer math.
        private const val RESOLUTION_CAP_REPORT_THRESHOLD_NUM = 9L
        private const val RESOLUTION_CAP_REPORT_THRESHOLD_DEN = 10L

        // How long the floating bubble stays reachable after a recording
        // starts when OverlayVisibilityMode.AUTO_HIDE is selected, before it
        // detaches itself exactly like a manual eye-icon tap would -- see
        // showOverlayIfEnabled / scheduleAutoHideIfNeeded.
        private const val AUTO_HIDE_DELAY_MS = 3_000L

        const val ACTION_START = "com.recorderx.app.action.START"
        const val ACTION_STOP = "com.recorderx.app.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.recorderx.app.action.TOGGLE_PAUSE"
        const val ACTION_SHOW_OVERLAY = "com.recorderx.app.action.SHOW_OVERLAY"

        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_WIDTH = "extra_width"
        private const val EXTRA_HEIGHT = "extra_height"
        private const val EXTRA_DENSITY = "extra_density"

        fun buildStartIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            width: Int,
            height: Int,
            densityDpi: Int
        ): Intent = Intent(context, RecordingService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_WIDTH, width)
            putExtra(EXTRA_HEIGHT, height)
            putExtra(EXTRA_DENSITY, densityDpi)
        }

        fun buildStopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP)

        fun buildTogglePauseIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_TOGGLE_PAUSE)

        fun buildShowOverlayIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_SHOW_OVERLAY)
    }
}
