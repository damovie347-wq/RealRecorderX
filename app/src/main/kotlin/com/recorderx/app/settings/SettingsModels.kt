package com.recorderx.app.settings

/**
 * Every enum below carries its own short display [label] so sliders can be
 * built data-driven (see ui/SegmentedSliderView + MainActivity) instead of
 * hand-writing a near-identical XML block per setting. Labels are short,
 * technical tokens (H.264, 4K, VBR...) rather than prose, which is why they
 * live here as data instead of in strings.xml -- see build notes in
 * ARCHITECTURE.md if you want to localize them anyway.
 */

/** User's codec *ceiling*: "try up to this, cascade down if unsupported."
 * AV1 == "full auto cascade" (AV1 -> HEVC -> AVC), matching the spec's
 * default priority order. See codec/CodecSelector.kt for the fallback logic. */
enum class VideoCodecOption(val label: String, val mimeType: String) {
    H264("H.264", "video/avc"),
    H265("H.265", "video/hevc"),
    AV1("AV1", "video/av01")
}

enum class OrientationOption(val label: String) {
    AUTO("AUTO"),
    PORTRAIT("PORT"),
    LANDSCAPE("LAND")
}

/** Fixed, exact industry-standard 16:9 pixel targets (long edge x short edge,
 * i.e. the landscape-oriented pair -- ResolutionResolver swaps them for
 * portrait). These are deliberately NOT derived from the device's panel
 * aspect ratio: an earlier version recomputed the short edge from the real
 * panel's aspect (e.g. 3840 x round(3840*panelAspect)), which on any panel
 * that isn't exactly 16:9 (most tablets are 16:10, 4:3, or similar) silently
 * produced a size that was neither the panel's native resolution nor the
 * standard resolution the label promised -- "I picked 4K but didn't get
 * 3840x2160." Locking these to the literal standard values is what the user
 * picking "4K" actually expects, matching every other camera/recorder app.
 * NATIVE is the one exception, by design: it deliberately follows the real
 * panel size (see [ResolutionResolver]), not a fixed pair. */
enum class ResolutionOption(val label: String, val longEdge: Int, val shortEdge: Int) {
    NATIVE("NATIVE", 0, 0), // 0,0 == "use the panel's real resolution, unscaled"
    UHD_4K("4K", 3840, 2160),
    QHD_2K("2K", 2560, 1440),
    FHD("FHD", 1920, 1080),
    HD("HD", 1280, 720),
    SD_480("480", 854, 480)
}

enum class FrameRateOption(val label: String, val fps: Int) {
    FPS_24("24", 24),
    FPS_30("30", 30),
    FPS_60("60", 60),
    FPS_90("90", 90),
    FPS_120("120", 120)
}

/** The "core" bitrate ladder shown by default. Advanced Mode (toggle in the
 * UI) additionally unlocks [ADV_60M]..[ADV_100_PLUS]. AUTO is the default
 * selection and defers to BitrateAdvisor rather than a fixed number. */
enum class BitrateOption(val label: String, val bps: Int) {
    AUTO("AUTO", -1),
    BR_2M("2M", 2_000_000),
    BR_4M("4M", 4_000_000),
    BR_8M("8M", 8_000_000),
    BR_12M("12M", 12_000_000),
    BR_20M("20M", 20_000_000),
    BR_40M("40M", 40_000_000),
    ADV_60M("60M", 60_000_000),
    ADV_80M("80M", 80_000_000),
    ADV_100_PLUS("100M+", 100_000_000)
}

enum class BitrateMode(val label: String) {
    VBR("VBR"),
    CBR("CBR")
}

/**
 * Encoder-side sample precision. TEN_BIT only ever changes anything real
 * when the *content itself* is higher-than-8-bit (an HDR game, an HDR video
 * played back on-screen) -- MediaProjection mirrors whatever SurfaceFlinger
 * already composited, which is ordinary 8-bit RGBA8888 for the overwhelming
 * majority of Android UI/gameplay content today, so 10-bit on that source is
 * a wider container around the same information, not new detail. It's still
 * offered because (a) it's a real, correct encoder configuration this app
 * can honestly provide -- see [com.recorderx.app.codec.CodecSelector] -- and
 * (b) it's genuinely future-facing as HDR screen content and HDR-aware
 * capture become more common. `label_color_depth_note` in strings.xml is the
 * in-UI disclosure of this, matching how [ResolutionOption]'s upscaling case
 * is disclosed rather than hidden.
 */
enum class ColorDepthOption(val label: String) {
    EIGHT_BIT("8-BIT"),
    TEN_BIT("10-BIT")
}

/**
 * Whether the AV1 cascade may land on a *software* (CPU/libaom-based) AV1
 * encoder when no hardware AV1 encoder exists, instead of moving straight on
 * to HEVC/AVC hardware. Off by default: software AV1 is real, but dramatically
 * slower than any hardware path and meaningfully increases heat/battery use --
 * exactly the kind of trade-off that should be an explicit opt-in, not a
 * silent default, for users who specifically want genuine AV1 output over a
 * different (faster, cooler) codec. See `CodecSelector.findSoftwareAv1Encoder`
 * for why this is also capped at 1080p regardless of the Resolution picker.
 */
enum class Av1SoftwareFallback(val label: String) {
    OFF("OFF"),
    ON("ON (CPU, UP TO 1080P)")
}

/**
 * How the floating pause/stop bubble trades off "reachable on screen" against
 * "not in the recording" -- see `overlay/RecordingOverlayController`'s kdoc
 * for why a normal app can't have both at once. VISIBLE is the original
 * always-shown-small-and-fading behavior; AUTO_HIDE detaches the window
 * outright a few seconds after it's shown (same mechanism as the manual eye-
 * icon hide) so it's reachable only via the notification's "Show controls"
 * action / the Quick Settings tile, trading reachability for the cleanest
 * frames a third-party app can offer; BLACKOUT restores `FLAG_SECURE` on the
 * bubble specifically for users who've decided a small solid-black shape is
 * preferable to legible controls appearing in their footage.
 */
enum class OverlayVisibilityMode(val label: String) {
    VISIBLE("VISIBLE"),
    AUTO_HIDE("AUTO-HIDE"),
    BLACKOUT("BLACKOUT")
}

/**
 * Strength of [com.recorderx.app.audio.ResidualBleedSuppressor]'s gain-based
 * suppression of system audio bleeding back into the mic through the device's
 * own speaker (see that class's kdoc). NORMAL is the tuned default; STRONG
 * trades a bit more audible mic-ducking on loud content for a deeper cut on
 * genuinely bad bleed (a phone speaker at high volume a few cm from the mic,
 * with no headset) -- exactly the "one explosion sound plays twice" report
 * this exists to address. OFF disables the second-stage suppressor entirely
 * (platform AEC, if the device has one, still runs regardless -- see
 * `MicAudioSource`) for anyone who'd rather tune monitoring manually.
 */
enum class BleedSuppressionMode(val label: String) {
    OFF("OFF"),
    NORMAL("NORMAL"),
    STRONG("STRONG")
}

enum class AudioSourceOption(val label: String, val wantsSystem: Boolean, val wantsMic: Boolean) {
    OFF("OFF", wantsSystem = false, wantsMic = false),
    MIC("MIC", wantsSystem = false, wantsMic = true),
    SYS("SYS", wantsSystem = true, wantsMic = false),
    SYS_MIC("SYS+MIC", wantsSystem = true, wantsMic = true)
}

enum class AudioQualityOption(val label: String, val aacBitrate: Int, val sampleRate: Int) {
    LOW("LOW", 96_000, 44_100),
    MID("MID", 160_000, 48_000),
    MAX("MAX", 256_000, 48_000)
}

enum class AudioChannelMode(val label: String, val channelCount: Int) {
    AUTO("AUTO", -1), // resolved at record time from what the hardware actually offers
    MONO("MONO", 1),
    STEREO("STEREO", 2)
}

enum class MicGainMode(val label: String, val linearGain: Float) {
    AUTO("AUTO", -1f), // handled by AutomaticGainControl instead of a fixed multiplier
    LOW("LOW", 0.6f),
    NORMAL("NORMAL", 1.0f),
    HIGH("HIGH", 1.6f)
}

enum class VoicePriority(val label: String, val duckFloor: Float) {
    OFF("OFF", 1.0f),
    LOW("LOW", 0.55f),
    MEDIUM("MEDIUM", 0.35f),
    HIGH("HIGH", 0.15f)
}

enum class AudioMixMode(val label: String) {
    SYSTEM_ONLY("SYSTEM ONLY"),
    MIC_ONLY("MIC ONLY"),
    SYSTEM_MIC("SYSTEM + MIC")
}

enum class AudioMonitoringMode(val label: String) {
    OFF("OFF"),
    HEADPHONES_ONLY("HEADPHONES ONLY")
}

/**
 * The full, persisted shape of a user's recording configuration. Immutable +
 * copy() so the UI layer and RecordingService both work with plain snapshots
 * rather than a shared mutable object.
 */
data class RecordingSettings(
    val videoCodec: VideoCodecOption = VideoCodecOption.AV1,
    val orientation: OrientationOption = OrientationOption.AUTO,
    val resolution: ResolutionOption = ResolutionOption.NATIVE,
    val frameRate: FrameRateOption = FrameRateOption.FPS_30,
    val bitrateOption: BitrateOption = BitrateOption.AUTO,
    val bitrateMode: BitrateMode = BitrateMode.VBR,
    val advancedBitrateUnlocked: Boolean = false,
    val colorDepth: ColorDepthOption = ColorDepthOption.EIGHT_BIT,
    val av1SoftwareFallback: Av1SoftwareFallback = Av1SoftwareFallback.OFF,

    val audioSource: AudioSourceOption = AudioSourceOption.SYS_MIC,
    val audioQuality: AudioQualityOption = AudioQualityOption.MID,
    val audioChannel: AudioChannelMode = AudioChannelMode.AUTO,
    val micGain: MicGainMode = MicGainMode.AUTO,
    val voicePriority: VoicePriority = VoicePriority.OFF,
    val systemLevelPercent: Int = 100, // 0..200
    val micLevelPercent: Int = 100,    // 0..200
    val audioMix: AudioMixMode = AudioMixMode.SYSTEM_MIC,
    val audioMonitoring: AudioMonitoringMode = AudioMonitoringMode.OFF,
    val bleedSuppression: BleedSuppressionMode = BleedSuppressionMode.NORMAL,

    val floatingBubbleEnabled: Boolean = true,
    val overlayVisibility: OverlayVisibilityMode = OverlayVisibilityMode.VISIBLE,
    val outputTemplate: String = "RecorderX_{timestamp}"
)
