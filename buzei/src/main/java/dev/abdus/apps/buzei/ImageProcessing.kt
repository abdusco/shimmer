package dev.abdus.apps.buzei

import android.graphics.Bitmap

data class ProcessedImage(
    val blurred: Bitmap
)

/**
 * Process image for renderer. Duotone is now applied via GPU shader.
 * This only handles blur processing.
 */
fun processImageForRenderer(
    source: Bitmap,
    blurFraction: Float,
    blurRadiusPixels: Float = 150f
): ProcessedImage {
    val normalizedBlur = blurFraction.coerceIn(0f, 1f)
    val radius = normalizedBlur * blurRadiusPixels
    val blurred = if (radius <= 0f) {
        source
    } else {
        source.blur(radius) ?: source
    }
    return ProcessedImage(blurred)
}
