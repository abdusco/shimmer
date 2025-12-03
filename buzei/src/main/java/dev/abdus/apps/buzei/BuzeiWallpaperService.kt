package dev.abdus.apps.buzei

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.widget.Toast
import net.rbgrn.android.glwallpaperservice.GLWallpaperService
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class BuzeiWallpaperService : GLWallpaperService() {

    companion object {
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

        // Debounce handler to coalesce rapid preference updates
        private val reprocessDebouncer = android.os.Handler(android.os.Looper.getMainLooper())
        private var reprocessPending = false

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

            applyPreferences(skipImageFolderReload = true)
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceAvailable = true
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
            reprocessDebouncer.removeCallbacksAndMessages(null)
            transitionScheduler.cancel()
            folderScheduler.shutdownNow()
            if (activeEngineRef?.get() == this) {
                activeEngineRef = null
            }
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (tapGestureDetector.onTripleTap(event)) {
                queueRendererEvent { renderer.toggleBlur() }
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

        private fun loadImage() {
            if (folderRepository.hasFolders()) {
                return
            }
            thread {
                try {
                    val url = URL("https://images.unsplash.com/photo-1764193875912-0f0a64874344?ixlib=rb-4.1.0&q=85&fm=jpg&crop=entropy&cs=srgb&dl=luise-and-nic-6fWb2V1UIZU-unsplash.jpg&w=1920")
                    val inputStream = url.openStream()
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 2
                    }
                    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream.close()
                    bitmap?.let {
                        currentImageUri = null
                        currentImageBitmap = it
                        val blurAmount = WallpaperPreferences.getBlurAmount(prefs)
                        val processed = processImageForRenderer(it, blurAmount)
                        val payload = RendererImagePayload(
                            original = it,
                            blurred = processed.blurred,
                            sourceUri = null
                        )
                        queueRendererEvent(allowWhenSurfaceUnavailable = true) { renderer.setImage(payload) }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        private fun applyPreferences(skipImageFolderReload: Boolean = false) {
            preferenceHandlers.forEach { (key, handler) ->
                if (skipImageFolderReload && key == WallpaperPreferences.KEY_IMAGE_FOLDER_URIS) {
                    return@forEach
                }
                handler()
            }
        }

        private fun applyBlurPreference() {
            debouncedReprocessCurrentImage()
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
                renderer.setDuotoneSettings(settings.enabled, settings.lightColor, settings.darkColor, animate = animate)
            }
        }

        /**
         * Debounces image reprocessing to coalesce rapid preference updates.
         * Useful when multiple related preferences change in quick succession
         * (e.g., applying a duotone preset updates 3-4 preferences at once).
         */
        private fun debouncedReprocessCurrentImage() {
            if (reprocessPending) return
            reprocessPending = true
            reprocessDebouncer.postDelayed({
                reprocessPending = false
                reprocessCurrentImage()
            }, 50) // 50ms debounce is enough to coalesce batch updates
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
                loadImage()
                return
            }
            folderScheduler.execute {
                if (!loadNextImageFromFolders()) {
                    loadImage()
                    return@execute
                }
                transitionScheduler.start(hasFolders = true)
            }
        }

        private fun applyTransitionIntervalPreference() {
            val interval = WallpaperPreferences.getTransitionIntervalMillis(prefs)
            transitionScheduler.updateInterval(interval, folderRepository.hasFolders())
        }

        private fun applyTransitionEnabledPreference() {
            val enabled = WallpaperPreferences.isTransitionEnabled(prefs)
            transitionScheduler.updateEnabled(enabled, folderRepository.hasFolders())
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
            val hadFolders = folderRepository.hasFolders()
            performAdvance()
            if (hadFolders) {
                transitionScheduler.restartAfterManualAdvance(hadFolders)
            }
        }

        private fun performAdvance(): Boolean {
            val loaded = loadNextImageFromFolders()
            if (!loaded && !folderRepository.hasFolders()) {
                loadImage()
                return false
            }
            return loaded
        }

        private fun loadNextImageFromFolders(): Boolean {
            val payload = prepareNextPayload() ?: return false
            queueRendererEvent(allowWhenSurfaceUnavailable = true) {
                renderer.setImage(payload)
            }
            // Track current image for reprocessing
            currentImageUri = payload.sourceUri
            currentImageBitmap = payload.original
            return true
        }

        private fun prepareNextPayload(): RendererImagePayload? {
            val nextUri = folderRepository.nextImageUri() ?: return null
            return prepareRendererImage(nextUri)
        }

        private fun prepareRendererImage(uri: Uri): RendererImagePayload? {
            val bitmap = decodeBitmapFromUri(uri) ?: return null
            val blurAmount = WallpaperPreferences.getBlurAmount(prefs)
            val processed = processImageForRenderer(bitmap, blurAmount)
            return RendererImagePayload(
                original = bitmap,
                blurred = processed.blurred,
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
                val processed = processImageForRenderer(bitmap, blurAmount)
                val payload = RendererImagePayload(
                    original = bitmap,
                    blurred = processed.blurred,
                    sourceUri = currentImageUri
                )
                queueRendererEvent(allowWhenSurfaceUnavailable = true) {
                    renderer.setImage(payload)
                }
            }
        }
    }
}
