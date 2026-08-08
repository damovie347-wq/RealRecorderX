package com.recorderx.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.recorderx.app.codec.CodecSelector

/**
 * Plain SharedPreferences, not androidx.datastore: DataStore pulls in protobuf
 * or an extra Preferences artifact for a job that ~20 primitive key/value pairs
 * handle just fine. One more example of "don't add a dependency you don't need."
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): RecordingSettings {
        val defaults = RecordingSettings()
        return RecordingSettings(
            // No saved value yet (first launch) -- resolve the *smart* default
            // (H.264 on Android 8/9 or a low-tier device, AV1's full fallback
            // cascade elsewhere) instead of a single hardcoded constant.
            videoCodec = savedEnumOrNull(KEY_CODEC)
                ?: CodecSelector.resolveDefaultPreference(appContext),
            orientation = enumOf(KEY_ORIENTATION, defaults.orientation),
            resolution = enumOf(KEY_RESOLUTION, defaults.resolution),
            frameRate = enumOf(KEY_FPS, defaults.frameRate),
            bitrateOption = enumOf(KEY_BITRATE, defaults.bitrateOption),
            bitrateMode = enumOf(KEY_BITRATE_MODE, defaults.bitrateMode),
            advancedBitrateUnlocked = prefs.getBoolean(KEY_ADV_BITRATE, defaults.advancedBitrateUnlocked),
            audioSource = enumOf(KEY_AUDIO_SOURCE, defaults.audioSource),
            audioQuality = enumOf(KEY_AUDIO_QUALITY, defaults.audioQuality),
            audioChannel = enumOf(KEY_AUDIO_CHANNEL, defaults.audioChannel),
            micGain = enumOf(KEY_MIC_GAIN, defaults.micGain),
            voicePriority = enumOf(KEY_VOICE_PRIORITY, defaults.voicePriority),
            systemLevelPercent = prefs.getInt(KEY_SYSTEM_LEVEL, defaults.systemLevelPercent),
            micLevelPercent = prefs.getInt(KEY_MIC_LEVEL, defaults.micLevelPercent),
            audioMix = enumOf(KEY_AUDIO_MIX, defaults.audioMix),
            audioMonitoring = enumOf(KEY_AUDIO_MONITORING, defaults.audioMonitoring),
            floatingBubbleEnabled = prefs.getBoolean(KEY_BUBBLE, defaults.floatingBubbleEnabled),
            outputTemplate = prefs.getString(KEY_TEMPLATE, defaults.outputTemplate) ?: defaults.outputTemplate
        )
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
            .putString(KEY_AUDIO_SOURCE, settings.audioSource.name)
            .putString(KEY_AUDIO_QUALITY, settings.audioQuality.name)
            .putString(KEY_AUDIO_CHANNEL, settings.audioChannel.name)
            .putString(KEY_MIC_GAIN, settings.micGain.name)
            .putString(KEY_VOICE_PRIORITY, settings.voicePriority.name)
            .putInt(KEY_SYSTEM_LEVEL, settings.systemLevelPercent)
            .putInt(KEY_MIC_LEVEL, settings.micLevelPercent)
            .putString(KEY_AUDIO_MIX, settings.audioMix.name)
            .putString(KEY_AUDIO_MONITORING, settings.audioMonitoring.name)
            .putBoolean(KEY_BUBBLE, settings.floatingBubbleEnabled)
            .putString(KEY_TEMPLATE, settings.outputTemplate)
            .apply()
    }

    fun setLastRecordingUri(uriString: String?) {
        prefs.edit().putString(KEY_LAST_RECORDING, uriString).apply()
    }

    fun getLastRecordingUri(): String? = prefs.getString(KEY_LAST_RECORDING, null)

    private inline fun <reified T : Enum<T>> enumOf(key: String, default: T): T {
        val raw = prefs.getString(key, null) ?: return default
        return try {
            enumValueOf<T>(raw)
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    /** Returns null (rather than a fallback) when nothing has been saved yet,
     * so the caller can tell "first launch" apart from "user picked this
     * value" -- only used for videoCodec's device-aware smart default. */
    private inline fun <reified T : Enum<T>> savedEnumOrNull(key: String): T? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            enumValueOf<T>(raw)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "recorderx_settings"
        private const val KEY_CODEC = "video_codec"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_FPS = "frame_rate"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_BITRATE_MODE = "bitrate_mode"
        private const val KEY_ADV_BITRATE = "advanced_bitrate"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_AUDIO_QUALITY = "audio_quality"
        private const val KEY_AUDIO_CHANNEL = "audio_channel"
        private const val KEY_MIC_GAIN = "mic_gain"
        private const val KEY_VOICE_PRIORITY = "voice_priority"
        private const val KEY_SYSTEM_LEVEL = "system_level"
        private const val KEY_MIC_LEVEL = "mic_level"
        private const val KEY_AUDIO_MIX = "audio_mix"
        private const val KEY_AUDIO_MONITORING = "audio_monitoring"
        private const val KEY_BUBBLE = "floating_bubble"
        private const val KEY_TEMPLATE = "output_template"
        private const val KEY_LAST_RECORDING = "last_recording_uri"
    }
}
