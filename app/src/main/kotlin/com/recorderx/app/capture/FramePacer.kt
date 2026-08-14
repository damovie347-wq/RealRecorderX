package com.recorderx.app.capture

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * The actual fix for "60 fps seçtim ama 30-40 fps çekiyor" / "15 fps
 * seçtim ama 17 fps çekiyor": on a Surface-input [android.media.MediaCodec],
 * [android.media.MediaFormat.KEY_FRAME_RATE] is only a bitrate-calculation
 * hint, and [android.hardware.display.VirtualDisplay] otherwise hands the
 * encoder a new frame every time SurfaceFlinger recomposites -- i.e. at
 * whatever cadence the *content* changes and the panel's own refresh rate
 * allow, not at the fps the user picked. `MediaFormat.KEY_MAX_FPS_TO_ENCODER`
 * (still set in VideoEncoderPipeline as a best-effort belt-and-suspenders
 * hint) is an undocumented vendor-extension-style key that not every chipset
 * driver honors, which is exactly the gap this class closes for real: it
 * puts a GPU texture-copy stage *between* the mirrored screen content and
 * the encoder, and *this app*, not any vendor driver, decides when a frame
 * crosses that stage -- on a fixed, drift-free schedule derived from the
 * user's chosen fps, full stop.
 *
 * ## Pipeline shape
 * `MediaProjection.createVirtualDisplay` targets [virtualDisplaySurface] (a
 * [Surface] backed by an off-screen [SurfaceTexture]/OES texture) instead of
 * the encoder's input surface directly. A single dedicated GL thread wakes on
 * a precise schedule (`1/fps` seconds apart, recomputed from the previous
 * *target* wake time rather than "now + interval" so small scheduling jitter
 * never accumulates into long-term drift), latches whatever the most recent
 * mirrored frame is via [SurfaceTexture.updateTexImage] (re-latching the same
 * content again, harmlessly, if the screen hasn't changed since the last
 * tick -- which is *correct*: a paused/static screen should still emit frames
 * at the target cadence, not fall silent and skew the output's effective fps),
 * blits it with a trivial one-triangle-strip shader onto the encoder's real
 * input surface, and stamps an explicit presentation time on the way out.
 *
 * ## Why System.nanoTime() for the presentation timestamp
 * Deliberately the *same* clock domain SurfaceFlinger itself would have
 * stamped a frame with (boot-relative monotonic nanoseconds -- confirmed:
 * System.nanoTime() on Android is backed by CLOCK_MONOTONIC, the same domain
 * used throughout the platform's graphics stack), specifically so
 * VideoEncoderPipeline's existing session-base-rebase and pause/resume-gap
 * logic keeps working completely unmodified: from its point of view a paced
 * frame looks exactly like a SurfaceFlinger-produced one, just arriving on a
 * cleaner schedule.
 *
 * ## Cost
 * One GPU texture sample + blit per output frame, at exactly the configured
 * fps (never more) -- cheaper than the *uncontrolled* frame flow it replaces
 * whenever the real display refresh rate exceeds the chosen recording fps
 * (a 120Hz panel recording at 30fps previously hearted the encoder with up to
 * 4x the frames actually needed; this caps it at the source). No CPU-side
 * pixel copy anywhere in this path -- SurfaceTexture -> texture -> encoder
 * surface is GPU-to-GPU throughout.
 */
class FramePacer(
    private val encoderSurface: Surface,
    initialFps: Int,
    private val width: Int,
    private val height: Int,
    private val onError: (Throwable) -> Unit
) {
    /** Target for MediaProjection's VirtualDisplay. Valid only after [start]
     * has returned. */
    lateinit var virtualDisplaySurface: Surface
        private set

    @Volatile private var targetFps: Int = initialFps.coerceAtLeast(1)
    @Volatile private var running = false
    @Volatile private var ticking = true

    private var glThread: Thread? = null
    private val egl = EglCore()
    private var eglSurface: EGLSurface? = null
    private var oesTextureId = 0
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private val texMatrix = FloatArray(16)
    private var surfaceTexture: SurfaceTexture? = null
    private var signalThread: HandlerThread? = null
    private val hasFirstFrame = AtomicBoolean(false)

    private val vertexBuffer = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoordBuffer = floatBufferOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    /** Blocks briefly (typically well under a frame's worth of time) until the
     * GL thread has finished EGL/GLES setup and [virtualDisplaySurface] is
     * ready to hand to MediaProjection. Throws if setup failed -- GLES2 has
     * been mandatory on every Android device since long before this app's
     * minSdk, so failure here means something is genuinely wrong, not a
     * device-capability gap to silently work around. */
    fun start() {
        running = true
        ticking = true
        val ready = CountDownLatch(1)
        val t = Thread({ glThreadBody(ready) }, "RecorderX-FramePacer")
        glThread = t
        t.start()
        ready.await(2, TimeUnit.SECONDS)
        if (!this::virtualDisplaySurface.isInitialized) {
            throw IllegalStateException("FramePacer failed to initialize (see preceding log for the real cause)")
        }
    }

    /** Stops advancing the schedule (no more frames reach the encoder) without
     * tearing down GL/EGL state, so [resume] is cheap. Mirrors
     * RecordingService#pauseInternal releasing just the VirtualDisplay while
     * leaving the encoder itself alive. */
    fun pause() {
        ticking = false
    }

    /** Resumes the fixed-cadence schedule from "now" -- deliberately does not
     * try to catch up on ticks missed while paused, which would just burst a
     * run of redundant frames at the encoder for no visual benefit. */
    fun resume() {
        ticking = true
    }

    /** Live fps change, applied on the very next tick. Wired to
     * ThermalBitrateGovernor so a thermal fps cap actually changes what
     * reaches the encoder now that this class, not any codec-level hint,
     * owns delivery cadence. */
    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceAtLeast(1)
    }

    fun release() {
        running = false
        ticking = false
        val t = glThread
        if (t != null) {
            LockSupport.unpark(t)
            t.join(1000)
        }
        glThread = null
    }

    private fun glThreadBody(ready: CountDownLatch) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        try {
            egl.setup()
            val surface = egl.createWindowSurface(encoderSurface)
            eglSurface = surface
            egl.makeCurrent(surface)

            oesTextureId = createOesTexture()
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            // Sampler uniforms default to texture unit 0 per the GLES spec,
            // matching the glActiveTexture(GL_TEXTURE0) used in drawFrame()
            // below -- but set it explicitly, once, rather than lean on that
            // default, since it costs nothing and removes any doubt.
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

            val st = SurfaceTexture(oesTextureId)
            surfaceTexture = st
            virtualDisplaySurface = Surface(st)

            // SurfaceTexture's frame-available listener needs a Looper; this
            // thread runs its own precise tick scheduler below instead of
            // Looper.loop(), so a tiny dedicated HandlerThread relays just the
            // *first* frame's arrival (see updateTexImage()'s kdoc in
            // runTickLoop for why frames 2..N don't need a listener at all).
            val sig = HandlerThread("RecorderX-FrameSignal").apply { start() }
            signalThread = sig
            st.setOnFrameAvailableListener({ hasFirstFrame.set(true) }, Handler(sig.looper))

            ready.countDown()
            runTickLoop()
        } catch (t: Throwable) {
            Log.e(TAG, "glThreadBody(): fatal error", t)
            ready.countDown()
            onError(t)
        } finally {
            cleanupGl()
        }
    }

    private fun runTickLoop() {
        // Calling updateTexImage() before the producer side has ever queued a
        // buffer throws -- MediaProjection's VirtualDisplay can take a moment
        // after creation to start actually compositing, so wait for real
        // content rather than assuming frame 1 is already there.
        while (running && !hasFirstFrame.get()) {
            LockSupport.parkNanos(2_000_000L)
        }
        if (!running) return

        var nextDeadline = System.nanoTime()
        while (running) {
            if (!ticking) {
                // Paused: idle in short bursts and keep resyncing the
                // schedule to "now" so resume() doesn't fire a burst of
                // catch-up frames for however long the pause lasted.
                LockSupport.parkNanos(20_000_000L)
                nextDeadline = System.nanoTime()
                continue
            }

            val st = surfaceTexture ?: break
            try {
                // Always safe to call again even if nothing new arrived since
                // the last tick -- it just re-latches the same content, which
                // is exactly the desired behavior for static screen content:
                // the output still gets a frame every tick at the target fps
                // instead of silently emitting fewer frames than requested.
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)
            } catch (e: Exception) {
                Log.w(TAG, "updateTexImage() failed, skipping this tick's draw", e)
            }

            val surface = eglSurface
            if (surface != null) {
                drawFrame()
                egl.setPresentationTime(surface, System.nanoTime())
                egl.swapBuffers(surface)
            }

            val frameIntervalNs = 1_000_000_000L / targetFps.coerceAtLeast(1)
            nextDeadline += frameIntervalNs
            val now = System.nanoTime()
            if (nextDeadline <= now) {
                // Fell behind (a slow draw, or a live fps drop) -- resync to
                // "now" rather than firing back-to-back catch-up frames.
                nextDeadline = now
            } else {
                LockSupport.parkNanos(nextDeadline - now)
            }
        }
    }

    private fun drawFrame() {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    private fun cleanupGl() {
        try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (e: Exception) { /* ignore */ }
        try { signalThread?.quitSafely() } catch (e: Exception) { /* ignore */ }
        try { virtualDisplaySurface.release() } catch (e: Exception) { /* not initialized, or already released */ }
        try { surfaceTexture?.release() } catch (e: Exception) { /* ignore */ }
        if (program != 0) try { GLES20.glDeleteProgram(program) } catch (e: Exception) { /* ignore */ }
        if (oesTextureId != 0) try { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0) } catch (e: Exception) { /* ignore */ }
        eglSurface?.let { try { egl.releaseSurface(it) } catch (e: Exception) { /* ignore */ } }
        try { egl.release() } catch (e: Exception) { /* ignore */ }
    }

    private fun createOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return texId
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw IllegalStateException("program link failed: $log")
        }
        return prog
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    companion object {
        private const val TAG = "FramePacer"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
