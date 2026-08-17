package com.recorderx.app.codec

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.recorderx.app.settings.ColorDepthOption
import com.recorderx.app.settings.VideoCodecOption
import com.recorderx.app.util.DeviceTier

/** Result of a successful encoder search: everything VideoEncoderPipeline needs
 * to configure MediaCodec, plus the size actually granted (which may have been
 * nudged to satisfy the codec's alignment/range requirements).
 *
 * [profile]/[level] are 0 when the codec reported no profile/level info at all
 * (rare, but seen on some very old OMX software paths) -- 0 means "don't set
 * KEY_PROFILE/KEY_LEVEL, let the codec pick its own default," which is always
 * a safe fallback. See [CodecSelector.pickProfileLevel] for why these are
 * resolved here instead of left to the codec's own default. */
data class CodecChoice(
    val codecName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val isHardware: Boolean,
    val profile: Int = 0,
    val level: Int = 0,
    /** The fps this exact (codecName, width, height) combination can actually
     * sustain, per this device's own declared capabilities -- may be lower
     * than the fps the user asked for (see [findEncoderFor]'s kdoc: resolution
     * is never sacrificed to hit a requested fps, fps is clipped instead).
     * Callers must configure the encoder with *this*, not the raw requested
     * fps, and should tell the user when the two disagree. */
    val achievedFps: Int,
    /** The color depth this exact candidate actually satisfies -- always
     * [ColorDepthOption.EIGHT_BIT] unless [ColorDepthOption.TEN_BIT] was
     * requested *and* this candidate genuinely advertises a Main10-family
     * profile for [mimeType] (see [pickProfileLevel]: a codec that can't is
     * skipped outright, never silently downgraded mid-candidate). Callers
     * compare this against what was requested exactly like [achievedFps],
     * and tell the user when [findBestEncoder] had to retry the whole
     * cascade at 8-bit because nothing in it could do 10-bit at all. */
    val colorDepth: ColorDepthOption = ColorDepthOption.EIGHT_BIT
)

object CodecSelector {

    /**
     * Auto default: on Android 8/9 or a device we classify as low/mid tier, the
     * spec explicitly asks for H.264 as the safe default. Elsewhere we default
     * to AV1, which -- per [findBestEncoder] -- really means "run the full
     * AV1 -> HEVC -> AVC cascade and take the best hit," letting capable
     * devices land on the most efficient codec automatically.
     */
    fun resolveDefaultPreference(context: Context): VideoCodecOption {
        val oldPlatform = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        val lowTier = DeviceTier.classify(context) == DeviceTier.Tier.LOW
        return if (oldPlatform || lowTier) VideoCodecOption.H264 else VideoCodecOption.AV1
    }

    /**
     * Walks the cascade implied by [preference] (AV1 implies the full
     * AV1 -> [software AV1, if [allowSoftwareAv1]] -> HEVC -> AVC chain; H265
     * implies HEVC -> AVC; H264 is AVC only) and returns the best encoder that
     * can handle [targetWidth]x[targetHeight] at [colorDepth], clipping fps
     * down from [targetFps] rather than the resolution if this device's
     * hardware genuinely can't sustain both together (see [findEncoderFor]'s
     * kdoc). Falls back to a software AVC encoder as an absolute last resort
     * so recording still works on a device with no usable hardware path;
     * callers should surface [CodecChoice.isHardware] == false to the user.
     *
     * A mime is only skipped to the next cascade step when *no* encoder for
     * it exists at all -- never because of a resolution/fps mismatch, which
     * [findEncoderFor] resolves internally instead. Codec choice (which mime,
     * which physical encoder) and capability negotiation (how big / how fast)
     * are deliberately separate concerns.
     *
     * If [colorDepth] is [ColorDepthOption.TEN_BIT] and nothing anywhere in
     * the cascade genuinely advertises a Main10-family profile, the entire
     * cascade is retried once at [ColorDepthOption.EIGHT_BIT] rather than
     * failing outright -- [CodecChoice.colorDepth] on the result tells the
     * caller this happened, exactly like [CodecChoice.achievedFps] does for a
     * clipped fps.
     */
    fun findBestEncoder(
        preference: VideoCodecOption,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        colorDepth: ColorDepthOption = ColorDepthOption.EIGHT_BIT,
        allowSoftwareAv1: Boolean = false
    ): CodecChoice? {
        findAtColorDepth(preference, targetWidth, targetHeight, targetFps, colorDepth, allowSoftwareAv1)?.let { return it }
        if (colorDepth == ColorDepthOption.TEN_BIT) {
            return findAtColorDepth(preference, targetWidth, targetHeight, targetFps, ColorDepthOption.EIGHT_BIT, allowSoftwareAv1)
        }
        return null
    }

    private fun findAtColorDepth(
        preference: VideoCodecOption,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        colorDepth: ColorDepthOption,
        allowSoftwareAv1: Boolean
    ): CodecChoice? {
        if (preference == VideoCodecOption.AV1) {
            findHardwareEncoderFor(MediaFormat.MIMETYPE_VIDEO_AV1, targetWidth, targetHeight, targetFps, colorDepth)?.let { return it }
            // Only ever inserted right here -- straight after hardware AV1 and
            // before HEVC/AVC -- so an explicit opt-in for "real AV1" is
            // honored ahead of a faster/cooler substitute codec, matching why
            // the user turns this on in the first place. Software 10-bit AV1
            // isn't offered (see findSoftwareAv1Encoder); the TEN_BIT retry in
            // findBestEncoder above still reaches this same 8-bit branch on
            // its second pass.
            if (allowSoftwareAv1 && colorDepth == ColorDepthOption.EIGHT_BIT) {
                findSoftwareAv1Encoder(targetWidth, targetHeight, targetFps)?.let { return it }
            }
        }
        val cascade = when (preference) {
            VideoCodecOption.AV1 -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            VideoCodecOption.H265 -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            VideoCodecOption.H264 -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        for (mime in cascade) {
            findHardwareEncoderFor(mime, targetWidth, targetHeight, targetFps, colorDepth)?.let { return it }
        }

        // AVC's "High10" profile exists on paper (CodecProfileLevel declares
        // it) but has no meaningful hardware *or* software presence on real
        // Android devices -- not worth a last-resort attempt that would only
        // ever fail. A 10-bit request that gets this far returns null, which
        // sends findBestEncoder's caller back around the whole cascade at
        // 8-bit instead.
        if (colorDepth == ColorDepthOption.TEN_BIT) return null

        // Absolute last resort: any encoder at all for AVC, hardware or not, so
        // the app can still record (slower, hotter) instead of just failing.
        return findAnyEncoderFor(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight, targetFps, colorDepth)
    }

    /**
     * The specific gap behind "AV1 kodekte video kaydını başlatırken
     * desteklenmediği için hiçbir şey yapamıyoruz": [findBestEncoder]'s
     * cascade only ever searched `hardwareOnly = true` for AV1/HEVC/AVC, and
     * its *one* non-hardware attempt was AVC, never AV1 -- so even on a
     * device that genuinely has AOSP's software (libaom-based) AV1 encoder
     * (shipping since Android 14 on updated media modules, registered like
     * any other `MediaCodecList` entry), this class structurally never tried
     * it and silently cascaded straight past AV1 to HEVC/AVC hardware
     * instead. This function is the missing, explicit attempt -- gated
     * behind [com.recorderx.app.settings.Av1SoftwareFallback.ON] because
     * software encoding is real but dramatically slower and hotter than any
     * hardware path, so it must never be a silent default.
     *
     * Two deliberate, hard caps, both because CPU-bound encode throughput on
     * a phone-class SoC is nowhere near a hardware block's, and both matching
     * exactly what was asked for ("Maksimum çözünürlük olarak AV1 tarafında
     * 1080P yeterlidir"):
     * - Resolution: only even attempted when the *already-resolved* target is
     *   at or under 1080p's long/short edge. Above that, this function isn't
     *   called at all and the cascade just continues to HEVC/AVC hardware --
     *   deliberately never silently rescaling the user's exact resolution
     *   pick, unlike every other choice in this app.
     * - FPS: capped to [SOFTWARE_AV1_MAX_FPS] up front rather than trusting
     *   whatever this software codec's own `VideoCapabilities` happens to
     *   declare (software codecs are known to over-declare a "supported"
     *   frame-rate range that doesn't reflect real sustained throughput).
     *   [CodecChoice.achievedFps] still carries the real number back through
     *   the normal path, so `RecordingService`'s existing fps-clipped toast
     *   tells the user when this applied -- no new mechanism needed.
     */
    private fun findSoftwareAv1Encoder(targetWidth: Int, targetHeight: Int, targetFps: Int): CodecChoice? {
        val longEdge = maxOf(targetWidth, targetHeight)
        val shortEdge = minOf(targetWidth, targetHeight)
        if (longEdge > SOFTWARE_AV1_MAX_LONG_EDGE || shortEdge > SOFTWARE_AV1_MAX_SHORT_EDGE) return null
        val cappedFps = targetFps.coerceAtMost(SOFTWARE_AV1_MAX_FPS)
        return findEncoderFor(
            MediaFormat.MIMETYPE_VIDEO_AV1, targetWidth, targetHeight, cappedFps,
            hardwareOnly = false, softwareOnly = true, colorDepth = ColorDepthOption.EIGHT_BIT
        )
    }

    private fun findHardwareEncoderFor(mime: String, w: Int, h: Int, fps: Int, colorDepth: ColorDepthOption): CodecChoice? =
        findEncoderFor(mime, w, h, fps, hardwareOnly = true, softwareOnly = false, colorDepth = colorDepth)

    private fun findAnyEncoderFor(mime: String, w: Int, h: Int, fps: Int, colorDepth: ColorDepthOption): CodecChoice? =
        findEncoderFor(mime, w, h, fps, hardwareOnly = false, softwareOnly = false, colorDepth = colorDepth)

    /**
     * Scores *every* matching [MediaCodecInfo] for [mime] (a device can expose
     * more than one -- some chipsets register a second, size-restricted entry
     * for the same mime that only unlocks high fps at a much smaller
     * resolution, e.g. a "slow-motion" capability set) and returns the single
     * best one.
     *
     * "Best" means: resolution as close to the requested [w]x[h] as this
     * device can actually do, full stop -- fps is *never* traded away for it.
     * This replaced an earlier version that rejected a whole codec entry
     * outright whenever [fps] exceeded [MediaCodecInfo.VideoCapabilities
     * .getSupportedFrameRatesFor] at the *requested* size, which on a real
     * device with exactly that kind of dual capability set meant the loop
     * kept falling through past the entry that could do the real resolution
     * and landed on the high-fps/tiny-resolution one instead -- the actual
     * mechanism behind "120 fps seçtiğimde 512x512 kalitesinde çekmeye
     * başlıyor." Only once the best-achievable resolution is fixed does this
     * function look at what fps *that specific* encoder can sustain there,
     * and clips down to it (never up) -- [CodecChoice.achievedFps] carries
     * that real number back to the caller so the encoder is configured with
     * it (not the raw request) and the user can be told when it's lower.
     */
    private fun findEncoderFor(
        mime: String,
        w: Int,
        h: Int,
        fps: Int,
        hardwareOnly: Boolean,
        softwareOnly: Boolean = false,
        colorDepth: ColorDepthOption = ColorDepthOption.EIGHT_BIT
    ): CodecChoice? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var best: CodecChoice? = null
        var bestAreaDeficit = Long.MAX_VALUE
        var bestAchievedFps = -1
        val requestedArea = w.toLong() * h.toLong()

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            if (hardwareOnly && !info.isLikelyHardware()) continue
            if (softwareOnly && info.isLikelyHardware()) continue

            val capabilities = try {
                info.getCapabilitiesForType(mime)
            } catch (e: IllegalArgumentException) {
                continue
            }
            val videoCaps = capabilities.videoCapabilities ?: continue

            // This app's whole capture pipeline is Surface-input only (see
            // VideoEncoderPipeline's kdoc: MediaProjection -> FramePacer ->
            // the encoder's own createInputSurface(), zero CPU-side pixel
            // copies anywhere). A candidate that doesn't list
            // COLOR_FormatSurface among its supported color formats can't
            // actually back that, even when its mime/resolution/profile all
            // check out -- createInputSurface() throws on it. This is the
            // actual mechanism behind "AV1 kodek ile kayıt yapmak istediğim
            // zaman uygulama çöküyor" for CPU-based (software) AV1
            // specifically: Android's software AV1 encoder is a Codec2
            // "Linear" (buffer-only) component on many builds, never
            // Surface/Graphic, so it used to get picked here anyway and only
            // failed once VideoEncoderPipeline actually tried to configure()
            // it. Checked once, right alongside the resolution and
            // profile/level checks below, so a candidate like that is simply
            // skipped during selection -- the cascade continues to the next
            // candidate/mime instead of crashing on one that was never
            // usable by this pipeline to begin with.
            if (!capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue

            val (adjW, adjH) = alignToSupportedSize(videoCaps, w, h) ?: continue
            if (!videoCaps.isSizeSupported(adjW, adjH)) continue

            // Resolved before the area-deficit comparison below: a candidate
            // that can't satisfy the requested color depth at all (see
            // pickProfileLevel) is skipped outright, exactly like a candidate
            // that can't satisfy the requested mime or hardware-ness.
            val (profile, level) = pickProfileLevel(mime, colorDepth, capabilities.profileLevels) ?: continue

            // How much smaller than requested this specific encoder forces us
            // to go -- 0 when it can do the exact requested size. This, never
            // fps, is what picks the winner between multiple entries.
            val areaDeficit = requestedArea - (adjW.toLong() * adjH.toLong())
            val achievedFps = maxSustainableFps(videoCaps, adjW, adjH, fps)

            val better = when {
                best == null -> true
                areaDeficit != bestAreaDeficit -> areaDeficit < bestAreaDeficit
                // Tie on resolution match: prefer whichever entry sustains more fps there.
                else -> achievedFps > bestAchievedFps
            }
            if (!better) continue

            best = CodecChoice(
                codecName = info.name,
                mimeType = mime,
                width = adjW,
                height = adjH,
                isHardware = info.isLikelyHardware(),
                profile = profile,
                level = level,
                achievedFps = achievedFps,
                colorDepth = colorDepth
            )
            bestAreaDeficit = areaDeficit
            bestAchievedFps = achievedFps
        }
        return best
    }

    /** Caps [requestedFps] down to what [caps] actually declares it can
     * sustain at [w]x[h] -- never up, and never used to reject the encoder
     * outright (some devices report frame-rate ranges conservatively; a
     * clipped-but-real recording beats no recording). Null capability info
     * (rare) is treated as "no declared ceiling," i.e. trust the request. */
    private fun maxSustainableFps(caps: MediaCodecInfo.VideoCapabilities, w: Int, h: Int, requestedFps: Int): Int {
        val supportedFps = try {
            caps.getSupportedFrameRatesFor(w, h)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return requestedFps
        return requestedFps.coerceAtMost(supportedFps.upper.toInt()).coerceAtLeast(1)
    }

    /** The profile that unlocks each codec's full compression toolset (B-frames,
     * CABAC entropy coding for AVC, etc.) rather than the stripped-down profile
     * some vendor drivers silently default to when `configure()` doesn't request
     * one explicitly. That silent default is a real, common cause of "bitrate
     * and resolution are both set high, but it still doesn't look sharp" --
     * a low profile caps both the encoding tools available *and*, via its
     * paired level, the maximum bitrate the stream is even allowed to use,
     * regardless of what KEY_BIT_RATE separately asks for.
     *
     * At [ColorDepthOption.TEN_BIT] this returns the mime's Main10-family
     * profile instead -- [pickProfileLevel] then *requires* an exact match
     * against what the codec actually advertises rather than falling back to
     * "whatever it offers," since silently handing back an 8-bit profile here
     * would mean the encoder configures successfully but simply isn't
     * producing the 10-bit stream the user asked for, with nothing to tell
     * them so. */
    private fun preferredProfile(mime: String, colorDepth: ColorDepthOption): Int = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        MediaFormat.MIMETYPE_VIDEO_HEVC ->
            if (colorDepth == ColorDepthOption.TEN_BIT) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        MediaFormat.MIMETYPE_VIDEO_AV1 ->
            if (colorDepth == ColorDepthOption.TEN_BIT) MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
            else MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8
        else -> 0
    }

    /** Every Main10-family profile constant this app is willing to accept as
     * "genuinely 10-bit" for [mime] -- HDR10/HDR10+ variants included, since
     * they're still ten-bit-per-sample streams, just with extra static/
     * dynamic metadata this app doesn't populate. AVC has no entry: its
     * "High10" profile exists as a `CodecProfileLevel` constant but has no
     * meaningful hardware or software presence on real Android devices, so
     * it's deliberately never offered as a match. */
    private fun main10Profiles(mime: String): Set<Int> = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_HEVC -> setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        )
        MediaFormat.MIMETYPE_VIDEO_AV1 -> setOf(
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
        )
        else -> emptySet()
    }

    /**
     * Picks the highest-quality (profile, level) pair *this specific encoder*
     * actually advertises in [profileLevels]. At [ColorDepthOption.EIGHT_BIT]:
     * [preferredProfile] if the codec offers it at all, otherwise whatever
     * profile it does offer (some low-end AVC encoders genuinely only
     * implement Baseline -- forcing High on those would just make
     * `configure()` throw, so this never demands a profile the hardware
     * didn't list). Among entries at the chosen profile, picks the highest
     * level, so the stream's bitrate ceiling is the codec's real maximum
     * rather than a low level a driver might default to on its own.
     *
     * At [ColorDepthOption.TEN_BIT] this is deliberately *not* the same
     * permissive fallback: only [main10Profiles] entries are considered, and
     * this returns `null` -- meaning "this codec candidate is skipped
     * entirely, not silently recorded in 8-bit" -- when it doesn't advertise
     * any of them. [findEncoderFor] treats a null return exactly like a
     * resolution the codec can't hit at all.
     *
     * Returns 0 to 0 ("don't set KEY_PROFILE/KEY_LEVEL at all") only at
     * 8-bit, if the codec reports no profile/level entries whatsoever --
     * letting the codec fall back to its own default is always safe there,
     * even if it's the conservative one this function otherwise tries to
     * avoid; at 10-bit, no entries at all trivially means no Main10 entry
     * either, so that case also returns `null`.
     */
    private fun pickProfileLevel(
        mime: String,
        colorDepth: ColorDepthOption,
        profileLevels: Array<MediaCodecInfo.CodecProfileLevel>?
    ): Pair<Int, Int>? {
        val entries = profileLevels?.toList().orEmpty()
        if (colorDepth == ColorDepthOption.TEN_BIT) {
            val wantedProfiles = main10Profiles(mime)
            val tenBitEntries = entries.filter { it.profile in wantedProfiles }
            val best = tenBitEntries.maxByOrNull { it.level } ?: return null
            return best.profile to best.level
        }
        if (entries.isEmpty()) return 0 to 0
        val wanted = preferredProfile(mime, colorDepth)
        val candidates = entries.filter { it.profile == wanted }.ifEmpty { entries }
        val best = candidates.maxByOrNull { it.level } ?: return 0 to 0
        return best.profile to best.level
    }

    /** Rounds [w]x[h] to the codec's required width/height alignment and clamps
     * into its supported range, so `configure()` doesn't throw on an odd size
     * like a 1080x2412 panel. Returns null if no aligned size is achievable. */
    private fun alignToSupportedSize(
        caps: MediaCodecInfo.VideoCapabilities,
        w: Int,
        h: Int
    ): Pair<Int, Int>? {
        val widthAlignment = caps.widthAlignment.coerceAtLeast(2)
        val heightAlignment = caps.heightAlignment.coerceAtLeast(2)

        var alignedW = (w / widthAlignment) * widthAlignment
        var alignedH = (h / heightAlignment) * heightAlignment
        if (alignedW == 0 || alignedH == 0) return null

        val widthRange = caps.supportedWidths
        val heightRange = caps.supportedHeights
        alignedW = alignedW.coerceIn(widthRange.lower, widthRange.upper)
        alignedH = alignedH.coerceIn(heightRange.lower, heightRange.upper)

        // Re-truncate to alignment after clamping, in case the clamp landed on
        // a boundary that isn't itself a multiple of the alignment.
        alignedW = (alignedW / widthAlignment) * widthAlignment
        alignedH = (alignedH / heightAlignment) * heightAlignment
        if (alignedW <= 0 || alignedH <= 0) return null

        return alignedW to alignedH
    }

    /** API 29+ exposes this directly; below that we fall back to the
     * long-standing naming convention (OMX.google.* / c2.android.* are
     * Google's *software* implementations, everything else is a vendor's
     * hardware path). Not perfect, but it's the same heuristic the wider
     * Android ecosystem has relied on since before isHardwareAccelerated() existed. */
    private fun MediaCodecInfo.isLikelyHardware(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return isHardwareAccelerated
        }
        val n = name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
    }

    // Software (CPU) AV1 is only ever attempted at or under 1080p -- see
    // findSoftwareAv1Encoder's kdoc. 1920/1080 rather than a strict
    // "long==1920 && short==1080" check so 1080p in either orientation,
    // and anything smaller, both qualify.
    private const val SOFTWARE_AV1_MAX_LONG_EDGE = 1920
    private const val SOFTWARE_AV1_MAX_SHORT_EDGE = 1080

    // Deliberately conservative rather than trusting the software codec's
    // own declared frame-rate ceiling -- see findSoftwareAv1Encoder's kdoc.
    private const val SOFTWARE_AV1_MAX_FPS = 30
}
