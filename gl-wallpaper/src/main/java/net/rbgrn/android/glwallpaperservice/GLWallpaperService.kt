/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 package net.rbgrn.android.glwallpaperservice

 import android.opengl.GLSurfaceView
 import android.service.wallpaper.WallpaperService
 import android.util.Log
 import android.view.SurfaceHolder
 import java.io.Writer
 import java.util.ArrayList
 import javax.microedition.khronos.egl.EGL10
 import javax.microedition.khronos.egl.EGL11
 import javax.microedition.khronos.egl.EGLConfig
 import javax.microedition.khronos.egl.EGLContext
 import javax.microedition.khronos.egl.EGLDisplay
 import javax.microedition.khronos.egl.EGLSurface
 import javax.microedition.khronos.opengles.GL
 import javax.microedition.khronos.opengles.GL10
 
 open class GLWallpaperService : WallpaperService() {
     companion object {
         private const val TAG = "GLWallpaperService"
         const val RENDERMODE_WHEN_DIRTY = 0
         const val RENDERMODE_CONTINUOUSLY = 1
     }
 
     override fun onCreateEngine(): Engine {
         return GLEngine()
     }
 
     open inner class GLEngine : Engine() {
 
         private var mGLThread: GLThread? = null
         private var mEGLConfigChooser: GLSurfaceView.EGLConfigChooser? = null
         private var mEGLContextFactory: GLSurfaceView.EGLContextFactory? = null
         private var mEGLWindowSurfaceFactory: GLSurfaceView.EGLWindowSurfaceFactory? = null
         private var mGLWrapper: GLSurfaceView.GLWrapper? = null
         private var mDebugFlags = 0
         private var mEGLContextClientVersion = 0
 
         override fun onVisibilityChanged(visible: Boolean) {
             if (visible) {
                 onResume()
             } else {
                 onPause()
             }
             super.onVisibilityChanged(visible)
         }
 
         override fun onCreate(surfaceHolder: SurfaceHolder) {
             super.onCreate(surfaceHolder)
         }
 
         override fun onDestroy() {
             super.onDestroy()
             mGLThread?.requestExitAndWait()
         }
 
         override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
             mGLThread?.onWindowResize(width, height)
             super.onSurfaceChanged(holder, format, width, height)
         }
 
         override fun onSurfaceCreated(holder: SurfaceHolder) {
             mGLThread?.surfaceCreated(holder)
             super.onSurfaceCreated(holder)
         }
 
         override fun onSurfaceDestroyed(holder: SurfaceHolder) {
             mGLThread?.surfaceDestroyed()
             super.onSurfaceDestroyed(holder)
         }
 
         fun setRenderer(renderer: GLSurfaceView.Renderer) {
             checkRenderThreadState()
             if (mEGLConfigChooser == null) {
                 mEGLConfigChooser = BaseConfigChooser.SimpleEGLConfigChooser(true, mEGLContextClientVersion)
             }
             if (mEGLContextFactory == null) {
                 mEGLContextFactory = DefaultContextFactory(mEGLContextClientVersion)
             }
             if (mEGLWindowSurfaceFactory == null) {
                 mEGLWindowSurfaceFactory = DefaultWindowSurfaceFactory()
             }
             mGLThread = GLThread(renderer, mEGLConfigChooser!!, mEGLContextFactory!!, mEGLWindowSurfaceFactory!!, mGLWrapper)
             mGLThread?.start()
         }
 
         fun setEGLConfigChooser(
             redSize: Int,
             greenSize: Int,
             blueSize: Int,
             alphaSize: Int,
             depthSize: Int,
             stencilSize: Int
         ) {
             setEGLConfigChooser(
                 BaseConfigChooser.ComponentSizeChooser(
                     redSize, greenSize, blueSize, alphaSize,
                     depthSize, stencilSize, mEGLContextClientVersion
                 )
             )
         }
 
         fun setEGLConfigChooser(configChooser: GLSurfaceView.EGLConfigChooser) {
             checkRenderThreadState()
             mEGLConfigChooser = configChooser
         }
 
         open fun setEGLContextClientVersion(version: Int) {
             checkRenderThreadState()
             mEGLContextClientVersion = version
         }
 
         open fun setRenderMode(renderMode: Int) {
             mGLThread?.setRenderMode(renderMode)
         }
 
         open fun requestRender() {
             mGLThread?.requestRender()
         }
 
         fun onPause() {
             mGLThread?.onPause()
         }
 
         fun onResume() {
             mGLThread?.onResume()
         }
 
         fun queueEvent(r: Runnable) {
             mGLThread?.queueEvent(r)
         }
 
         private fun checkRenderThreadState() {
             if (mGLThread != null) {
                 throw IllegalStateException("setRenderer has already been called for this instance.")
             }
         }
     }
 }
 
 private class DefaultContextFactory(private val eglContextClientVersion: Int) : GLSurfaceView.EGLContextFactory {
     companion object {
         private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
     }
 
     override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
         val attribList = intArrayOf(
             EGL_CONTEXT_CLIENT_VERSION, eglContextClientVersion,
             EGL10.EGL_NONE
         )
         return egl.eglCreateContext(
             display, config, EGL10.EGL_NO_CONTEXT,
             if (eglContextClientVersion != 0) attribList else null
         )
     }
 
     override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
         egl.eglDestroyContext(display, context)
     }
 }
 
 private class DefaultWindowSurfaceFactory : GLSurfaceView.EGLWindowSurfaceFactory {
     override fun createWindowSurface(
         egl: EGL10,
         display: EGLDisplay,
         config: EGLConfig,
         nativeWindow: Any
     ): EGLSurface {
         var eglSurface: EGLSurface? = null
         while (eglSurface == null) {
             try {
                 eglSurface = egl.eglCreateWindowSurface(display, config, nativeWindow, null)
             } catch (t: Throwable) {
             } finally {
                 if (eglSurface == null) {
                     try {
                         Thread.sleep(10)
                     } catch (t: InterruptedException) {
                         Thread.currentThread().interrupt()
                     }
                 }
             }
         }
         return eglSurface
     }
 
     override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
         egl.eglDestroySurface(display, surface)
     }
 }
 
 private class EglHelper(
     private val mEGLConfigChooser: GLSurfaceView.EGLConfigChooser,
     private val mEGLContextFactory: GLSurfaceView.EGLContextFactory,
     private val mEGLWindowSurfaceFactory: GLSurfaceView.EGLWindowSurfaceFactory,
     private val mGLWrapper: GLSurfaceView.GLWrapper?
 ) {
     private var mEgl: EGL10? = null
     private var mEglDisplay: EGLDisplay? = null
     private var mEglSurface: EGLSurface? = null
     private var mEglContext: EGLContext? = null
     var mEglConfig: EGLConfig? = null
         private set
 
     fun start() {
         if (mEgl == null) {
             mEgl = EGLContext.getEGL() as EGL10
         }
         if (mEglDisplay == null) {
             mEglDisplay = mEgl!!.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
         }
         if (mEglConfig == null) {
             val version = IntArray(2)
             mEgl!!.eglInitialize(mEglDisplay, version)
             mEglConfig = mEGLConfigChooser.chooseConfig(mEgl!!, mEglDisplay!!)
         }
         if (mEglContext == null) {
             mEglContext = mEGLContextFactory.createContext(mEgl!!, mEglDisplay!!, mEglConfig!!)
             if (mEglContext == null || mEglContext == EGL10.EGL_NO_CONTEXT) {
                 throw RuntimeException("createContext failed")
             }
         }
         mEglSurface = null
     }
 
     fun createSurface(holder: SurfaceHolder): GL {
         if (mEglSurface != null && mEglSurface != EGL10.EGL_NO_SURFACE) {
             mEgl!!.eglMakeCurrent(mEglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
             mEGLWindowSurfaceFactory.destroySurface(mEgl!!, mEglDisplay!!, mEglSurface!!)
         }
 
         mEglSurface = mEGLWindowSurfaceFactory.createWindowSurface(mEgl!!, mEglDisplay!!, mEglConfig!!, holder)
 
         if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
             throw RuntimeException("createWindowSurface failed")
         }
 
         if (!mEgl!!.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
             throw RuntimeException("eglMakeCurrent failed.")
         }
 
         var gl = mEglContext!!.gl
         if (mGLWrapper != null) {
             gl = mGLWrapper.wrap(gl)
         }
         return gl
     }
 
     fun swap(): Boolean {
         val surface = mEglSurface
         if (surface == null || surface == EGL10.EGL_NO_SURFACE) return false
         
         mEgl!!.eglSwapBuffers(mEglDisplay, surface)
         return mEgl!!.eglGetError() != EGL11.EGL_CONTEXT_LOST
     }
 
     fun destroySurface() {
         if (mEglSurface != null && mEglSurface != EGL10.EGL_NO_SURFACE) {
             mEgl!!.eglMakeCurrent(mEglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
             mEGLWindowSurfaceFactory.destroySurface(mEgl!!, mEglDisplay!!, mEglSurface!!)
             mEglSurface = null
         }
     }
 
     fun finish() {
         if (mEglContext != null) {
             mEGLContextFactory.destroyContext(mEgl!!, mEglDisplay!!, mEglContext!!)
             mEglContext = null
         }
         if (mEglDisplay != null) {
             mEgl!!.eglTerminate(mEglDisplay)
             mEglDisplay = null
         }
     }
 }
 
 private class GLThread(
     private val mRenderer: GLSurfaceView.Renderer,
     private val mEGLConfigChooser: GLSurfaceView.EGLConfigChooser,
     private val mEGLContextFactory: GLSurfaceView.EGLContextFactory,
     private val mEGLWindowSurfaceFactory: GLSurfaceView.EGLWindowSurfaceFactory,
     private val mGLWrapper: GLSurfaceView.GLWrapper?
 ) : Thread() {
 
     private val sGLThreadManager = GLThreadManager()
     private var mEglOwner: GLThread? = null
 
     var mHolder: SurfaceHolder? = null
         private set
     private var mSizeChanged = true
 
     var mDone = false
         private set
     private var mPaused = false
     private var mHasSurface = false
     private var mWaitingForSurface = false
     private var mHaveEgl = false
     private var mWidth = 0
     private var mHeight = 0
     private var mRenderMode = GLWallpaperService.RENDERMODE_CONTINUOUSLY
     private var mRequestRender = true
     private var mEventsWaiting = false
 
     private val mEventQueue = ArrayList<Runnable>()
     private var mEglHelper: EglHelper? = null
 
     init {
         mDone = false
         mWidth = 0
         mHeight = 0
         mRequestRender = true
         mRenderMode = GLWallpaperService.RENDERMODE_CONTINUOUSLY
     }
 
     override fun run() {
         name = "GLThread $id"
         try {
             guardedRun()
         } catch (e: InterruptedException) {
         } finally {
             sGLThreadManager.threadExiting(this)
         }
     }
 
     private fun stopEglLocked() {
         if (mHaveEgl) {
             mHaveEgl = false
             mEglHelper!!.destroySurface()
             sGLThreadManager.releaseEglSurface(this)
         }
     }
 
     @Throws(InterruptedException::class)
     private fun guardedRun() {
         mEglHelper = EglHelper(mEGLConfigChooser, mEGLContextFactory, mEGLWindowSurfaceFactory, mGLWrapper)
         try {
             var gl: GL10? = null
             var tellRendererSurfaceCreated = true
             var tellRendererSurfaceChanged = true
 
             while (!isDone()) {
                 var w = 0
                 var h = 0
                 var changed = false
                 var needStart = false
                 var eventsWaiting = false
 
                 synchronized(sGLThreadManager) {
                     while (true) {
                         if (mPaused) {
                             stopEglLocked()
                         }
                         if (!mHasSurface) {
                             if (!mWaitingForSurface) {
                                 stopEglLocked()
                                 mWaitingForSurface = true
                                 (sGLThreadManager as Object).notifyAll()
                             }
                         } else {
                             if (!mHaveEgl) {
                                 if (sGLThreadManager.tryAcquireEglSurface(this)) {
                                     mHaveEgl = true
                                     mEglHelper!!.start()
                                     mRequestRender = true
                                     needStart = true
                                 }
                             }
                         }
 
                         if (mDone) return
 
                         if (mEventsWaiting) {
                             eventsWaiting = true
                             mEventsWaiting = false
                             break
                         }
 
                         if ((!mPaused) && mHasSurface && mHaveEgl && (mWidth > 0) && (mHeight > 0)
                             && (mRequestRender || (mRenderMode == GLWallpaperService.RENDERMODE_CONTINUOUSLY))
                         ) {
                             changed = mSizeChanged
                             w = mWidth
                             h = mHeight
                             mSizeChanged = false
                             mRequestRender = false
                             if (mHasSurface && mWaitingForSurface) {
                                 changed = true
                                 mWaitingForSurface = false
                                 (sGLThreadManager as Object).notifyAll()
                             }
                             break
                         }
                         (sGLThreadManager as Object).wait()
                     }
                 }
 
                 if (eventsWaiting) {
                     // Before processing events, ensure the context is current if we are not paused
                     if (!mPaused && mHaveEgl && mHasSurface) {
                        try {
                            gl = mEglHelper!!.createSurface(mHolder!!) as GL10
                        } catch (e: Exception) {}
                     }
                     
                     var r: Runnable?
                     while (getEvent().also { r = it } != null) {
                         r!!.run()
                         if (isDone()) return
                     }
                     continue
                 }
 
                 if (needStart) {
                     tellRendererSurfaceCreated = true
                     changed = true
                 }
                 if (changed) {
                     gl = mEglHelper!!.createSurface(mHolder!!) as GL10
                     tellRendererSurfaceChanged = true
                 }
                 if (tellRendererSurfaceCreated) {
                     mRenderer.onSurfaceCreated(gl, mEglHelper!!.mEglConfig!!)
                     tellRendererSurfaceCreated = false
                 }
                 if (tellRendererSurfaceChanged) {
                     mRenderer.onSurfaceChanged(gl, w, h)
                     tellRendererSurfaceChanged = false
                 }
                 if ((w > 0) && (h > 0) && !mPaused) {
                     mRenderer.onDrawFrame(gl)
                     mEglHelper!!.swap()
                     Thread.sleep(10)
                 }
             }
         } finally {
             synchronized(sGLThreadManager) {
                 stopEglLocked()
                 mEglHelper!!.finish()
             }
         }
     }
 
     private fun isDone(): Boolean {
         synchronized(sGLThreadManager) {
             return mDone
         }
     }
 
     fun setRenderMode(renderMode: Int) {
         synchronized(sGLThreadManager) {
             mRenderMode = renderMode
             if (renderMode == GLWallpaperService.RENDERMODE_CONTINUOUSLY) {
                 (sGLThreadManager as Object).notifyAll()
             }
         }
     }
 
     fun requestRender() {
         synchronized(sGLThreadManager) {
             mRequestRender = true
             (sGLThreadManager as Object).notifyAll()
         }
     }
 
     fun surfaceCreated(holder: SurfaceHolder) {
         mHolder = holder
         synchronized(sGLThreadManager) {
             mHasSurface = true
             (sGLThreadManager as Object).notifyAll()
         }
     }
 
     fun surfaceDestroyed() {
         synchronized(sGLThreadManager) {
             mHasSurface = false
             (sGLThreadManager as Object).notifyAll()
             while (!mWaitingForSurface && isAlive && !mDone) {
                 try {
                     (sGLThreadManager as Object).wait()
                 } catch (e: InterruptedException) {
                     Thread.currentThread().interrupt()
                 }
             }
         }
     }
 
     fun onPause() {
         synchronized(sGLThreadManager) {
             mPaused = true
             (sGLThreadManager as Object).notifyAll()
         }
     }
 
     fun onResume() {
         synchronized(sGLThreadManager) {
             mPaused = false
             mSizeChanged = true // Ensure surface is recreated on resume
             mRequestRender = true
             (sGLThreadManager as Object).notifyAll()
         }
     }
 
     fun onWindowResize(w: Int, h: Int) {
         synchronized(sGLThreadManager) {
             mWidth = w
             mHeight = h
             mSizeChanged = true
             (sGLThreadManager as Object).notifyAll()
         }
     }
 
     fun requestExitAndWait() {
         synchronized(sGLThreadManager) {
             mDone = true
             (sGLThreadManager as Object).notifyAll()
         }
         try {
             join()
         } catch (ex: InterruptedException) {
             Thread.currentThread().interrupt()
         }
     }
 
     fun queueEvent(r: Runnable) {
         synchronized(this) {
             mEventQueue.add(r)
             synchronized(sGLThreadManager) {
                 mEventsWaiting = true
                 (sGLThreadManager as Object).notifyAll()
             }
         }
     }
 
     private fun getEvent(): Runnable? {
         synchronized(this) {
             return if (mEventQueue.isNotEmpty()) {
                 mEventQueue.removeAt(0)
             } else {
                 null
             }
         }
     }
 
     private inner class GLThreadManager {
         @Synchronized
         fun threadExiting(thread: GLThread) {
             thread.mDone = true
             if (mEglOwner == thread) {
                 mEglOwner = null
             }
             (this as Object).notifyAll()
         }
 
         @Synchronized
         fun tryAcquireEglSurface(thread: GLThread): Boolean {
             if (mEglOwner == thread || mEglOwner == null) {
                 mEglOwner = thread
                 (this as Object).notifyAll()
                 return true
             }
             return false
         }
 
         @Synchronized
         fun releaseEglSurface(thread: GLThread) {
             if (mEglOwner == thread) {
                 mEglOwner = null
             }
             (this as Object).notifyAll()
         }
     }
 }