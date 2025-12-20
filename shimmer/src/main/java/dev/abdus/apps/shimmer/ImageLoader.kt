package dev.abdus.apps.shimmer

import android.content.ContentResolver
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import dev.abdus.apps.shimmer.gl.MAX_SUPPORTED_BLUR_RADIUS_PIXELS
import dev.abdus.apps.shimmer.gl.generateBlurLevels
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

class ImageLoader(
    private val contentResolver: ContentResolver,
    private val resources: Resources,
) {
    companion object {
        private const val TAG = "ImageLoader"
        private const val DEFAULT_SCREEN_HEIGHT = 1920

        // How much smaller than the target can the image be?
        // 0.8 means we allow up to 20% smaller than screen height before refusing to downscale further.
        private const val DOWNSAMPLE_THRESHOLD = 0.8f
    }

    private var screenHeight: Int = 0

    /**
     * Called by the Engine when the surface size is known.
     */
    fun setScreenHeight(height: Int) {
        screenHeight = height
    }

    /**
     * Loads an image from a URI with optimal downsampling.
     */
    suspend fun loadFromUri(uri: Uri, blurAmount: Float): ImageSet? {
        val targetHeight = if (screenHeight > 0) screenHeight else DEFAULT_SCREEN_HEIGHT
        val bitmap = try {
            decodeSampledBitmapFromUri(uri, targetHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load URI: $uri", e)
            null
        } ?: return null

        return prepareImageSet(bitmap, blurAmount, id = uri.toString())
    }

    /**
     * Loads the default wallpaper with optimal downsampling.
     */
    suspend fun loadDefault(blurAmount: Float): ImageSet? {
        val targetHeight = if (screenHeight > 0) screenHeight else DEFAULT_SCREEN_HEIGHT
        val bitmap = try {
            decodeSampledBitmapFromResource(R.drawable.default_wallpaper, targetHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load default wallpaper", e)
            null
        } ?: return null

        return prepareImageSet(bitmap, blurAmount, id = "default")
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, reqHeight: Int): Bitmap? {
        // Pass 1: Decode with inJustDecodeBounds=true to check dimensions
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        // Calculate inSampleSize (Power of 2)
        options.inSampleSize = calculateInSampleSize(options.outHeight, reqHeight)
        options.inJustDecodeBounds = false

        Log.d(TAG, "Decoding URI $uri: original_h=${options.outHeight}, target_h=$reqHeight, sampleSize=${options.inSampleSize}")

        // Pass 2: Decode actual bitmap
        return contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    private fun decodeSampledBitmapFromResource(resId: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeResource(resources, resId, options)

        options.inSampleSize = calculateInSampleSize(options.outHeight, reqHeight)
        options.inJustDecodeBounds = false

        Log.d(TAG, "Decoding Res $resId: original_h=${options.outHeight}, target_h=$reqHeight, sampleSize=${options.inSampleSize}")

        return BitmapFactory.decodeResource(resources, resId, options)
    }

    /**
     * Standard Android calculation for inSampleSize.
     * reqHeight is the target screen height.
     */
    private fun calculateInSampleSize(height: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight) {
            val halfHeight = height / 2
            // Keep doubling the sample size until the resulting height would be
            // smaller than our threshold.
            while ((halfHeight / inSampleSize) >= (reqHeight * DOWNSAMPLE_THRESHOLD)) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private suspend fun prepareImageSet(bitmap: Bitmap, blurAmount: Float, id: String): ImageSet {
        val maxRadius = ceil(blurAmount * MAX_SUPPORTED_BLUR_RADIUS_PIXELS)

        // Use currentCoroutineContext() from the kotlinx.coroutines package
        val context = currentCoroutineContext()
        val blurResult = bitmap.generateBlurLevels(maxRadius) {
            context.ensureActive()
        }

        return ImageSet(
            id = id,
            original = bitmap,
            blurred = blurResult.bitmaps,
            blurRadii = blurResult.radii,
            width = bitmap.width,
            height = bitmap.height
        )
    }
}