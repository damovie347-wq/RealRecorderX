package com.recorderx.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.recorderx.app.codec.CodecSelector

/**
 * Plain SharedPreferences, not androidx.datastore: DataStore pulls in protobuf
 * or an extra Preferences artifact for a job that ~20 primitive key/value pairs
 * handle just fine. One more example of "don't add a dependency you don't need."
 *
 * Deliberately NOT using `inline fun <reified T : Enum<T>>` helpers here, even
 * though that's the more compact way to write this. AGP 9.3's built-in Kotlin
 * compiler has a real, confirmed inference bug (KT-86728) with exactly that
 * pattern in this exact spot, and after tracking down a settings-not-honored
 * bug that traced back to this file, every field below uses a plain,
 * boring, per-type `valueOf()` call instead. No generics, no inference, no
 * ambiguity -- see README.md's troubleshooting log for the full story.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): RecordingSettings {
        val defaults = RecordingSettings()

        // No saved codec yet (first launch) -- resolve the device-aware smart
        // default (H.264 on Android 8/9 or a low-tier device, AV1's full
        // fallback cascade elsewhere) instead of a single hardcoded constant.
        val savedCodecRaw = prefs.getString(KEY_CODEC, null)
        val videoCodec = if (savedCodecRaw == null) {
            CodecSelector.resolveDefaultPreference(appContext)
        } else {
            parseVideoCodec(savedCodecRaw) ?: CodecSelector.resolveDefaultPreference(appContext)
        }

        val settings = RecordingSettings(
            videoCodec = videoCodec,
            orientation = parseOrientation(prefs.getString(KEY_ORIENTATION, null)) ?: defaults.orientation,
            resolution = parseResolution(prefs.getString(KEY_RESOLUTION, null)) ?: defaults.resolution,
            frameRate = parseFrameRate(prefs.getString(KEY_FPS, null)) ?: defaults.frameRate,
            bitrateOption = parseBitrateOption(prefs.getString(KEY_BITRATE, null)) ?: defaults.bitrateOption,
            bitrateMode = parseBitrateMode(prefs.getString(KEY_BITRATE_MODE, null)) ?: defaults.bitrateMode,
            advancedBitrateUnlocked = prefs.getBoolean(KEY_ADV_BITRATE, defaults.advancedBitrateUnlocked),
            colorDepth = parseColorDepth(prefs.getString(KEY_COLOR_DEPTH, null)) ?: defaults.colorDepth,
            av1SoftwareFallback = parseAv1SoftwareFallback(prefs.getString(KEY_AV1_SW_FALLBACK, null)) ?: defaults.av1SoftwareFallback,
            audioSource = parseAudioSource(prefs.getString(KEY_AUDIO_SOURCE, null)) ?: defaults.audioSource,
            audioQuality = parseAudioQuality(prefs.getString(KEY_AUDIO_QUALITY, null)) ?: defaults.audioQuality,
            audioChannel = parseAudioChannel(prefs.getString(KEY_AUDIO_CHANNEL, null)) ?: defaults.audioChannel,
            micGain = parseMicGain(prefs.getString(KEY_MIC_GAIN, null)) ?: defaults.micGain,
            voicePriority = parseVoicePriority(prefs.getString(KEY_VOICE_PRIORITY, null)) ?: defaults.voicePriority,
            systemLevelPercent = prefs.getInt(KEY_SYSTEM_LEVEL, defaults.systemLevelPercent),
            micLevelPercent = prefs.getInt(KEY_MIC_LEVEL, defaults.micLevelPercent),
            audioMix = parseAudioMix(prefs.getString(KEY_AUDIO_MIX, null)) ?: defaults.audioMix,
            audioMonitoring = parseAudioMonitoring(prefs.getString(KEY_AUDIO_MONITORING, null)) ?: defaults.audioMonitoring,
            bleedSuppression = parseBleedSuppression(prefs.getString(KEY_BLEED_SUPPRESSION, null)) ?: defaults.bleedSuppression,
            floatingBubbleEnabled = prefs.getBoolean(KEY_BUBBLE, defaults.floatingBubbleEnabled),
            overlayVisibility = parseOverlayVisibility(prefs.getString(KEY_OVERLAY_VISIBILITY, null)) ?: defaults.overlayVisibility,
            outputTemplate = prefs.getString(KEY_TEMPLATE, defaults.outputTemplate) ?: defaults.outputTemplate
        )

        Log.i(
            TAG,
            "load(): codec=${settings.videoCodec} (raw='$savedCodecRaw') " +
                "resolution=${settings.resolution} fps=${settings.frameRate.fps} " +
                "bitrate=${settings.bitrateOption}/${settings.bitrateMode} " +
                "audioSource=${settings.audioSource}"
        )
        return settings
    }

    fun save(settings: RecordingSettings) {
        prefs.edit()
            .putString(KEY_CODEC, settings.videoCodec.name)
            .putString(KEY_ORIENTATION, settings.orientation.name)
            .putString(KEY_RESOLUTION, settings.resolution.name)
            .putString(KEY_FPS, settings.frameRate.name)
            .putString(KEY_BITRATE, settings.bitrateOption.name)
            .putString(KEY_BITRATE_MODE, settings.bitrateMode.name)
            .putBoolean(KEY_ADV_BITRATE, settings.advancedBitrateUnlocked)
            .putString(KEY_COLOR_DEPTH, settings.colorDepth.name)
            .putString(KEY_AV1_SW_FALLBACK, settings.av1SoftwareFallback.name)
            .putString(KEY_AUDIO_SOURCE, settings.audioSource.name)
            .putString(KEY_AUDIO_QUALITY, settings.audioQuality.name)
            .putString(KEY_AUDIO_CHANNEL, settings.audioChannel.name)
            .putString(KEY_MIC_GAIN, settings.micGain.name)
            .putString(KEY_VOICE_PRIORITY, settings.voicePriority.name)
            .putInt(KEY_SYSTEM_LEVEL, settings.systemLevelPercent)
            .putInt(KEY_MIC_LEVEL, settings.micLevelPercent)
            .putString(KEY_AUDIO_MIX, settings.audioMix.name)
            .putString(KEY_AUDIO_MONITORING, settings.audioMonitoring.name)
            .putString(KEY_BLEED_SUPPRESSION, settings.bleedSuppression.name)
            .putBoolean(KEY_BUBBLE, settings.floatingBubbleEnabled)
            .putString(KEY_OVERLAY_VISIBILITY, settings.overlayVisibility.name)
            .putString(KEY_TEMPLATE, settings.outputTemplate)
            .apply()
        Log.i(TAG, "save(): codec=${settings.videoCodec} resolution=${settings.resolution} fps=${settings.frameRate.fps}")
    }

    fun setLastRecordingUri(uriString: String?) {
        prefs.edit().putString(KEY_LAST_RECORDING, uriString).apply()
    }

    fun getLastRecordingUri(): String? = prefs.getString(KEY_LAST_RECORDING, null)

    /** One-shot flag for the "use headphones for cleaner audio" tip (see
     * RecordingService#announceHeadphoneTipIfNeeded) -- shown at most once
     * ever, not once per recording, so it informs without nagging. */
    fun hasShownHeadphoneTip(): Boolean = prefs.getBoolean(KEY_HEADPHONE_TIP_SHOWN, false)
    fun setHeadphoneTipShown() { prefs.edit().putBoolean(KEY_HEADPHONE_TIP_SHOWN, true).apply() }

    // -- Explicit, non-generic parsers. Verbose on purpose; see class kdoc. --

    private fun parseVideoCodec(raw: String?): VideoCodecOption? =
        try { raw?.let { VideoCodecOption.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseOrientation(raw: String?): OrientationOption? =
        try { raw?.let { OrientationOption.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseResolution(raw: String?): ResolutionOption? =
        try { raw?.let { ResolutionOption.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseFrameRate(raw: String?): FrameRateOption? =
        try { raw?.let { FrameRateOption.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseBitrateOption(raw: String?): BitrateOption? =
        try { raw?.let { BitrateOption.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseBitrateMode(raw: String?): BitrateMode? =
        try { raw?.let { BitrateMode.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseColorDepth(raw: String?): ColorDepthOption? =
        try { raw?.let { ColorDepthOption.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseAv1SoftwareFallback(raw: String?): Av1SoftwareFallback? =
        try { raw?.let { Av1SoftwareFallback.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseOverlayVisibility(raw: String?): OverlayVisibilityMode? =
        try { raw?.let { OverlayVisibilityMode.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseBleedSuppression(raw: String?): BleedSuppressionMode? =
        try { raw?.let { BleedSuppressionMode.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseAudioSource(raw: String?): AudioSourceOption? =
        try { raw?.let { AudioSourceOption.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseAudioQuality(raw: String?): AudioQualityOption? =
        try { raw?.let { AudioQualityOption.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseAudioChannel(raw: String?): AudioChannelMode? =
        try { raw?.let { AudioChannelMode.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseMicGain(raw: String?): MicGainMode? =
        try { raw?.let { MicGainMode.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseVoicePriority(raw: String?): VoicePriority? =
        try { raw?.let { VoicePriority.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseAudioMix(raw: String?): AudioMixMode? =
        try { raw?.let { AudioMixMode.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    private fun parseAudioMonitoring(raw: String?): AudioMonitoringMode? =
        try { raw?.let { AudioMonitoringMode.valueOf(it) } } catch (e: IllegalArgumentException) { null }

    companion object {
        private const val TAG = "SettingsRepository"
        private const val PREFS_NAME = "recorderx_settings"
        private const val KEY_CODEC = "video_codec"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_FPS = "frame_rate"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_BITRATE_MODE = "bitrate_mode"
        private const val KEY_ADV_BITRATE = "advanced_bitrate"
        private const val KEY_COLOR_DEPTH = "color_depth"
        private const val KEY_AV1_SW_FALLBACK = "av1_software_fallback"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_AUDIO_QUALITY = "audio_quality"
        private const val KEY_AUDIO_CHANNEL = "audio_channel"
        private const val KEY_MIC_GAIN = "mic_gain"
        private const val KEY_VOICE_PRIORITY = "voice_priority"
        private const val KEY_SYSTEM_LEVEL = "system_level"
        private const val KEY_MIC_LEVEL = "mic_level"
        private const val KEY_AUDIO_MIX = "audio_mix"
        private const val KEY_AUDIO_MONITORING = "audio_monitoring"
        private const val KEY_BLEED_SUPPRESSION = "bleed_suppression"
        private const val KEY_BUBBLE = "floating_bubble"
        private const val KEY_OVERLAY_VISIBILITY = "overlay_visibility"
        private const val KEY_TEMPLATE = "output_template"
        private const val KEY_LAST_RECORDING = "last_recording_uri"
        private const val KEY_HEADPHONE_TIP_SHOWN = "headphone_tip_shown"
    }
}
