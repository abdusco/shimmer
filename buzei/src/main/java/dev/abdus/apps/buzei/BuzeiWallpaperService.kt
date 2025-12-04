package dev.abdus.apps.buzei

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.widget.Toast
import net.rbgrn.android.glwallpaperservice.GLWallpaperService
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class BuzeiWallpaperService : GLWallpaperService() {

    companion object {
        private const val MAX_BLUR_RADIUS_PIXELS = 150f

        @Volatile
        private var activeEngineRef: WeakReference<BuzeiWallpaperEngine>? = null

        fun requestNextImage() {
            activeEngineRef?.get()?.advanceToNextImage()
        }

        fun requestNextDuotonePreset() {
            activeEngineRef?.get()?.applyNextDuotonePreset()
        }
    }

    override fun onCreateEngine(): Engine {
        return BuzeiWallpaperEngine()
    }

    inner class BuzeiWallpaperEngine : GLEngine(), BuzeiRenderer.Callbacks {

        private lateinit var renderer: BuzeiRenderer
        private var surfaceAvailable = false
        private var engineVisible = true
        private val folderScheduler = Executors.newSingleThreadScheduledExecutor()
        private val folderRepository = ImageFolderRepository(this@BuzeiWallpaperService)
        private val transitionScheduler =
            ImageTransitionScheduler(folderScheduler) { handleScheduledAdvance() }

        // Track current image for reprocessing when settings change
        private var currentImageUri: Uri? = null
        private var currentImageBitmap: Bitmap? = null
        private var duotoneInitialized = false

        private val tapGestureDetector = TapGestureDetector(this@BuzeiWallpaperService)
        private val prefs: SharedPreferences =
            WallpaperPreferences.prefs(this@BuzeiWallpaperService)


        private val preferenceHandlers: Map<String, () -> Unit> = mapOf(
            WallpaperPreferences.KEY_BLUR_AMOUNT to ::applyBlurPreference,
            WallpaperPreferences.KEY_DIM_AMOUNT to ::applyDimPreference,
            WallpaperPreferences.KEY_DUOTONE_SETTINGS to ::applyDuotoneSettingsPreference,
            WallpaperPreferences.KEY_IMAGE_FOLDER_URIS to ::applyImageFolderPreference,
            WallpaperPreferences.KEY_TRANSITION_ENABLED to ::applyTransitionEnabledPreference,
            WallpaperPreferences.KEY_TRANSITION_INTERVAL to ::applyTransitionIntervalPreference
        )
        private val preferenceListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                preferenceHandlers[key]?.invoke()
            }

        init {
            activeEngineRef = WeakReference(this)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer = BuzeiRenderer(this)
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 0, 0, 0)
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY

            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)

            prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceAvailable = true
            // Apply all preferences now that surface is ready
            applyPreferences()
            super.requestRender()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceAvailable = false
            queueRendererEvent(allowWhenSurfaceUnavailable = true) {
                renderer.setParallaxOffset(0.5f)
            }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            engineVisible = visible
            if (engineVisible && surfaceAvailable) {
                super.requestRender()
            }
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            transitionScheduler.cancel()
            folderScheduler.shutdownNow()
            if (activeEngineRef?.get() == this) {
                activeEngineRef = null
            }
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (tapGestureDetector.onTouchEvent(event)) {
                TapEvent.TWO_FINGER_DOUBLE_TAP -> {
                    android.util.Log.d("BuzeiWallpaper", "Two-finger double tap detected - advancing to next image")
                    advanceToNextImage()
                }
                TapEvent.TRIPLE_TAP -> {
                    android.util.Log.d("BuzeiWallpaper", "Triple tap detected - toggling blur")
                    queueRendererEvent { renderer.toggleBlur() }
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
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(
                xOffset,
                yOffset,
                xOffsetStep,
                yOffsetStep,
                xPixelOffset,
                yPixelOffset
            )
            queueRendererEvent { renderer.setParallaxOffset(xOffset) }
        }

        private fun loadDefaultImage() {
            if (folderRepository.hasFolders()) {
                return
            }
            thread {
                try {
                    // Load embedded default wallpaper from drawable resources
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 2
                    }
                    val bitmap = BitmapFactory.decodeResource(
                        resources,
                        R.drawable.default_wallpaper,
                        options
                    )
                    bitmap?.let {
                        currentImageUri = null
                        currentImageBitmap = it
                        val blurAmount = WallpaperPreferences.getBlurAmount(prefs)
                        val blurred = processImageForRenderer(it, blurAmount)
                        val payload = RendererImagePayload(
                            original = it,
                            blurred = blurred,
                            sourceUri = null
                        )
                        queueRendererEvent(allowWhenSurfaceUnavailable = true) {
                            renderer.setImage(payload)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        /**
         * Process image for renderer. Duotone is now applied via GPU shader.
         * This only handles blur processing.
         */
        private fun processImageForRenderer(
            source: Bitmap,
            blurFraction: Float,
        ): Bitmap {
            val normalizedBlur = blurFraction.coerceIn(0f, 1f)
            val radius = normalizedBlur * MAX_BLUR_RADIUS_PIXELS
            val blurred = if (radius <= 0f) {
                source
            } else {
                source.blur(radius) ?: source
            }
            return blurred
        }


        private fun applyPreferences() {
            preferenceHandlers.forEach { (_, handler) ->
                handler()
            }
        }

        private fun applyBlurPreference() {
            reprocessCurrentImage()
        }

        private fun applyDimPreference() {
            val dimAmount = WallpaperPreferences.getDimAmount(prefs)
            queueRendererEvent { renderer.setUserDimAmount(dimAmount) }
        }

        private fun applyDuotoneSettingsPreference() {
            val settings = WallpaperPreferences.getDuotoneSettings(prefs)
            val animate = duotoneInitialized // Only animate after first initialization
            duotoneInitialized = true
            queueRendererEvent {
                renderer.setDuotoneSettings(
                    settings.enabled,
                    settings.alwaysOn,
                    settings.lightColor,
                    settings.darkColor,
                    animate = animate
                )
            }
        }


        private fun applyImageFolderPreference() {
            val folderUris = WallpaperPreferences.getImageFolderUris(prefs)
            setImageFolders(folderUris)
        }

        private fun queueRendererEvent(
            allowWhenSurfaceUnavailable: Boolean = false,
            action: () -> Unit
        ) {
            if ((!surfaceAvailable && !allowWhenSurfaceUnavailable) || !engineVisible) {
                return
            }
            queueEvent(action)
            if (surfaceAvailable && engineVisible) {
                super.requestRender()
            }
        }

        private fun setImageFolders(uris: List<String>) {
            transitionScheduler.cancel()
            folderRepository.updateFolders(uris)
            if (!folderRepository.hasFolders()) {
                loadDefaultImage()
                return
            }
            folderScheduler.execute {
                if (!loadNextImageFromFolders(skipBlur = true)) {
                    loadDefaultImage()
                    return@execute
                }
                // Only start scheduler if we successfully loaded an image
                transitionScheduler.start()

                // Apply blur in background after initial display
                reprocessCurrentImage()
            }
        }

        private fun applyTransitionIntervalPreference() {
            val interval = WallpaperPreferences.getTransitionIntervalMillis(prefs)
            transitionScheduler.updateInterval(interval)
        }

        private fun applyTransitionEnabledPreference() {
            val enabled = WallpaperPreferences.isTransitionEnabled(prefs)
            transitionScheduler.updateEnabled(enabled)
        }

        fun advanceToNextImage() {
            folderScheduler.execute { handleManualAdvance() }
        }

        fun applyNextDuotonePreset() {
            // Get the last applied preset index and increment it (round-robin)
            val lastIndex = WallpaperPreferences.getDuotonePresetIndex(prefs)
            val nextIndex = (lastIndex + 1) % DUOTONE_PRESETS.size
            val nextPreset = DUOTONE_PRESETS[nextIndex]

            // Apply preset atomically - single transaction triggers only one listener notification
            WallpaperPreferences.applyDuotonePreset(
                prefs = prefs,
                lightColor = nextPreset.lightColor,
                darkColor = nextPreset.darkColor,
                enabled = true,
                presetIndex = nextIndex
            )

            Toast.makeText(this@BuzeiWallpaperService, "Duotone Preset: ${nextPreset.name}", Toast.LENGTH_SHORT).show()
        }

        private fun handleScheduledAdvance() {
            performAdvance()
        }

        private fun handleManualAdvance() {
            performAdvance()
            // Restart scheduler to reset the timer after manual advance
            transitionScheduler.restartAfterManualAdvance()
        }

        private fun performAdvance(): Boolean {
            val loaded = loadNextImageFromFolders()
            if (!loaded && !folderRepository.hasFolders()) {
                loadDefaultImage()
                return false
            }
            return loaded
        }

        private fun loadNextImageFromFolders(skipBlur: Boolean = false): Boolean {
            val payload = prepareNextPayload(skipBlur) ?: return false
            queueRendererEvent(allowWhenSurfaceUnavailable = true) {
                renderer.setImage(payload)
            }
            // Track current image for reprocessing
            currentImageUri = payload.sourceUri
            currentImageBitmap = payload.original
            return true
        }

        private fun prepareNextPayload(skipBlur: Boolean = false): RendererImagePayload? {
            val nextUri = folderRepository.nextImageUri() ?: return null
            return prepareRendererImage(nextUri, skipBlur)
        }

        private fun prepareRendererImage(uri: Uri, skipBlur: Boolean = false): RendererImagePayload? {
            val bitmap = decodeBitmapFromUri(uri) ?: return null

            // Skip blur for fast initial load
            val blurred = if (skipBlur) {
                bitmap
            } else {
                val blurAmount = WallpaperPreferences.getBlurAmount(prefs)
                processImageForRenderer(bitmap, blurAmount)
            }

            return RendererImagePayload(
                original = bitmap,
                blurred = blurred,
                sourceUri = uri
            )
        }

        private fun decodeBitmapFromUri(uri: Uri): Bitmap? {
            return try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 2
                    }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        private fun reprocessCurrentImage() {
            val bitmap = currentImageBitmap ?: return
            folderScheduler.execute {
                val blurAmount = WallpaperPreferences.getBlurAmount(prefs)
                val blurred = processImageForRenderer(bitmap, blurAmount)
                val payload = RendererImagePayload(
                    original = bitmap,
                    blurred = blurred,
                    sourceUri = currentImageUri
                )
                queueRendererEvent(allowWhenSurfaceUnavailable = true) {
                    renderer.setImage(payload)
                }
            }
        }
    }
}
