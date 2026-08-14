package com.recorderx.app.codec

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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
    val achievedFps: Int
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
     * AV1 -> HEVC -> AVC chain; H265 implies HEVC -> AVC; H264 is AVC only) and
     * returns the best hardware encoder that can handle [targetWidth]x[targetHeight],
     * clipping fps down from [targetFps] rather than the resolution if this
     * device's hardware genuinely can't sustain both together (see
     * [findEncoderFor]'s kdoc). Falls back to a software AVC encoder as an
     * absolute last resort so recording still works on a device with no
     * usable hardware path; callers should surface [CodecChoice.isHardware]
     * == false to the user.
     *
     * A mime is only skipped to the next cascade step when *no* encoder for
     * it exists at all -- never because of a resolution/fps mismatch, which
     * [findEncoderFor] resolves internally instead. Codec choice (which mime,
     * which physical encoder) and capability negotiation (how big / how fast)
     * are deliberately separate concerns.
     */
    fun findBestEncoder(
        preference: VideoCodecOption,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int
    ): CodecChoice? {
        val cascade = when (preference) {
            VideoCodecOption.AV1 -> listOf(MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            VideoCodecOption.H265 -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            VideoCodecOption.H264 -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
        }

        for (mime in cascade) {
            findHardwareEncoderFor(mime, targetWidth, targetHeight, targetFps)?.let { return it }
        }

        // Absolute last resort: any encoder at all for AVC, hardware or not, so
        // the app can still record (slower, hotter) instead of just failing.
        return findAnyEncoderFor(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight, targetFps)
    }

    private fun findHardwareEncoderFor(mime: String, w: Int, h: Int, fps: Int): CodecChoice? =
        findEncoderFor(mime, w, h, fps, hardwareOnly = true)

    private fun findAnyEncoderFor(mime: String, w: Int, h: Int, fps: Int): CodecChoice? =
        findEncoderFor(mime, w, h, fps, hardwareOnly = false)

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
    private fun findEncoderFor(mime: String, w: Int, h: Int, fps: Int, hardwareOnly: Boolean): CodecChoice? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var best: CodecChoice? = null
        var bestAreaDeficit = Long.MAX_VALUE
        var bestAchievedFps = -1
        val requestedArea = w.toLong() * h.toLong()

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            if (hardwareOnly && !info.isLikelyHardware()) continue

            val capabilities = try {
                info.getCapabilitiesForType(mime)
            } catch (e: IllegalArgumentException) {
                continue
            }
            val videoCaps = capabilities.videoCapabilities ?: continue
            val (adjW, adjH) = alignToSupportedSize(videoCaps, w, h) ?: continue
            if (!videoCaps.isSizeSupported(adjW, adjH)) continue

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

            val (profile, level) = pickProfileLevel(mime, capabilities.profileLevels)
            best = CodecChoice(
                codecName = info.name,
                mimeType = mime,
                width = adjW,
                height = adjH,
                isHardware = info.isLikelyHardware(),
                profile = profile,
                level = level,
                achievedFps = achievedFps
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
     * regardless of what KEY_BIT_RATE separately asks for. */
    private fun preferredProfile(mime: String): Int = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        MediaFormat.MIMETYPE_VIDEO_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        MediaFormat.MIMETYPE_VIDEO_AV1 -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8
        else -> 0
    }

    /**
     * Picks the highest-quality (profile, level) pair *this specific encoder*
     * actually advertises in [profileLevels]: [preferredProfile] if the codec
     * offers it at all, otherwise whatever profile it does offer (some low-end
     * AVC encoders genuinely only implement Baseline -- forcing High on those
     * would just make `configure()` throw, so this never demands a profile
     * the hardware didn't list). Among entries at the chosen profile, picks
     * the highest level, so the stream's bitrate ceiling is the codec's real
     * maximum rather than a low level a driver might default to on its own.
     *
     * Returns 0 to 0 ("don't set KEY_PROFILE/KEY_LEVEL at all") if the codec
     * reports no profile/level entries whatsoever -- letting the codec fall
     * back to its own default is always safe, even if it's the conservative
     * one this function otherwise tries to avoid.
     */
    private fun pickProfileLevel(mime: String, profileLevels: Array<MediaCodecInfo.CodecProfileLevel>?): Pair<Int, Int> {
        val entries = profileLevels?.toList().orEmpty()
        if (entries.isEmpty()) return 0 to 0
        val wanted = preferredProfile(mime)
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
}
