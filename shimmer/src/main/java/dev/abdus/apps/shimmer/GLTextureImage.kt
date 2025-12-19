package dev.abdus.apps.shimmer

import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor

class GLTextureImage {
    private var textures = IntArray(0)
    private val textureCoordsBuffer: FloatBuffer = GLUtil.asFloatBuffer(floatArrayOf(
        0f, 0f, 0f, 1f, 1f, 1f,
        0f, 0f, 1f, 1f, 1f, 0f
    ))
    private val vertexBuffer: FloatBuffer = GLUtil.newFloatBuffer(18)
    private var loadedImageSetHash: Int = 0

    fun load(imageSet: ImageSet) {
        val newHash = imageSet.hashCode()
        // If textures exist and it's the same image, just bail out!
        if (textures.isNotEmpty() && loadedImageSetHash == newHash) {
            return 
        }

        release()

        if (imageSet.original.isRecycled) return

        val bitmaps: List<android.graphics.Bitmap> = listOf(imageSet.original) + imageSet.blurred
        textures = IntArray(bitmaps.size)
        GLES30.glGenTextures(textures.size, textures, 0)

        for (i in bitmaps.indices) {
            val bitmap = bitmaps[i]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
            
            // OPTIMIZATION: Use immutable texture storage for better driver optimization
            // Allocate GPU memory once with immutable format
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, bitmap.width, bitmap.height)
            
            // Upload bitmap data using GLUtils.texSubImage2D (handles ARGB->RGBA conversion natively)
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            
            // Set texture parameters AFTER allocating storage
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        // Handshake: Ensure GPU has read pixels, but we NO LONGER recycle here.
        GLES30.glFlush()
        GLES30.glFinish()

        loadedImageSetHash = newHash
    }

    fun draw(
        handles: PictureHandles, mvpMatrix: FloatArray, blurPercent: Float, alpha: Float,
        duotone: Duotone, dimAmount: Float, grain: GrainSettings, grainCounts: Pair<Float, Float>,
        touchPoints: FloatArray, touchIntensities: FloatArray, screenSize: FloatArray
    ) {
        val touchCount = touchIntensities.size
        if (textures.isEmpty() || alpha <= 0f) return

        val keyframes = (textures.size - 1).coerceAtLeast(0)
        val progress = blurPercent * keyframes
        val lo = floor(progress).toInt().coerceIn(0, keyframes)
        val hi = ceil(progress).toInt().coerceIn(0, keyframes)
        val mix = progress - lo

        GLES30.glUseProgram(handles.program)
        GLES30.glUniformMatrix4fv(handles.uniformMvpMatrix, 1, false, mvpMatrix, 0)
        GLES30.glUniform3f(handles.uniformDuotoneLight, Color.red(duotone.lightColor)/255f, Color.green(duotone.lightColor)/255f, Color.blue(duotone.lightColor)/255f)
        GLES30.glUniform3f(handles.uniformDuotoneDark, Color.red(duotone.darkColor)/255f, Color.green(duotone.darkColor)/255f, Color.blue(duotone.darkColor)/255f)
        GLES30.glUniform1f(handles.uniformDuotoneOpacity, duotone.opacity)
        GLES30.glUniform1i(handles.uniformDuotoneBlendMode, duotone.blendMode.ordinal)
        GLES30.glUniform1f(handles.uniformDimAmount, dimAmount)
        GLES30.glUniform1f(handles.uniformGrainAmount, if (grain.enabled) grain.amount * 0.30f else 0f)
        GLES30.glUniform2f(handles.uniformGrainCount, grainCounts.first, grainCounts.second)
        GLES30.glUniform1i(handles.uniformTouchPointCount, touchCount)
        GLES30.glUniform3fv(handles.uniformTouchPoints, touchCount, touchPoints, 0)
        GLES30.glUniform1fv(handles.uniformTouchIntensities, touchCount, touchIntensities, 0)
        GLES30.glUniform2f(handles.uniformScreenSize, screenSize[0], screenSize[1])

        GLES30.glEnableVertexAttribArray(handles.attribPosition)
        GLES30.glEnableVertexAttribArray(handles.attribTexCoords)

        val v = floatArrayOf(-1f,1f,0f, -1f,-1f,0f, 1f,-1f,0f, -1f,1f,0f, 1f,-1f,0f, 1f,1f,0f)
        vertexBuffer.put(v).position(0)
        GLES30.glVertexAttribPointer(handles.attribPosition, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glVertexAttribPointer(handles.attribTexCoords, 2, GLES30.GL_FLOAT, false, 0, textureCoordsBuffer)

        // OPTIMIZATION: Bind BOTH textures simultaneously
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[lo])
        GLES30.glUniform1i(handles.uniformTexture0, 0) // Tell shader Texture0 is at Unit 0

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[hi])
        GLES30.glUniform1i(handles.uniformTexture1, 1) // Tell shader Texture1 is at Unit 1

        // Send the blend factor (0.0 = all lo, 1.0 = all hi)
        GLES30.glUniform1f(handles.uniformBlurMix, mix)
        GLES30.glUniform1f(handles.uniformAlpha, alpha)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // SINGLE DRAW CALL
        // No loops, no double blending, no double vertex processing
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    fun release() {
        if (textures.isNotEmpty()) {
            GLES30.glDeleteTextures(textures.size, textures, 0)
            textures = IntArray(0)
        }
    }
}