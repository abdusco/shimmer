package dev.abdus.apps.shimmer.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import java.util.concurrent.ArrayBlockingQueue

/**
 * Modern OpenGL ES wallpaper service using EGL14 APIs with VSync support.
 * Optimized for Android 8.0+ (API 26+) with GLES 3.0.
 */
open class GLWallpaperService : WallpaperService() {
    companion object {
        private const val TAG = "GLWallpaperService"
        const val RENDERMODE_WHEN_DIRTY = 0
        const val RENDERMODE_CONTINUOUSLY = 1
    }

    override fun onCreateEngine(): Engine {
        return GLEngine()
    }

    /**
     * Renderer interface for modern GLES 3.0 rendering.
     */
    interface Renderer {
        fun onSurfaceCreated()
        fun onSurfaceChanged(width: Int, height: Int)
        fun onDrawFrame()
        fun onSurfaceDestroyed() {}
    }

    open inner class GLEngine : Engine() {
        private var glThread: GLThread? = null
        private var renderer: Renderer? = null
        private var eglContextClientVersion = 3 // Default to GLES 3.0

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                glThread?.onResume()
            } else {
                glThread?.onPause()
            }
            super.onVisibilityChanged(visible)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
        }

        override fun onDestroy() {
            super.onDestroy()
            glThread?.requestExitAndWait()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            glThread?.onWindowResize(width, height)
            super.onSurfaceChanged(holder, format, width, height)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            glThread?.surfaceCreated(holder)
            super.onSurfaceCreated(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            glThread?.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        fun setRenderer(renderer: Renderer) {
            checkRenderThreadState()
            this.renderer = renderer
            glThread = GLThread(renderer, eglContextClientVersion)
            glThread?.start()
        }

        fun setEGLContextClientVersion(version: Int) {
            checkRenderThreadState()
            require(version == 2 || version == 3) { "Only GLES 2.0 and 3.0 are supported" }
            eglContextClientVersion = version
        }

        fun setRenderMode(renderMode: Int) {
            glThread?.setRenderMode(renderMode)
        }

        open fun requestRender() {
            glThread?.requestRender()
        }

        fun queueEvent(runnable: Runnable) {
            glThread?.queueEvent(runnable)
        }

        private fun checkRenderThreadState() {
            if (glThread != null) {
                throw IllegalStateException("setRenderer has already been called for this instance.")
            }
        }
    }
}

/**
 * EGL helper class using modern EGL14 APIs with VSync support.
 */
private class EglHelper(private val eglContextClientVersion: Int) {
    private var eglDisplay: EGLDisplay? = null
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    fun start() {
        if (eglContext != null) return // Already have a context, don't recreate!
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        eglDisplay = display

        // Modern EGL config optimized for Android 8.0+
        // Using RGB instead of RGBA - wallpapers are opaque, saves GPU memory
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, if (eglContextClientVersion == 3) 64 else 4,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw RuntimeException("eglChooseConfig failed")
        }

        if (numConfigs[0] == 0) {
            throw RuntimeException("No EGL config found")
        }

        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, eglContextClientVersion,
            EGL14.EGL_NONE
        )

        val context = EGL14.eglCreateContext(
            display,
            eglConfig!!,
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0
        )

        if (context == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }

        eglContext = context
    }

    fun createSurface(holder: SurfaceHolder): Boolean {
        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            destroySurface()
        }

        val surface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            holder.surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )

        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            val error = EGL14.eglGetError()
            Log.e("EglHelper", "createWindowSurface failed: $error")
            return false
        }

        eglSurface = surface

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            val error = EGL14.eglGetError()
            Log.e("EglHelper", "eglMakeCurrent failed: $error")
            return false
        }

        // Enable VSync by setting swap interval to 1
        // This synchronizes buffer swaps with the display's refresh rate
        if (!EGL14.eglSwapInterval(eglDisplay, 1)) {
            Log.w("EglHelper", "eglSwapInterval(1) failed, VSync may not be enabled")
        }

        return true
    }

    fun swap(): Boolean {
        val surface = eglSurface ?: return false
        if (surface == EGL14.EGL_NO_SURFACE) return false

        // With eglSwapInterval(1), this will block until VSync
        if (!EGL14.eglSwapBuffers(eglDisplay, surface)) {
            val error = EGL14.eglGetError()
            return error != EGL14.EGL_CONTEXT_LOST
        }
        return true
    }

    fun destroySurface() {
        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = null
        }
    }

    fun finish() {
        destroySurface() 

        if (eglContext != null) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = null
        }
        if (eglDisplay != null) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = null
        }
    }
}

/**
 * GL rendering thread using modern EGL14 APIs with VSync.
 */
private class GLThread(
    private val renderer: GLWallpaperService.Renderer,
    private val eglContextClientVersion: Int
) : Thread() {

    private val threadManager = GLThreadManager()
    private var holder: SurfaceHolder? = null
    private var width = 0
    private var height = 0
    private var sizeChanged = true

    @Volatile
    private var done = false

    @Volatile
    private var paused = false

    @Volatile
    private var hasSurface = false

    @Volatile
    private var waitingForSurface = false

    @Volatile
    private var haveEgl = false

    private var renderMode = GLWallpaperService.RENDERMODE_CONTINUOUSLY
    private var requestRender = true

    private val eventQueue = ArrayBlockingQueue<Runnable>(10)
    private var eventsWaiting = false

    private var eglHelper: EglHelper? = null

    init {
        name = "GLThread $id"
    }

    override fun run() {
        try {
            guardedRun()
        } catch (e: InterruptedException) {
            // Thread interrupted, exit gracefully
        } finally {
            threadManager.threadExiting(this)
        }
    }

    @Throws(InterruptedException::class)
    private fun guardedRun() {
        eglHelper = EglHelper(eglContextClientVersion)

        try {
            var tellRendererSurfaceCreated = true
            var tellRendererSurfaceChanged = true

            while (!isDone()) {
                var w = 0
                var h = 0
                var changed = false
                var needStart = false
                var eventsWaiting = false

                synchronized(threadManager) {
                    while (true) {
                        if (paused) {
                            stopEglLocked()
                        }

                        if (!hasSurface) {
                            if (!waitingForSurface) {
                                stopEglLocked()
                                waitingForSurface = true
                                (threadManager as Object).notifyAll()
                            }
                        } else {
                            if (!haveEgl) {
                                if (threadManager.tryAcquireEglSurface(this)) {
                                    haveEgl = true
                                    eglHelper!!.start()
                                    requestRender = true
                                    needStart = true
                                }
                            }
                        }

                        if (done) return

                        if (this.eventsWaiting) {
                            eventsWaiting = true
                            this.eventsWaiting = false
                            break
                        }

                        if (!paused && hasSurface && haveEgl && width > 0 && height > 0 &&
                            (requestRender || renderMode == GLWallpaperService.RENDERMODE_CONTINUOUSLY)
                        ) {
                            changed = sizeChanged
                            w = width
                            h = height
                            sizeChanged = false
                            requestRender = false
                            if (hasSurface && waitingForSurface) {
                                changed = true
                                waitingForSurface = false
                                (threadManager as Object).notifyAll()
                            }
                            break
                        }

                        (threadManager as Object).wait()
                    }
                }

                if (eventsWaiting) {
                    // Process events
                    if (!paused && haveEgl && hasSurface) {
                        try {
                            eglHelper!!.createSurface(holder!!)
                        } catch (e: Exception) {
                            Log.e("GLThread", "Error creating surface for events", e)
                        }
                    }

                    var runnable: Runnable?
                    while (getEvent().also { runnable = it } != null) {
                        runnable!!.run()
                        if (isDone()) return
                    }
                    continue
                }

                if (needStart) {
                    tellRendererSurfaceCreated = true
                    changed = true
                }

                if (changed) {
                    if (!eglHelper!!.createSurface(holder!!)) {
                        Log.e("GLThread", "Failed to create EGL surface")
                        continue
                    }
                    tellRendererSurfaceChanged = true
                }

                if (tellRendererSurfaceCreated) {
                    renderer.onSurfaceCreated()
                    tellRendererSurfaceCreated = false
                }

                if (tellRendererSurfaceChanged) {
                    renderer.onSurfaceChanged(w, h)
                    tellRendererSurfaceChanged = false
                }

                if (w > 0 && h > 0 && !paused) {
                    renderer.onDrawFrame()
                    // eglSwapBuffers will block until VSync when eglSwapInterval(1) is set
                    // No need for Thread.sleep() anymore!
                    eglHelper!!.swap()
                }
            }
        } finally {
            synchronized(threadManager) {
                stopEglLocked()
                eglHelper!!.finish()
            }
        }
    }

    private fun stopEglLocked() {
        if (haveEgl) {
            haveEgl = false
            eglHelper!!.destroySurface()
            threadManager.releaseEglSurface(this)
        }
    }

    private fun isDone(): Boolean {
        synchronized(threadManager) {
            return done
        }
    }

    fun setRenderMode(renderMode: Int) {
        synchronized(threadManager) {
            this.renderMode = renderMode
            if (renderMode == GLWallpaperService.RENDERMODE_CONTINUOUSLY) {
                (threadManager as Object).notifyAll()
            }
        }
    }

    fun requestRender() {
        synchronized(threadManager) {
            requestRender = true
            (threadManager as Object).notifyAll()
        }
    }

    fun surfaceCreated(holder: SurfaceHolder) {
        this.holder = holder
        synchronized(threadManager) {
            hasSurface = true
            (threadManager as Object).notifyAll()
        }
    }

    fun surfaceDestroyed() {
        synchronized(threadManager) {
            hasSurface = false
            (threadManager as Object).notifyAll()
            while (!waitingForSurface && isAlive && !done) {
                try {
                    (threadManager as Object).wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    fun onPause() {
        synchronized(threadManager) {
            paused = true
            (threadManager as Object).notifyAll()
        }
    }

    fun onResume() {
        synchronized(threadManager) {
            paused = false
            sizeChanged = true
            requestRender = true
            (threadManager as Object).notifyAll()
        }
    }

    fun onWindowResize(w: Int, h: Int) {
        synchronized(threadManager) {
            width = w
            height = h
            sizeChanged = true
            (threadManager as Object).notifyAll()
        }
    }

    fun requestExitAndWait() {
        synchronized(threadManager) {
            done = true
            (threadManager as Object).notifyAll()
        }
        try {
            join()
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun queueEvent(runnable: Runnable) {
        synchronized(this) {
            if (eventQueue.offer(runnable)) {
                synchronized(threadManager) {
                    eventsWaiting = true
                    (threadManager as Object).notifyAll()
                }
            } else {
                android.util.Log.w("GLThread", "Event queue full, dropping event")
            }
        }
    }

    private fun getEvent(): Runnable? {
        synchronized(this) {
            return eventQueue.poll()
        }
    }

    private inner class GLThreadManager {
        @Volatile
        private var eglOwner: GLThread? = null

        @Synchronized
        fun threadExiting(thread: GLThread) {
            thread.done = true
            if (eglOwner == thread) {
                eglOwner = null
            }
            (this as Object).notifyAll()
        }

        @Synchronized
        fun tryAcquireEglSurface(thread: GLThread): Boolean {
            if (eglOwner == thread || eglOwner == null) {
                eglOwner = thread
                (this as Object).notifyAll()
                return true
            }
            return false
        }

        @Synchronized
        fun releaseEglSurface(thread: GLThread) {
            if (eglOwner == thread) {
                eglOwner = null
            }
            (this as Object).notifyAll()
        }
    }
}