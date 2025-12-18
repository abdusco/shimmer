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

    fun load(imageSet: ImageSet) {
        release()
        
        // Check if already recycled before attempting upload
        if (imageSet.original.isRecycled) {
            android.util.Log.e("GLTextureImage", "Abort load: Original bitmap already recycled")
            return
        }

        val bitmaps = listOf(imageSet.original) + imageSet.blurred
        textures = IntArray(bitmaps.size)
        GLES30.glGenTextures(textures.size, textures, 0)

        for (i in bitmaps.indices) {
            val bmp = bitmaps[i]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        }

        // SIGSEGV PREVENTION: Flush and Finish to ensure driver has copied pixels
        GLES30.glFlush()
        GLES30.glFinish()

        // DETERMINISTIC RELEASE: Destroy CPU copies immediately
        imageSet.blurred.forEach { it.recycle() }
        imageSet.original.recycle()
    }

    fun draw(
        handles: PictureHandles,
        mvpMatrix: FloatArray,
        blurPercent: Float,
        alpha: Float,
        duotone: Duotone,
        dimAmount: Float,
        grain: GrainSettings,
        grainCounts: Pair<Float, Float>,
        touchData: Triple<Int, FloatArray, FloatArray>,
        screenSize: FloatArray
    ) {
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
        GLES30.glUniform1i(handles.uniformTouchPointCount, touchData.first)
        GLES30.glUniform3fv(handles.uniformTouchPoints, touchData.first, touchData.second, 0)
        GLES30.glUniform1fv(handles.uniformTouchIntensities, touchData.first, touchData.third, 0)
        GLES30.glUniform2f(handles.uniformScreenSize, screenSize[0], screenSize[1])

        GLES30.glEnableVertexAttribArray(handles.attribPosition)
        GLES30.glEnableVertexAttribArray(handles.attribTexCoords)

        val v = floatArrayOf(-1f,1f,0f, -1f,-1f,0f, 1f,-1f,0f, -1f,1f,0f, 1f,-1f,0f, 1f,1f,0f)
        vertexBuffer.put(v).position(0)
        GLES30.glVertexAttribPointer(handles.attribPosition, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glVertexAttribPointer(handles.attribTexCoords, 2, GLES30.GL_FLOAT, false, 0, textureCoordsBuffer)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(handles.uniformTexture, 0)

        // Smoothly interpolate between discrete blur levels
        if (lo == hi) {
            GLES30.glUniform1f(handles.uniformAlpha, alpha)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[lo])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        } else {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            
            // Pass 1: Base blur level
            GLES30.glUniform1f(handles.uniformAlpha, alpha)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[lo])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
            
            // Pass 2: Overlay higher blur level
            GLES30.glUniform1f(handles.uniformAlpha, alpha * mix)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[hi])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        }
    }

    fun release() {
        if (textures.isNotEmpty()) {
            GLES30.glDeleteTextures(textures.size, textures, 0)
            textures = IntArray(0)
        }
    }
}