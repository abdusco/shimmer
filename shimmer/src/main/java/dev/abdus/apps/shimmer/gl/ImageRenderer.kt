package dev.abdus.apps.shimmer

import android.graphics.Color
import android.opengl.GLES30
import dev.abdus.apps.shimmer.gl.PictureHandles
import dev.abdus.apps.shimmer.gl.QuadMesh
import dev.abdus.apps.shimmer.gl.TextureArray
import kotlin.math.ceil
import kotlin.math.floor

class ImageRenderer {
    private val textures = TextureArray()
    private var loadedHash = 0

    val aspectRatio: Float get() = textures.aspectRatio
    val isEmpty: Boolean get() = textures.isEmpty

    fun load(imageSet: ImageSet) {
        val newHash = imageSet.hashCode()
        if (loadedHash == newHash && !textures.isEmpty) return

        if (imageSet.original.isRecycled) return

        val bitmaps = listOf(imageSet.original) + imageSet.blurred
        val newAspect = imageSet.width.toFloat() / imageSet.height

        val needsRealloc = textures.isEmpty ||
                kotlin.math.abs(textures.aspectRatio - newAspect) >= 0.001f ||
                textures.size != bitmaps.size

        if (needsRealloc) {
            textures.allocate(bitmaps)
        } else {
            textures.upload(bitmaps)
        }

        loadedHash = newHash
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
        touchPoints: FloatArray,
        touchIntensities: FloatArray,
        aspectRatio: Float,
        timeSeconds: Float,
    ) {
        if (textures.isEmpty || alpha <= 0f) return

        val keyframes = (textures.size - 1).coerceAtLeast(0)
        val progress = blurPercent * keyframes
        val lo = floor(progress).toInt().coerceIn(0, keyframes)
        val hi = ceil(progress).toInt().coerceIn(0, keyframes)
        val mix = progress - lo

        GLES30.glUseProgram(handles.program)
        setUniforms(handles, mvpMatrix, duotone, dimAmount, grain, grainCounts,
                    touchPoints, touchIntensities, aspectRatio, timeSeconds, mix, alpha)

        textures.bind(lo, 0)
        GLES30.glUniform1i(handles.uniformTexture0, 0)

        textures.bind(hi, 1)
        GLES30.glUniform1i(handles.uniformTexture1, 1)

        QuadMesh.draw(handles.attribPosition, handles.attribTexCoords)
    }

    private fun setUniforms(
        h: PictureHandles,
        mvp: FloatArray,
        duotone: Duotone,
        dim: Float,
        grain: GrainSettings,
        grainCounts: Pair<Float, Float>,
        touchPoints: FloatArray,
        touchIntensities: FloatArray,
        aspectRatio: Float,
        timeSeconds: Float,
        blurMix: Float,
        alpha: Float
    ) {
        GLES30.glUniformMatrix4fv(h.uniformMvpMatrix, 1, false, mvp, 0)
        GLES30.glUniform3f(h.uniformDuotoneLight,
                           Color.red(duotone.lightColor)/255f,
                           Color.green(duotone.lightColor)/255f,
                           Color.blue(duotone.lightColor)/255f)
        GLES30.glUniform3f(h.uniformDuotoneDark,
                           Color.red(duotone.darkColor)/255f,
                           Color.green(duotone.darkColor)/255f,
                           Color.blue(duotone.darkColor)/255f)
        GLES30.glUniform1f(h.uniformDuotoneOpacity, duotone.opacity)
        GLES30.glUniform1i(h.uniformDuotoneBlendMode, duotone.blendMode.ordinal)
        GLES30.glUniform1f(h.uniformDimAmount, dim)
        GLES30.glUniform1f(h.uniformGrainAmount, if (grain.enabled) grain.amount else 0f)
        GLES30.glUniform2f(h.uniformGrainCount, grainCounts.first, grainCounts.second)
        GLES30.glUniform1i(h.uniformTouchPointCount, touchIntensities.size)
        GLES30.glUniform3fv(h.uniformTouchPoints, touchIntensities.size, touchPoints, 0)
        GLES30.glUniform1fv(h.uniformTouchIntensities, touchIntensities.size, touchIntensities, 0)
        GLES30.glUniform1f(h.uniformAspectRatio, aspectRatio)
        GLES30.glUniform1f(h.uniformTime, timeSeconds)
        GLES30.glUniform1f(h.uniformBlurMix, blurMix)
        GLES30.glUniform1f(h.uniformAlpha, alpha)
    }

    fun release() = textures.release()
}
