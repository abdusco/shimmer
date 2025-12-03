package dev.abdus.apps.buzei

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.floor

class GLPictureSet(private val blurKeyframes: Int) {

    private val pictures = arrayOfNulls<GLPicture>(blurKeyframes + 1)

    fun load(bitmaps: List<Bitmap?>) {
        destroyPictures()
        for (index in pictures.indices) {
            pictures[index] = bitmaps.getOrNull(index)?.let { GLPicture(it) }
        }
    }

    fun drawFrame(mvpMatrix: FloatArray, blurFrame: Float, globalAlpha: Float = 1f,
                  duotoneEnabled: Boolean = false, duotoneLightColor: Int = 0xFFFFFFFF.toInt(),
                  duotoneDarkColor: Int = 0xFF000000.toInt()) {
        if (pictures.all { it == null }) {
            return
        }

        val clampedBlur = blurFrame.coerceIn(0f, blurKeyframes.toFloat())
        val lo = floor(clampedBlur.toDouble()).toInt().coerceAtMost(blurKeyframes)
        val hi = ceil(clampedBlur.toDouble()).toInt().coerceAtMost(blurKeyframes)
        val localHiAlpha = clampedBlur - lo

        when {
            globalAlpha <= 0f -> return
            lo == hi -> {
                pictures[lo]?.draw(mvpMatrix, globalAlpha, duotoneEnabled, duotoneLightColor, duotoneDarkColor)
            }

            globalAlpha == 1f -> {
                pictures[lo]?.draw(mvpMatrix, 1f - localHiAlpha, duotoneEnabled, duotoneLightColor, duotoneDarkColor)
                pictures[hi]?.draw(mvpMatrix, localHiAlpha, duotoneEnabled, duotoneLightColor, duotoneDarkColor)
            }

            else -> {
                val loPicture = pictures[lo] ?: return
                val hiPicture = pictures[hi] ?: return
                val newLocalLoAlpha =
                    globalAlpha * (localHiAlpha - 1) / (globalAlpha * localHiAlpha - 1)
                val newLocalHiAlpha = globalAlpha * localHiAlpha
                loPicture.draw(mvpMatrix, newLocalLoAlpha, duotoneEnabled, duotoneLightColor, duotoneDarkColor)
                hiPicture.draw(mvpMatrix, newLocalHiAlpha, duotoneEnabled, duotoneLightColor, duotoneDarkColor)
            }
        }
    }

    fun destroyPictures() {
        for (i in pictures.indices) {
            pictures[i]?.destroy()
            pictures[i] = null
        }
    }
}
