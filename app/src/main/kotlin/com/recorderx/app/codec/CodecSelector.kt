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
 * nudged to satisfy the codec's alignment/range requirements). */
data class CodecChoice(
    val codecName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val isHardware: Boolean
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
     * returns the first hardware encoder that can handle [targetWidth]x[targetHeight]
     * at [targetFps]. Falls back to a software AVC encoder as an absolute last
     * resort so recording still works on a device with no usable hardware path;
     * callers should surface [CodecChoice.isHardware] == false to the user.
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

    private fun findEncoderFor(mime: String, w: Int, h: Int, fps: Int, hardwareOnly: Boolean): CodecChoice? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
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

            val supportedFps = try {
                videoCaps.getSupportedFrameRatesFor(adjW, adjH)
            } catch (e: IllegalArgumentException) {
                null
            }
            // Not a hard requirement (some devices report frame-rate ranges
            // conservatively) -- we only use this to prefer, not to exclude.
            if (supportedFps != null && fps > supportedFps.upper.toInt() + 5) {
                continue
            }

            return CodecChoice(
                codecName = info.name,
                mimeType = mime,
                width = adjW,
                height = adjH,
                isHardware = info.isLikelyHardware()
            )
        }
        return null
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
