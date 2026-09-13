package dev.abdus.apps.shimmer

import android.opengl.GLES30
import dev.abdus.apps.shimmer.gl.QuadMesh
import dev.abdus.apps.shimmer.gl.TextureArray
import dev.abdus.apps.shimmer.gl.UniformCache
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
        uniforms: UniformCache,
        quad: QuadMesh,
        mvpMatrix: FloatArray,
        blurPercent: Float,
        alpha: Float,
        duotoneLightColor: Int,
        duotoneDarkColor: Int,
        duotoneOpacity: Float,
        duotoneBlendMode: Int,
        dimAmount: Float,
        grainAmount: Float,
        grainCountX: Float,
        grainCountY: Float,
        touchPoints: FloatArray,
        touchIntensities: FloatArray,
        touchPointCount: Int,
        aspectRatio: Float,
        timeSeconds: Float,
    ) {
        if (textures.isEmpty || alpha <= 0f) return

        val keyframes = (textures.size - 1).coerceAtLeast(0)
        val progress = blurPercent * keyframes
        val lo = floor(progress).toInt().coerceIn(0, keyframes)
        val hi = ceil(progress).toInt().coerceIn(0, keyframes)
        val mix = progress - lo

        GLES30.glUseProgram(uniforms.handles.program)
        setUniforms(uniforms, mvpMatrix, duotoneLightColor, duotoneDarkColor, duotoneOpacity,
                    duotoneBlendMode, dimAmount, grainAmount, grainCountX, grainCountY,
                    touchPoints, touchIntensities, touchPointCount, aspectRatio, timeSeconds,
                    mix, alpha)

        textures.bind(lo, 0)
        textures.bind(hi, 1)

        quad.draw()
    }

    private fun setUniforms(
        u: UniformCache,
        mvp: FloatArray,
        duotoneLightColor: Int,
        duotoneDarkColor: Int,
        duotoneOpacity: Float,
        duotoneBlendMode: Int,
        dim: Float,
        grainAmount: Float,
        grainCountX: Float,
        grainCountY: Float,
        touchPoints: FloatArray,
        touchIntensities: FloatArray,
        touchPointCount: Int,
        aspectRatio: Float,
        timeSeconds: Float,
        blurMix: Float,
        alpha: Float
    ) {
        u.setMvpMatrix(mvp)
        u.setDuotoneColors(duotoneLightColor, duotoneDarkColor)
        u.setDuotoneOpacity(duotoneOpacity)
        u.setDuotoneBlendMode(duotoneBlendMode)
        u.setDimAmount(dim)
        u.setGrain(grainAmount, grainCountX, grainCountY)
        u.setTouchPoints(touchPoints, touchIntensities, touchPointCount)
        u.setAspectRatio(aspectRatio)
        u.setTime(timeSeconds)
        u.setBlurMix(blurMix)
        u.setAlpha(alpha)
    }

    fun release() = textures.release()
}
