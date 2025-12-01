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

    fun drawFrame(mvpMatrix: FloatArray, blurFrame: Float, globalAlpha: Float = 1f) {
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
                pictures[lo]?.draw(mvpMatrix, globalAlpha)
            }

            globalAlpha == 1f -> {
                pictures[lo]?.draw(mvpMatrix, 1f - localHiAlpha)
                pictures[hi]?.draw(mvpMatrix, localHiAlpha)
            }

            else -> {
                val loPicture = pictures[lo] ?: return
                val hiPicture = pictures[hi] ?: return
                val newLocalLoAlpha =
                    globalAlpha * (localHiAlpha - 1) / (globalAlpha * localHiAlpha - 1)
                val newLocalHiAlpha = globalAlpha * localHiAlpha
                loPicture.draw(mvpMatrix, newLocalLoAlpha)
                hiPicture.draw(mvpMatrix, newLocalHiAlpha)
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
