package com.recorderx.app.capture

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Minimal EGL/GLES2 context wrapper -- exactly enough for [FramePacer] to (1)
 * create a context, (2) wrap a producer [Surface] (here, the video encoder's
 * own [android.media.MediaCodec.createInputSurface] surface) as an EGL window
 * surface to render into, and (3) submit frames to it with an explicit,
 * caller-chosen presentation timestamp. This is the standard, long-established
 * pattern for "render into MediaCodec's input Surface via GLES" (the same
 * shape as Grafika/bigflake's EglCore + CodecInputSurface reference classes),
 * trimmed to only what a single dedicated render thread needs -- not a
 * general-purpose, multi-surface/multi-context GL utility.
 */
class EglCore {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    fun setup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        // EGL_RECORDABLE_ANDROID is the Android-specific hint that this config
        // is suitable for feeding a MediaCodec/MediaRecorder input surface --
        // without it, some vendor GPU drivers hand back a config whose buffer
        // format the video encoder then has to fight with.
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val chose = EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        check(chose && numConfigs[0] > 0) { "eglChooseConfig failed" }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    /** Wraps [surface] as a renderable EGL window surface. */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    /** [nanos] should be in the same monotonic clock domain SurfaceFlinger
     * itself stamps frames in (System.nanoTime() on Android is that clock --
     * see FramePacer's kdoc) so downstream PTS-rebasing logic that already
     * assumes "boot-relative nanoseconds" keeps working unmodified. */
    fun setPresentationTime(eglSurface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nanos)
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    fun releaseSurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
    }

    companion object {
        // Not exposed as a typed constant by android.opengl.EGL14 on all API
        // levels, so used as the raw attribute int directly (same value on
        // every platform version -- it's a fixed EGL enum, not a capability).
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
