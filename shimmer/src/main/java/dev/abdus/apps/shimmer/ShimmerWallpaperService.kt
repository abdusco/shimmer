package dev.abdus.apps.shimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.widget.Toast
import net.rbgrn.android.glwallpaperservice.GLWallpaperService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class ShimmerWallpaperService : GLWallpaperService() {
    override fun onCreateEngine(): Engine {
        return ShimmerWallpaperEngine()
    }

    inner class ShimmerWallpaperEngine : GLEngine(), ShimmerRenderer.Callbacks {

        private lateinit var renderer: ShimmerRenderer
        private var surfaceAvailable = false
        private var engineVisible = true
        private val folderScheduler = Executors.newSingleThreadScheduledExecutor()
        private val folderRepository = ImageFolderRepository(this@ShimmerWallpaperService)
        private val transitionScheduler =
            ImageTransitionScheduler(folderScheduler) { handleScheduledAdvance() }

        // Track current image for reprocessing when settings change
        private var currentImageBitmap: Bitmap? = null
        private val tapGestureDetector = TapGestureDetector(this@ShimmerWallpaperService)
        private val preferences = WallpaperPreferences.create(this@ShimmerWallpaperService)

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
            WallpaperPreferences.KEY_BLUR_AMOUNT to ::applyBlurPreference,
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
            if (engineVisible) {
                tapGestureDetector.reset()
                if (surfaceAvailable) {
                    super.requestRender()
                }
            }
        }

        override fun onDestroy() {
            preferences.unregisterListener(preferenceListener)
            try {
                this@ShimmerWallpaperService.unregisterReceiver(shortcutReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered, ignore
            }
            transitionScheduler.cancel()
            folderScheduler.shutdownNow()
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (tapGestureDetector.onTouchEvent(event)) {
                TapEvent.TWO_FINGER_DOUBLE_TAP -> {
                    advanceToNextImage()
                }
                TapEvent.TRIPLE_TAP -> {
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
                        currentImageBitmap = it
                        val blurAmount = preferences.getBlurAmount()
                        val maxRadius = blurAmount * MAX_SUPPORTED_BLUR_RADIUS_PIXELS
                        val blurLevels = it.generateBlurLevels(BLUR_KEYFRAMES, maxRadius)
                        val imageSet = ImageSet(
                            original = it,
                            blurred = blurLevels
                        )
                        queueRendererEvent(allowWhenSurfaceUnavailable = true) {
                            renderer.setImage(imageSet)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
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
            val dimAmount = preferences.getDimAmount()
            queueRendererEvent { renderer.setUserDimAmount(dimAmount) }
        }

        private fun applyDuotoneSettingsPreference() {
            val settings = preferences.getDuotoneSettings()
            queueRendererEvent {
                renderer.setDuotoneSettings(
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
            queueRendererEvent {
                renderer.setEffectTransitionDuration(duration)
            }
        }

        private fun applyImageFolderPreference() {
            val folderUris = preferences.getImageFolderUris()
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
                if (!loadNextImageFromFolders()) {
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
            val interval = preferences.getTransitionIntervalMillis()
            transitionScheduler.updateInterval(interval)
        }

        private fun applyTransitionEnabledPreference() {
            val enabled = preferences.isTransitionEnabled()
            transitionScheduler.updateEnabled(enabled)
        }

        fun advanceToNextImage() {
            folderScheduler.execute { handleManualAdvance() }
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

        fun enableBlur() {
            queueRendererEvent { renderer.enableBlur() }
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

        private fun loadNextImageFromFolders(): Boolean {
            val nextUri = folderRepository.nextImageUri() ?: return false
            val payload = prepareRendererImage(nextUri) ?: return false

            queueRendererEvent(allowWhenSurfaceUnavailable = true) {
                renderer.setImage(payload)
            }
            // Track current image for reprocessing
            currentImageBitmap = payload.original
            return true
        }

        private fun prepareRendererImage(uri: Uri): ImageSet? {
            val bitmap = decodeBitmapFromUri(uri) ?: return null

            val blurAmount = preferences.getBlurAmount()
            val maxRadius = blurAmount * MAX_SUPPORTED_BLUR_RADIUS_PIXELS

            val blurLevels = bitmap.generateBlurLevels(BLUR_KEYFRAMES, maxRadius)

            return ImageSet(
                original = bitmap,
                blurred = blurLevels
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
                val blurAmount = preferences.getBlurAmount()

                val maxRadius = blurAmount * MAX_SUPPORTED_BLUR_RADIUS_PIXELS
                val blurLevels = bitmap.generateBlurLevels(BLUR_KEYFRAMES, maxRadius)

                val payload = ImageSet(
                    original = bitmap,
                    blurred = blurLevels
                )
                queueRendererEvent(allowWhenSurfaceUnavailable = true) {
                    renderer.setImage(payload)
                }
            }
        }
    }
}
