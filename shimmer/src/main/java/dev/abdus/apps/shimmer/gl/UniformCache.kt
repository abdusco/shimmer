package dev.abdus.apps.shimmer.gl

import android.opengl.GLES30

/**
 * Skips glUniform* calls whose value hasn't changed since the last draw. Most uniforms
 * (duotone colors, grain, aspect ratio) are constant across long stretches of frames while
 * the loop redraws continuously for an animation.
 *
 * Uniform state belongs to the program, so one cache is shared by every renderer drawing
 * with it, and a new one must accompany a relinked program.
 */
class UniformCache(val handles: ShaderHandles) {
    private val mvp = FloatArray(16)
    private var mvpValid = false
    private var duotoneLightColor = 0
    private var duotoneDarkColor = 0
    private var duotoneColorsValid = false
    private var duotoneOpacity = Float.NaN
    private var duotoneBlendMode = -1
    private var dimAmount = Float.NaN
    private var grainAmount = Float.NaN
    private var grainCountX = Float.NaN
    private var grainCountY = Float.NaN
    private var aspectRatio = Float.NaN
    private var time = Float.NaN
    private var blurMix = Float.NaN
    private var alpha = Float.NaN
    private var touchPointCount = 0

    fun setMvpMatrix(value: FloatArray) {
        if (mvpValid && value.contentEquals(mvp)) return
        GLES30.glUniformMatrix4fv(handles.uniformMvpMatrix, 1, false, value, 0)
        value.copyInto(mvp)
        mvpValid = true
    }

    fun setDuotoneColors(lightColor: Int, darkColor: Int) {
        if (duotoneColorsValid && lightColor == duotoneLightColor && darkColor == duotoneDarkColor) {
            return
        }
        GLES30.glUniform3f(handles.uniformDuotoneLight,
                           ((lightColor shr 16) and 0xFF) / 255f,
                           ((lightColor shr 8) and 0xFF) / 255f,
                           (lightColor and 0xFF) / 255f)
        GLES30.glUniform3f(handles.uniformDuotoneDark,
                           ((darkColor shr 16) and 0xFF) / 255f,
                           ((darkColor shr 8) and 0xFF) / 255f,
                           (darkColor and 0xFF) / 255f)
        duotoneLightColor = lightColor
        duotoneDarkColor = darkColor
        duotoneColorsValid = true
    }

    fun setDuotoneOpacity(value: Float) {
        if (value == duotoneOpacity) return
        GLES30.glUniform1f(handles.uniformDuotoneOpacity, value)
        duotoneOpacity = value
    }

    fun setDuotoneBlendMode(value: Int) {
        if (value == duotoneBlendMode) return
        GLES30.glUniform1i(handles.uniformDuotoneBlendMode, value)
        duotoneBlendMode = value
    }

    fun setDimAmount(value: Float) {
        if (value == dimAmount) return
        GLES30.glUniform1f(handles.uniformDimAmount, value)
        dimAmount = value
    }

    fun setGrain(amount: Float, countX: Float, countY: Float) {
        if (amount != grainAmount) {
            GLES30.glUniform1f(handles.uniformGrainAmount, amount)
            grainAmount = amount
        }
        if (countX != grainCountX || countY != grainCountY) {
            GLES30.glUniform2f(handles.uniformGrainCount, countX, countY)
            grainCountX = countX
            grainCountY = countY
        }
    }

    fun setAspectRatio(value: Float) {
        if (value == aspectRatio) return
        GLES30.glUniform1f(handles.uniformAspectRatio, value)
        aspectRatio = value
    }

    fun setTime(value: Float) {
        if (value == time) return
        GLES30.glUniform1f(handles.uniformTime, value)
        time = value
    }

    fun setBlurMix(value: Float) {
        if (value == blurMix) return
        GLES30.glUniform1f(handles.uniformBlurMix, value)
        blurMix = value
    }

    fun setAlpha(value: Float) {
        if (value == alpha) return
        GLES30.glUniform1f(handles.uniformAlpha, value)
        alpha = value
    }

    /**
     * Array contents change on every frame a touch is active, so they are uploaded
     * unconditionally; the skip that matters is the resting case of no touches at all.
     */
    fun setTouchPoints(points: FloatArray, intensities: FloatArray, count: Int) {
        if (count == 0 && touchPointCount == 0) return

        GLES30.glUniform1i(handles.uniformTouchPointCount, count)
        if (count > 0) {
            GLES30.glUniform3fv(handles.uniformTouchPoints, count, points, 0)
            GLES30.glUniform1fv(handles.uniformTouchIntensities, count, intensities, 0)
        }
        touchPointCount = count
    }
}
