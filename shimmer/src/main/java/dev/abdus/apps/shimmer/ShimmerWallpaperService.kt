package dev.abdus.apps.shimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.core.net.toUri
import net.rbgrn.android.glwallpaperservice.GLWallpaperService
import java.util.concurrent.Executors

class ShimmerWallpaperService : GLWallpaperService() {
    companion object {
        const val TAG = "ShimmerWallpaperService"
    }

    override fun onCreateEngine(): Engine {
        return ShimmerWallpaperEngine()
    }

    inner class ShimmerWallpaperEngine : GLEngine(), ShimmerRenderer.Callbacks {

        private var renderer: ShimmerRenderer? = null
        private var surfaceAvailable = false
        private var engineVisible = true
        private val imageLoadExecutor = Executors.newSingleThreadScheduledExecutor()
        private val folderRepository = ImageFolderRepository(this@ShimmerWallpaperService)
        private val transitionScheduler =
            ImageTransitionScheduler(imageLoadExecutor) { handleScheduledAdvance() }

        private val tapGestureDetector = TapGestureDetector(this@ShimmerWallpaperService)
        private val preferences = WallpaperPreferences.create(this@ShimmerWallpaperService)

        // Queue for actions that need to be run on the GL thread when the renderer is available
        // This list will hold the actual actions, not just their lambdas.
        private val deferredRendererActions = mutableListOf<(ShimmerRenderer) -> Unit>()

        // Image change state management (accessed only on folderScheduler thread)
        private var pendingImageUri: Uri? = null

        // Track current image URI for re-blurring when blur amount changes
        private var currentImageUri: Uri? = null

        // Track initial load to prevent double-blur
        private var isInitialLoad = true

        // Screen dimensions for optimal image downsampling
        private var screenHeight: Int = 0

        // allow up to 50% smaller than target height
        private val DOWNSAMPLE_MARGIN_OF_ERROR = 0.50f

        /**
         * Calculate optimal inSampleSize to downsample image to fit target height.
         * inSampleSize will be a power of 2, ensuring efficient decoding.
         * This version allows a configurable leeway (e.g. 30%) so we still pick
         * an inSampleSize of 2 for images that are only slightly smaller than twice
         * the target height.
         */
        private fun calculateInSampleSize(imageHeight: Int, targetHeight: Int): Int {
            var inSampleSize = 1
            if (imageHeight > targetHeight) {
                val halfHeight = imageHeight / 2
                val threshold = kotlin.math.max(1, (targetHeight * (1 - DOWNSAMPLE_MARGIN_OF_ERROR)).toInt())
                // Calculate the largest inSampleSize (power of two) whose resulting height
                // is >= threshold. Threshold is targetHeight reduced by leeway percent.
                while (halfHeight / inSampleSize >= threshold) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }

        override fun onRendererReady() {
            Log.d(TAG, "onRendererReady called")

            executeDeferredActions()

            applyPreferences()
            isInitialLoad = false
            super.requestRender() // Request render after applying preferences
        }

        override fun onReadyForNextImage() {
            Log.d(TAG, "onReadyForNextImage called on ${Thread.currentThread().name}")
            imageLoadExecutor.execute {
                pendingImageUri?.let { uri ->
                    pendingImageUri = null
                    loadImage(uri)
                }
            }
        }

        override fun onSurfaceDimensionsChanged(width: Int, height: Int) {
            screenHeight = height
            Log.d(TAG, "onSurfaceDimensionsChanged: ${width}x${height}")
        }

        // Helper function to safely execute actions on the renderer on the GL thread
        // Handles renderer nullability, surface availability, and visibility checks
        private fun withRenderer(allowWhenSurfaceUnavailable: Boolean = false, action: (ShimmerRenderer) -> Unit) {
            val r = renderer
            if (r == null) {
                // If renderer is not yet available, defer this action.
                // This typically happens if a preference change occurs before onSurfaceCreated has completed
                // and renderer is assigned.
                Log.d(TAG, "withRenderer: Renderer not available, deferring action")
                deferredRendererActions.add(action)
                return
            }

            // If surface is unavailable, defer the action regardless of allowWhenSurfaceUnavailable
            // We can't execute GL commands without a valid GL context
            if (!surfaceAvailable) {
                if (allowWhenSurfaceUnavailable) {
                    Log.d(TAG, "withRenderer: Surface unavailable, deferring action for next surface creation")
                    deferredRendererActions.add(action)
                } else {
                    Log.d(TAG, "withRenderer: Surface unavailable, skipping action")
                }
                return
            }

            // Check visibility - allowWhenSurfaceUnavailable also bypasses visibility check
            if (!allowWhenSurfaceUnavailable && !engineVisible) {
                Log.d(TAG, "withRenderer: Engine not visible, skipping action")
                return
            }

            Log.d(TAG, "withRenderer: Queueing action on GL thread")
            queueEvent { action(r) }
            if (engineVisible) {
                super.requestRender()
            }
        }


        // BroadcastReceiver for handling shortcut actions
        private val shortcutReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Actions.ACTION_NEXT_IMAGE -> advanceToNextImage()
                    Actions.ACTION_NEXT_DUOTONE -> applyNextDuotonePreset()
                    Actions.ACTION_SET_BLUR_PERCENT -> {
                        Actions.BlurPercentAction.fromIntent(intent)?.let { action ->
                            preferences.setBlurAmount(action.percent)
                        }
                    }

                    Actions.ACTION_ENABLE_BLUR -> enableBlur()
                }
            }
        }

        private val preferenceHandlers: Map<String, () -> Unit> = mapOf(
            WallpaperPreferences.KEY_BLUR_AMOUNT to ::onBlurPreferenceChanged,
            WallpaperPreferences.KEY_DIM_AMOUNT to ::applyDimPreference,
            WallpaperPreferences.KEY_DUOTONE_SETTINGS to ::applyDuotoneSettingsPreference,
            WallpaperPreferences.KEY_IMAGE_FOLDER_URIS to ::applyImageFolderPreference,
            WallpaperPreferences.KEY_TRANSITION_ENABLED to ::applyTransitionEnabledPreference,
            WallpaperPreferences.KEY_TRANSITION_INTERVAL to ::applyTransitionIntervalPreference,
            WallpaperPreferences.KEY_EFFECT_TRANSITION_DURATION to ::applyEffectTransitionDurationPreference
        )

        private val preferenceListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                preferenceHandlers[key]?.invoke()
            }


        override fun onCreate(surfaceHolder: SurfaceHolder) {
            Log.d(TAG, "onCreate called")
            super.onCreate(surfaceHolder)
            renderer = ShimmerRenderer(this)
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 0, 0, 0)
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY

            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)

            preferences.registerListener(preferenceListener)

            Actions.registerReceivers(this@ShimmerWallpaperService, shortcutReceiver)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "onSurfaceCreated called")
            super.onSurfaceCreated(holder)
            surfaceAvailable = true

            executeDeferredActions()

            super.requestRender()
        }

        private fun executeDeferredActions() {
            renderer?.let { r ->
                if (surfaceAvailable && deferredRendererActions.isNotEmpty()) {
                    deferredRendererActions.forEach { queueEvent { it(r) } }
                    deferredRendererActions.clear()
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "onSurfaceDestroyed called")
            surfaceAvailable = false
            withRenderer(allowWhenSurfaceUnavailable = true) { it.setParallaxOffset(0.5f) }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            Log.d(TAG, "onVisibilityChanged: $visible")
            super.onVisibilityChanged(visible)
            engineVisible = visible

            // To distinguish app switch from screen lock, check multiple signals
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val isOnLockScreen = keyguardManager?.isKeyguardLocked ?: false

            // Check if screen is actually on
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val isScreenOn = powerManager?.isInteractive ?: true

            // Apply app switch blur only when:
            // - Wallpaper becomes invisible
            // - Screen is ON (not turning off/locked)
            // - Keyguard is NOT showing
            // This combination indicates an app opened over the wallpaper
            if (!visible && isScreenOn && !isOnLockScreen && preferences.isBlurOnAppSwitchEnabled()) {
                Log.d(TAG, "  → Applying blur due to app switch")
                enableBlur()
            } else if (visible && isScreenOn && isOnLockScreen && preferences.isBlurOnScreenLockEnabled()) {
                Log.d(TAG, "  → Applying blur due to screen lock (visibility change)")
                enableBlur()
            }

            if (engineVisible) {
                tapGestureDetector.reset()
                if (surfaceAvailable) {
                    super.requestRender()
                }
            }
        }

        override fun onDestroy() {
            Log.d(TAG, "onDestroy called")
            preferences.unregisterListener(preferenceListener)
            try {
                this@ShimmerWallpaperService.unregisterReceiver(shortcutReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered, ignore
            }
            transitionScheduler.cancel()
            imageLoadExecutor.shutdownNow()
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (tapGestureDetector.onTouchEvent(event)) {
                TapEvent.TWO_FINGER_DOUBLE_TAP -> {
                    advanceToNextImage()
                }

                TapEvent.TRIPLE_TAP -> {
                    withRenderer { it.toggleBlur() }
                }

                TapEvent.NONE -> {
                    // No gesture detected, continue with default handling
                }
            }
            super.onTouchEvent(event)
        }

        override fun requestRender() {
            if (surfaceAvailable && engineVisible) {
                super.requestRender()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(
                xOffset,
                yOffset,
                xOffsetStep,
                yOffsetStep,
                xPixelOffset,
                yPixelOffset
            )
            withRenderer { it.setParallaxOffset(xOffset) }
        }

        private fun loadDefaultImage() {
            Log.d(TAG, "loadDefaultImage: Loading default wallpaper image")
            // Called on folderScheduler thread
            if (folderRepository.hasFolders()) {
                return
            }
            try {
                // Determine optimal sample size based on screen height
                val targetHeight = if (screenHeight > 0) screenHeight else 1920 // Fallback

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
                    TAG, "loadDefaultImage: Decoding default image: ${boundsOptions.outWidth}x${boundsOptions.outHeight} -> " +
                            "inSampleSize=$sampleSize (target height=$targetHeight)"
                )
                val bitmap = BitmapFactory.decodeResource(
                    resources,
                    R.drawable.default_wallpaper,
                    options
                )

                val blurAmount = preferences.getBlurAmount()
                val maxRadius = blurAmount * MAX_SUPPORTED_BLUR_RADIUS_PIXELS
                Log.d(TAG, "loadDefaultImage: Generating blur levels for default image with maxRadius=$maxRadius")
                val blurLevels = bitmap.generateBlurLevels(BLUR_KEYFRAMES, maxRadius)
                val imageSet = ImageSet(
                    original = bitmap,
                    blurred = blurLevels
                )

                // Mark that we're using the default image (no URI means default)
                currentImageUri = null

                withRenderer(allowWhenSurfaceUnavailable = true) { it.setImage(imageSet) }
            } catch (e: Exception) {
                Log.e(TAG, "loadDefaultImage: Error loading default image: ${e.message}", e)
            }
        }


        private fun applyPreferences() {
            Log.d(TAG, "applyPreferences: Applying wallpaper preferences")
            if (!isInitialLoad) {
                return
            }
            applyImageFolderPreference()
            applyBlurPreference()
            applyDimPreference()
            applyDuotoneSettingsPreference()
            applyTransitionEnabledPreference()
            applyTransitionIntervalPreference()
            applyEffectTransitionDurationPreference()
        }

        private fun applyBlurPreference() {
            val blurAmount = preferences.getBlurAmount()
            withRenderer { it.enableBlur(blurAmount > 0f) }
        }

        private fun enableBlur() {
            withRenderer(allowWhenSurfaceUnavailable = true) { it.enableBlur(true) }
        }

        private fun onBlurPreferenceChanged() {
            reBlurCurrentImage()
        }

        private fun applyDimPreference() {
            val dimAmount = preferences.getDimAmount()
            withRenderer { it.setUserDimAmount(dimAmount) }
        }

        private fun applyDuotoneSettingsPreference() {
            val settings = preferences.getDuotoneSettings()
            withRenderer {
                it.setDuotoneSettings(
                    enabled = settings.enabled,
                    alwaysOn = settings.alwaysOn,
                    duotone = Duotone(
                        lightColor = settings.lightColor,
                        darkColor = settings.darkColor,
                        opacity = if (settings.enabled) 1f else 0f
                    ),
                )
            }
        }

        private fun applyEffectTransitionDurationPreference() {
            val duration = preferences.getEffectTransitionDurationMillis()
            withRenderer { it.setEffectTransitionDuration(duration) }
        }

        private fun applyImageFolderPreference() {
            val folderUris = preferences.getImageFolderUris()
            setImageFolders(folderUris)
        }


        private fun setImageFolders(uris: List<String>) {
            Log.d(TAG, "setImageFolders: Updating image folders to $uris")
            transitionScheduler.cancel()
            folderRepository.updateFolders(uris)

            if (!folderRepository.hasFolders()) {
                Log.d(TAG, "setImageFolders: No folders set, loading last or default image")
                // If no folders are set, try to load the last image. If that fails, load default.
                imageLoadExecutor.execute {
                    if (!loadLastImage()) {
                        loadDefaultImage()
                    }
                }
                return
            }

            imageLoadExecutor.execute {
                // Try to load last image, otherwise next, otherwise default
                if (loadLastImage()) {
                    transitionScheduler.start()
                } else {
                    Log.d(TAG, "setImageFolders: loading default image")
                    loadDefaultImage()
                }
            }
        }

        private fun loadLastImage(): Boolean {
            Log.d(TAG, "loadLastImage: Attempting to load last image URI from preferences")
            var lastImageUriString = preferences.getLastImageUri()
            if (lastImageUriString.isNullOrBlank()) {
                val nextUri = folderRepository.nextImageUri()
                if (nextUri != null) {
                    Log.d(TAG, "loadLastImage: No last image URI, using next image URI from repo: $nextUri")
                    lastImageUriString = nextUri.toString()
                } else {
                    return false
                }
            }

            try {
                val lastImageUri = lastImageUriString.toUri()
                val payload = prepareRendererImage(lastImageUri)
                if (payload != null) {
                    withRenderer(allowWhenSurfaceUnavailable = true) { it.setImage(payload) }
                    return true
                } else {
                    // If payload is null, image couldn't be prepared (e.g., file not found). Clear preference.
                    preferences.setLastImageUri(null)
                }
            } catch (e: Exception) {
                Log.e("ShimmerWallpaperService", "Error loading last image URI: $lastImageUriString", e)
                preferences.setLastImageUri(null) // Clear invalid URI
            }
            return false
        }

        private fun applyTransitionIntervalPreference() {
            val interval = preferences.getTransitionIntervalMillis()
            transitionScheduler.updateInterval(interval)
        }

        private fun applyTransitionEnabledPreference() {
            val enabled = preferences.isTransitionEnabled()
            transitionScheduler.updateEnabled(enabled)
        }

        private fun requestImageChange() {
            imageLoadExecutor.execute {
                Log.d(TAG, "requestImageChange START: pendingUri=$pendingImageUri")

                // Check if renderer is currently animating
                val isAnimating = renderer?.isAnimating() ?: false
                Log.d(TAG, "  isAnimating=$isAnimating, pendingUri!=null=${pendingImageUri != null}")

                if (isAnimating) {
                    // Animation is running - queue/replace this request
                    val next = folderRepository.nextImageUri()
                    Log.d(TAG, "  → QUEUING/REPLACING as pending (animating): old=$pendingImageUri, new=$next")
                    pendingImageUri = next
                    return@execute
                }

                // No animation running - load immediately
                // Prioritize pending request if it exists, otherwise get next image
                val uriToLoad = pendingImageUri ?: folderRepository.nextImageUri()
                pendingImageUri = null  // Clear pending since we're about to load
                Log.d(TAG, "  → NOT animating, loading: $uriToLoad")
                if (uriToLoad != null) {
                    Log.d(TAG, "  → EXECUTING load immediately")
                    loadImage(uriToLoad)
                } else {
                    Log.d(TAG, "  → No image to load")
                }
            }
        }

        fun advanceToNextImage() {
            requestImageChange()
        }

        fun applyNextDuotonePreset() {
            // Get the last applied preset index and increment it (round-robin)
            val lastIndex = preferences.getDuotonePresetIndex()
            val nextIndex = (lastIndex + 1) % DUOTONE_PRESETS.size
            val nextPreset = DUOTONE_PRESETS[nextIndex]

            // Apply preset atomically - single transaction triggers only one listener notification
            preferences.applyDuotonePreset(
                lightColor = nextPreset.lightColor,
                darkColor = nextPreset.darkColor,
                enabled = true,
                presetIndex = nextIndex
            )

            Toast.makeText(this@ShimmerWallpaperService, "Duotone Preset: ${nextPreset.name}", Toast.LENGTH_SHORT).show()
        }

        private fun loadImage(uri: Uri) {
            Log.d(TAG, "loadImage: Loading image from $uri")
            // Called on folderScheduler thread to load a specific image
            val imageSet = prepareRendererImage(uri)
            if (imageSet != null) {
                Log.d(TAG, "loadImage: imageSet prepared, calling setImage with allowWhenSurfaceUnavailable = true")
                withRenderer(allowWhenSurfaceUnavailable = true) { it.setImage(imageSet) }
                preferences.setLastImageUri(uri.toString())
                Log.d(TAG, "loadImage: COMPLETE")
            } else {
                Log.d(TAG, "loadImage: Failed to prepare image")
            }
        }

        private fun handleScheduledAdvance() {
            requestImageChange()
        }

        private fun prepareRendererImage(uri: Uri): ImageSet? {
            Log.d(TAG, "prepareRendererImage: Decoding bitmap")
            val bitmap = decodeBitmapFromUri(uri) ?: return null

            val blurAmount = preferences.getBlurAmount()
            val maxRadius = blurAmount * MAX_SUPPORTED_BLUR_RADIUS_PIXELS

            val blurLevels = bitmap.generateBlurLevels(BLUR_KEYFRAMES, maxRadius)

            // Store the current image URI for potential re-blurring
            currentImageUri = uri

            return ImageSet(
                original = bitmap,
                blurred = blurLevels
            )
        }

        private fun reBlurCurrentImage() {
            Log.d(TAG, "reBlurCurrentImage: Re-blurring current image")
            val uri = currentImageUri ?: return
            val imageSet = prepareRendererImage(uri) ?: return
            withRenderer(allowWhenSurfaceUnavailable = true) { it.setImage(imageSet) }
        }

        private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
            return try {
                // Determine optimal sample size based on screen height
                val targetHeight = if (screenHeight > 0) screenHeight else 1920 // Fallback to common resolution

                // First pass: decode bounds only
                val boundsOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, boundsOptions)
                }

                // Calculate optimal sample size using the shared helper (with leeway)
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
                e.printStackTrace()
                null
            }
        }
    }
}
