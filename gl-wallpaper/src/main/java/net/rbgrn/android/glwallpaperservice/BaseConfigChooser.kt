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
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Created by romannurik on 11/6/13.
 */
abstract class BaseConfigChooser(
    configSpec: IntArray,
    private val eglContextClientVersion: Int
) : GLSurfaceView.EGLConfigChooser {

    protected var mConfigSpec: IntArray = filterConfigSpec(configSpec)

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val numConfig = IntArray(1)
        if (!egl.eglChooseConfig(display, mConfigSpec, null, 0, numConfig)) {
            throw IllegalArgumentException("eglChooseConfig failed")
        }

        val numConfigs = numConfig[0]

        if (numConfigs <= 0) {
            throw IllegalArgumentException("No configs match configSpec")
        }

        val configs = arrayOfNulls<EGLConfig>(numConfigs)
        if (!egl.eglChooseConfig(display, mConfigSpec, configs, numConfigs, numConfig)) {
            throw IllegalArgumentException("eglChooseConfig#2 failed")
        }
        val config = chooseConfig(egl, display, configs.filterNotNull().toTypedArray())
        if (config == null) {
            throw IllegalArgumentException("No config chosen")
        }
        return config
    }

    abstract fun chooseConfig(egl: EGL10, display: EGLDisplay, configs: Array<EGLConfig>): EGLConfig?

    private fun filterConfigSpec(configSpec: IntArray): IntArray {
        // Support both GLES 2.0 and 3.0
        if (eglContextClientVersion != 2 && eglContextClientVersion != 3) {
            return configSpec
        }
        /* We know none of the subclasses define EGL_RENDERABLE_TYPE.
         * And we know the configSpec is well formed.
         */
        val len = configSpec.size
        val newConfigSpec = IntArray(len + 2)
        System.arraycopy(configSpec, 0, newConfigSpec, 0, len - 1)
        newConfigSpec[len - 1] = EGL10.EGL_RENDERABLE_TYPE
        // Use GLES 3.0 bit if version is 3, otherwise GLES 2.0 bit
        newConfigSpec[len] = if (eglContextClientVersion == 3) 64 /* EGL_OPENGL_ES3_BIT */ else 4 /* EGL_OPENGL_ES2_BIT */
        newConfigSpec[len + 1] = EGL10.EGL_NONE
        return newConfigSpec
    }

    open class ComponentSizeChooser(
        redSize: Int,
        greenSize: Int,
        blueSize: Int,
        alphaSize: Int,
        depthSize: Int,
        stencilSize: Int,
        eglContextClientVersion: Int
    ) : BaseConfigChooser(
        intArrayOf(
            EGL10.EGL_RED_SIZE, redSize,
            EGL10.EGL_GREEN_SIZE, greenSize,
            EGL10.EGL_BLUE_SIZE, blueSize,
            EGL10.EGL_ALPHA_SIZE, alphaSize,
            EGL10.EGL_DEPTH_SIZE, depthSize,
            EGL10.EGL_STENCIL_SIZE, stencilSize,
            EGL10.EGL_NONE
        ),
        eglContextClientVersion
    ) {
        private val mValue = IntArray(1)
        
        // Subclasses can adjust these values:
        protected var mRedSize: Int = redSize
        protected var mGreenSize: Int = greenSize
        protected var mBlueSize: Int = blueSize
        protected var mAlphaSize: Int = alphaSize
        protected var mDepthSize: Int = depthSize
        protected var mStencilSize: Int = stencilSize

        override fun chooseConfig(egl: EGL10, display: EGLDisplay, configs: Array<EGLConfig>): EGLConfig? {
            var closestConfig: EGLConfig? = null
            var closestDistance = 1000
            for (config in configs) {
                val d = findConfigAttrib(egl, display, config, EGL10.EGL_DEPTH_SIZE)
                val s = findConfigAttrib(egl, display, config, EGL10.EGL_STENCIL_SIZE)
                if (d >= mDepthSize && s >= mStencilSize) {
                    val r = findConfigAttrib(egl, display, config, EGL10.EGL_RED_SIZE)
                    val g = findConfigAttrib(egl, display, config, EGL10.EGL_GREEN_SIZE)
                    val b = findConfigAttrib(egl, display, config, EGL10.EGL_BLUE_SIZE)
                    val a = findConfigAttrib(egl, display, config, EGL10.EGL_ALPHA_SIZE)
                    val distance = kotlin.math.abs(r - mRedSize) +
                            kotlin.math.abs(g - mGreenSize) +
                            kotlin.math.abs(b - mBlueSize) +
                            kotlin.math.abs(a - mAlphaSize)
                    if (distance < closestDistance) {
                        closestDistance = distance
                        closestConfig = config
                    }
                }
            }
            return closestConfig
        }

        private fun findConfigAttrib(egl: EGL10, display: EGLDisplay, config: EGLConfig, attribute: Int): Int {
            return if (egl.eglGetConfigAttrib(display, config, attribute, mValue)) {
                mValue[0]
            } else {
                0
            }
        }
    }

    /**
     * This class will choose a supported surface as close to RGB565 as possible, with or without a depth buffer.
     */
    class SimpleEGLConfigChooser(
        withDepthBuffer: Boolean,
        eglContextClientVersion: Int
    ) : ComponentSizeChooser(
        4, 4, 4, 0,
        if (withDepthBuffer) 16 else 0,
        0,
        eglContextClientVersion
    ) {
        init {
            // Adjust target values. This way we'll accept a 4444 or
            // 555 buffer if there's no 565 buffer available.
            mRedSize = 5
            mGreenSize = 6
            mBlueSize = 5
        }
    }
}

