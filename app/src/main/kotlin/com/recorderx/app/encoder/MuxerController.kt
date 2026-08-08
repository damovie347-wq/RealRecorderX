package com.recorderx.app.encoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * A [MediaMuxer] needs every track added via [MediaMuxer.addTrack] *before*
 * [MediaMuxer.start] is called, and after that point the set of tracks is
 * frozen. Since the video encoder and the audio encoder run on their own
 * threads and reach "I know my output format now" (MediaCodec's
 * INFO_OUTPUT_FORMAT_CHANGED) at different, unpredictable times, something
 * has to gate "actually start the muxer" until every expected track has
 * registered -- that's this class's only job.
 *
 * Threads that have data before the muxer has started simply wait
 * ([awaitStarted]) rather than dropping frames or crashing on
 * writeSampleData before start().
 */
class MuxerController(
    fd: FileDescriptor,
    private val expectedTrackCount: Int
) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Object()

    private var registeredTracks = 0
    @Volatile private var started = false
    @Volatile private var released = false

    /** Called once per track (video, and audio if enabled) when its encoder
     * reports its real output MediaFormat. Returns the muxer track index to
     * use for subsequent writeSampleData calls. */
    fun registerTrack(format: MediaFormat): Int = synchronized(lock) {
        val index = muxer.addTrack(format)
        registeredTracks++
        if (registeredTracks >= expectedTrackCount && !started) {
            muxer.start()
            started = true
            lock.notifyAll()
        }
        index
    }

    /** Blocks the calling (encoder drain) thread until every expected track
     * has registered and the muxer is actually running. Safe to call from
     * multiple threads. */
    fun awaitStarted() {
        synchronized(lock) {
            while (!started && !released) {
                lock.wait(500)
            }
        }
    }

    fun isStarted(): Boolean = started

    fun writeSample(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || released) return
        synchronized(lock) {
            if (!started || released) return
            muxer.writeSampleData(trackIndex, buffer, info)
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            try {
                if (started) muxer.stop()
            } catch (e: Exception) {
                // Best-effort: if the encoder threads never produced a single
                // frame (e.g. permission revoked mid-handshake) stop() can throw
                // because the muxer never actually started writing samples.
            }
            try {
                muxer.release()
            } catch (e: Exception) {
                // Already released or never fully initialized -- nothing to do.
            }
            lock.notifyAll()
        }
    }
}
