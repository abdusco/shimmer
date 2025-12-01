package dev.abdus.apps.buzei

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

data class ProcessedImage(
    val blurred: Bitmap,
    val tintedOriginal: Bitmap?
)

data class RendererImageSettings(
    val blurAmount: Float,
    val duotoneEnabled: Boolean,
    val duotoneAlwaysOn: Boolean,
    val duotoneLightColor: Int,
    val duotoneDarkColor: Int
) {
    fun matches(
        blurAmount: Float,
        duotoneEnabled: Boolean,
        duotoneAlwaysOn: Boolean,
        duotoneLightColor: Int,
        duotoneDarkColor: Int
    ): Boolean {
        return this.blurAmount == blurAmount &&
            this.duotoneEnabled == duotoneEnabled &&
            this.duotoneAlwaysOn == duotoneAlwaysOn &&
            this.duotoneLightColor == duotoneLightColor &&
            this.duotoneDarkColor == duotoneDarkColor
    }
}

fun processImageForRenderer(
    source: Bitmap,
    blurFraction: Float,
    settings: RendererImageSettings,
    blurRadiusPixels: Float = 150f
): ProcessedImage {
    val normalizedBlur = blurFraction.coerceIn(0f, 1f)
    val radius = normalizedBlur * blurRadiusPixels
    val blurredSource = if (radius <= 0f) {
        source
    } else {
        source.blur(radius) ?: source
    }
    val applyBlurDuotone = settings.duotoneEnabled
    val blurred = if (applyBlurDuotone) {
        blurredSource.applyDuotone(settings.duotoneLightColor, settings.duotoneDarkColor)
    } else {
        blurredSource
    }
    val tinted = if (settings.duotoneEnabled && settings.duotoneAlwaysOn) {
        source.applyDuotone(settings.duotoneLightColor, settings.duotoneDarkColor)
    } else {
        null
    }
    return ProcessedImage(blurred, tinted)
}

fun Bitmap.applyDuotone(lightColor: Int, darkColor: Int): Bitmap {
    val target = copy(Bitmap.Config.ARGB_8888, true)
    val pixelCount = width * height
    if (pixelCount == 0) {
        return target
    }
    val pixels = IntArray(pixelCount)
    target.getPixels(pixels, 0, width, 0, 0, width, height)
    val lightR = Color.red(lightColor)
    val lightG = Color.green(lightColor)
    val lightB = Color.blue(lightColor)
    val darkR = Color.red(darkColor)
    val darkG = Color.green(darkColor)
    val darkB = Color.blue(darkColor)
    for (index in pixels.indices) {
        val source = pixels[index]
        val lum = (0.2126f * Color.red(source) +
                0.7152f * Color.green(source) +
                0.0722f * Color.blue(source)) / 255f
        val outR = (darkR + (lightR - darkR) * lum).roundToInt().coerceIn(0, 255)
        val outG = (darkG + (lightG - darkG) * lum).roundToInt().coerceIn(0, 255)
        val outB = (darkB + (lightB - darkB) * lum).roundToInt().coerceIn(0, 255)
        pixels[index] = Color.argb(Color.alpha(source), outR, outG, outB)
    }
    target.setPixels(pixels, 0, width, 0, 0, width, height)
    return target
}
