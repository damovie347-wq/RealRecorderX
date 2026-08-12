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
import android.view.Surface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.recorderx.app.MainActivity
import com.recorderx.app.R
import com.recorderx.app.adaptive.ThermalBitrateGovernor
import com.recorderx.app.audio.AudioMixEngine
import com.recorderx.app.bitrate.BitrateAdvisor
import com.recorderx.app.capture.ScreenCaptureController
import com.recorderx.app.codec.CodecSelector
import com.recorderx.app.encoder.AudioEncoderPipeline
import com.recorderx.app.encoder.MuxerController
import com.recorderx.app.encoder.VideoEncoderPipeline
import com.recorderx.app.overlay.RecordingOverlayController
import com.recorderx.app.settings.AudioSourceOption
import com.recorderx.app.settings.BitrateOption
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
    private var thermalGovernor: ThermalBitrateGovernor? = null
    private var outputTarget: RecordingOutputResolver.Output? = null
    private var encoderInputSurface: Surface? = null

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
            currentSettings.frameRate.fps
        ) ?: return false

        if (!codecChoice.isHardware) {
            mainHandler.post {
                Toast.makeText(this, R.string.toast_no_hardware_encoder, Toast.LENGTH_LONG).show()
            }
        }
        announceCodecResult(currentSettings.videoCodec, codecChoice)

        val bitrateBps = if (currentSettings.bitrateOption == BitrateOption.AUTO) {
            BitrateAdvisor.suggestBitrateBps(codecChoice.width, codecChoice.height, currentSettings.frameRate.fps, codecChoice.mimeType)
        } else {
            currentSettings.bitrateOption.bps
        }

        val video = VideoEncoderPipeline(muxer) { t -> handleFatalError(t) }
        val surface = video.configure(codecChoice, bitrateBps, currentSettings.frameRate.fps, currentSettings.bitrateMode)
        encoderInputSurface = surface
        resolvedCaptureWidth = codecChoice.width
        resolvedCaptureHeight = codecChoice.height
        video.startDraining()
        videoEncoder = video

        val capture = ScreenCaptureController(mediaProjection ?: return false)
        capture.start(surface, codecChoice.width, codecChoice.height, captureDensity) {
            mainHandler.post { handleStop() }
        }
        captureController = capture

        val governor = ThermalBitrateGovernor(this, video, bitrateBps, currentSettings.frameRate.fps) {
            mainHandler.post { handleStop() }
        }
        governor.start()
        thermalGovernor = governor

        if (wantsAudio) startAudioPipeline(muxer)

        return true
    }

    /** Compares what the user picked against what CodecSelector actually
     * resolved (post hardware-availability cascade) and says so plainly when
     * a fallback happened, instead of silently substituting a different
     * codec with no indication anything changed. */
    private fun announceCodecResult(preference: com.recorderx.app.settings.VideoCodecOption, choice: com.recorderx.app.codec.CodecChoice) {
        val resolvedLabel = mimeToLabel(choice.mimeType)
        val fellBack = mimeToPreference(choice.mimeType) != preference
        Log.i(TAG, "announceCodecResult(): preference=$preference resolved=$resolvedLabel " +
            "size=${choice.width}x${choice.height} fps=${currentSettings.frameRate.fps} fellBack=$fellBack")
        mainHandler.post {
            if (fellBack) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_codec_fallback, preference.label, resolvedLabel),
                    Toast.LENGTH_LONG
                ).show()
            }
            val summary = "$resolvedLabel · ${choice.width}x${choice.height} · ${currentSettings.frameRate.fps}fps"
            Toast.makeText(this, getString(R.string.toast_recording_summary, summary), Toast.LENGTH_SHORT).show()
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
        val surface = encoderInputSurface
        val projection = mediaProjection
        if (surface != null && projection != null) {
            val capture = ScreenCaptureController(projection)
            capture.start(surface, videoEncoderCaptureWidth(), videoEncoderCaptureHeight(), captureDensity) {
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

        captureController?.stop()
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
        overlayController.show(
            onTogglePauseResume = { handleTogglePause() },
            onStop = { handleStop() },
            onHide = { handleHideOverlay() }
        )
    }

    /** Wired to the overlay's own "eye" button (see RecordingOverlayController).
     * Fully detaches the window -- see that class's kdoc for why that's the
     * only way to actually guarantee it's out of the next captured frame,
     * unlike the old FLAG_SECURE approach. Surfaces a "Show controls" action
     * on the persistent notification since the bubble can't offer its own
     * way back once it's gone. */
    private fun handleHideOverlay() {
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
