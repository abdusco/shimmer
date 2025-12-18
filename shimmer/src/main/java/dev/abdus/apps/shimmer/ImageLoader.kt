package dev.abdus.apps.shimmer

import android.content.ContentResolver
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log

/**
 * Handles loading and preparing images for the wallpaper renderer.
 * Manages bitmap decoding, downsampling, and blur level generation.
 */
class ImageLoader(
    private val contentResolver: ContentResolver,
    private val resources: Resources,
    private val preferences: WallpaperPreferences
) {
    companion object {
        private const val TAG = "ImageLoader"
        private const val DEFAULT_SCREEN_HEIGHT = 1920
        private const val DOWNSAMPLE_MARGIN_OF_ERROR = 0.50f
    }

    private var screenHeight: Int = 0

    /**
     * Updates the target screen height for optimal image downsampling.
     */
    fun setScreenHeight(height: Int) {
        screenHeight = height
    }

    /**
     * Loads an image from a URI and prepares it with blur levels.
     * @param uri The URI of the image to load
     * @param overrideBlurAmount Optional override for blur amount (0..1). If null, uses preferences.
     * @return ImageSet with original and blurred bitmaps, or null if loading fails
     */
    fun loadFromUri(uri: Uri, blurAmount: Float): ImageSet? {
        val bitmap = decodeBitmapFromUri(uri) ?: return null
        return prepareImageSet(bitmap, blurAmount, id = uri.toString())
    }

    /**
     * Loads the default wallpaper image.
     * @param overrideBlurAmount Optional override for blur amount (0..1). If null, uses preferences.
     * @return ImageSet with original and blurred bitmaps, or null if loading fails
     */
    fun loadDefault(blurAmount: Float): ImageSet? {
        try {
            val targetHeight = if (screenHeight > 0) screenHeight else DEFAULT_SCREEN_HEIGHT

            // First pass: decode bounds only
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeResource(resources, R.drawable.default_wallpaper, boundsOptions)

            // Calculate optimal sample size
            val sampleSize = calculateInSampleSize(boundsOptions.outHeight, targetHeight)

            // Second pass: decode actual bitmap
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            Log.d(
                TAG, "loadDefault: Decoding default image: ${boundsOptions.outWidth}x${boundsOptions.outHeight} -> " +
                        "inSampleSize=$sampleSize (target height=$targetHeight)"
            )
            val bitmap = BitmapFactory.decodeResource(resources, R.drawable.default_wallpaper, options)

            return prepareImageSet(bitmap, blurAmount, id="default")
        } catch (e: Exception) {
            Log.e(TAG, "loadDefault: Error loading default image: ${e.message}", e)
            return null
        }
    }

    /**
     * Loads the last viewed image from preferences.
     * @param overrideBlurAmount Optional override for blur amount (0..1). If null, uses preferences.
     * @return ImageSet if successful, null if no last image or loading fails
     */
    fun loadLast(blurAmount: Float): ImageSet? {
        Log.d(TAG, "loadLast: Attempting to load last image URI from preferences")
        val lastImageUri = preferences.getLastImageUri()
        
        if (lastImageUri == null) {
            Log.d(TAG, "loadLast: No last image URI in preferences")
            return null
        }

        return try {
            val imageSet = loadFromUri(lastImageUri, blurAmount)
            if (imageSet == null) {
                Log.w(TAG, "loadLast: Failed to load, clearing invalid URI")
                preferences.setLastImageUri(null) // Clear invalid URI
            }
            imageSet
        } catch (e: Exception) {
            Log.e(TAG, "loadLast: Error loading last image URI: $lastImageUri", e)
            preferences.setLastImageUri(null) // Clear invalid URI
            null
        }
    }

    /**
     * Prepares an ImageSet with blur levels from a bitmap.
     */
    private fun prepareImageSet(bitmap: Bitmap, blurAmount: Float, id: String): ImageSet {
        val maxRadius = blurAmount * MAX_SUPPORTED_BLUR_RADIUS_PIXELS
        Log.d(TAG, "prepareImageSet: Generating blur levels with maxRadius=$maxRadius")
        val blurResult = bitmap.generateBlurLevels(maxRadius)
        
        return ImageSet(
            id = id,
            original = bitmap,
            blurred = blurResult.bitmaps,
            blurRadii = blurResult.radii,
        )
    }

    /**
     * Decodes a bitmap from a URI with optimal downsampling.
     */
    private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val targetHeight = if (screenHeight > 0) screenHeight else DEFAULT_SCREEN_HEIGHT

            // First pass: decode bounds only
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }

            // Calculate optimal sample size
            val sampleSize = calculateInSampleSize(boundsOptions.outHeight, targetHeight)

            // Second pass: decode actual bitmap with calculated sample size
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                Log.d(
                    TAG, "decodeBitmapFromUri: Decoding image: ${boundsOptions.outWidth}x${boundsOptions.outHeight} -> " +
                            "inSampleSize=$sampleSize (target height=$targetHeight)"
                )
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeBitmapFromUri: Error decoding bitmap", e)
            null
        }
    }

    /**
     * Calculate optimal inSampleSize to downsample image to fit target height.
     * inSampleSize will be a power of 2, ensuring efficient decoding.
     * Allows configurable margin of error to pick appropriate sample size.
     */
    private fun calculateInSampleSize(imageHeight: Int, targetHeight: Int): Int {
        var inSampleSize = 1
        if (imageHeight > targetHeight) {
            val halfHeight = imageHeight / 2
            val threshold = kotlin.math.max(1, (targetHeight * (1 - DOWNSAMPLE_MARGIN_OF_ERROR)).toInt())
            // Calculate the largest inSampleSize (power of two) whose resulting height
            // is >= threshold. Threshold is targetHeight reduced by margin of error.
            while (halfHeight / inSampleSize >= threshold) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
