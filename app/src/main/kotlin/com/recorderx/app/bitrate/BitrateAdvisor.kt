package com.recorderx.app.bitrate

import android.media.MediaFormat

/**
 * Standard "bits per pixel per frame" estimation: target_bps = width * height *
 * fps * bpp, where bpp is tuned per codec efficiency (AV1 needs meaningfully
 * fewer bits than AVC for the same perceived quality, HEVC sits in between).
 * This is the same rule-of-thumb encoders/streaming guides have used for years --
 * nothing proprietary, just resolution x motion x codec-efficiency math.
 *
 * Keyed off the *actually resolved* encoder mime type (post codec-fallback),
 * not the user's preference -- if AV1 was requested but the device fell back
 * to AVC hardware, the suggestion should reflect AVC's lower efficiency, not
 * the codec that never actually got used.
 */
object BitrateAdvisor {

    private const val MIN_BITRATE_BPS = 1_500_000
    private const val MAX_BITRATE_BPS = 120_000_000

    private fun bitsPerPixel(mimeType: String): Double = when (mimeType) {
        MediaFormat.MIMETYPE_VIDEO_AV1 -> 0.038
        MediaFormat.MIMETYPE_VIDEO_HEVC -> 0.055
        else -> 0.085 // AVC, or anything unrecognized -- assume the least efficient case
    }

    /**
     * [motionFactor] is a coarse content-type multiplier (1.0 = typical mixed
     * UI/video content). Screen recordings of mostly-static content (reading,
     * a document, a slow UI) can reasonably use ~0.6-0.7; fast-motion content
     * (gameplay, video playback) benefits from ~1.2-1.4. VBR mode already does
     * *within-recording* adaptation on top of whatever fixed target this
     * returns -- see AdaptiveBitrateController for the runtime side of that.
     */
    fun suggestBitrateBps(
        width: Int,
        height: Int,
        fps: Int,
        resolvedMimeType: String,
        motionFactor: Double = 1.0
    ): Int {
        val pixels = width.toLong() * height.toLong()
        val raw = pixels * fps * bitsPerPixel(resolvedMimeType) * motionFactor
        return raw.toInt().coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS)
    }

    /** Human-readable "8.4 Mbps" style label for the suggestion line under the
     * Bitrate slider. */
    fun formatMbps(bps: Int): String {
        val mbps = bps / 1_000_000.0
        return if (mbps >= 10) "%.0f Mbps".format(mbps) else "%.1f Mbps".format(mbps)
    }
}
