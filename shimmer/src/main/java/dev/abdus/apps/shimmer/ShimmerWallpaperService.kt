package dev.abdus.apps.shimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import dev.abdus.apps.shimmer.gl.GLWallpaperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ShimmerWallpaperService : GLWallpaperService() {
    companion object {
        const val TAG = "ShimmerWallpaperService"
    }

    override fun onCreateEngine(): Engine = ShimmerWallpaperEngine()

    inner class ShimmerWallpaperEngine : GLEngine(), ShimmerRenderer.Callbacks {

        private var renderer: ShimmerRenderer? = null
        private var rendererReady = false
        private var engineVisible = true

        override fun requestRender() {
            // Only request render if engine is visible
            if (engineVisible) super.requestRender()
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val preferences = WallpaperPreferences.create(this@ShimmerWallpaperService)
        private val folderRepository = ImageFolderRepository(this@ShimmerWallpaperService)
        private val imageLoader = ImageLoader(contentResolver, resources)
        private val transitionScheduler =
                ImageTransitionScheduler(scope, preferences) {
                    Log.d(TAG, "TransitionScheduler triggered image advance")
                    requestImageChange()
                }
        private val tapGestureDetector = TapGestureDetector(this@ShimmerWallpaperService)

        // Cache state
        private var cachedImageSet: ImageSet? = null
        private var pendingImageUri: Uri? = null
        private var currentImageUri: Uri? = null
        private var sessionBlurEnabled = preferences.getBlurAmount() > 0f

        private var surfaceDimensions = SurfaceDimensions(0, 0)
        private val touchList = ArrayList<TouchData>(10)

        private val blurTimeoutHandler = Handler(Looper.getMainLooper())
        private val blurTimeoutRunnable = Runnable {
            if (preferences.isBlurTimeoutEnabled() && engineVisible) {
                sessionBlurEnabled = true
                applyBlurState(immediate = false)
            }
        }

        private val preferenceListener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    syncRendererSettings(key)
                }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.d(TAG, "Engine onCreate: initializing renderer and resources")
            renderer = ShimmerRenderer(this)
            setEGLContextClientVersion(3) // GLES 3.0
            setRenderer(renderer!!)
            setRenderMode(RENDERMODE_WHEN_DIRTY)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)

            preferences.registerListener(preferenceListener)
            Actions.registerReceivers(this@ShimmerWallpaperService, shortcutReceiver)
            registerReceiver(screenUnlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))

            folderRepository.updateFolders(preferences.getImageFolders().map { it.uri })
        }

        private fun syncRendererSettings(key: String? = null) {
            Log.d(TAG, "syncRendererSettings called, key=")
            if (key != null) Log.d(TAG, "syncRendererSettings key=$key")
            val r = renderer ?: return
            if (!rendererReady) return

            if (key == WallpaperPreferences.KEY_BLUR_AMOUNT) {
                Log.d(TAG, "syncRendererSettings: BLUR_AMOUNT changed, re-blurring current image")
                reBlurCurrentImage()
                return
            }

            queueEvent {
                if (key == null ||
                                key == WallpaperPreferences.KEY_DIM_AMOUNT ||
                                key == WallpaperPreferences.KEY_DUOTONE_SETTINGS ||
                                key == WallpaperPreferences.KEY_GRAIN_SETTINGS ||
                                key == WallpaperPreferences.KEY_CHROMATIC_ABERRATION_SETTINGS ||
                                key == WallpaperPreferences.KEY_EFFECT_TRANSITION_DURATION
                ) {

                    Log.d(TAG, "syncRendererSettings: updating renderer settings (key=$key)")
                    r.updateSettings(
                            blurAmount = preferences.getBlurAmount(),
                            dimAmount = preferences.getDimAmount(),
                            duotone = preferences.getDuotoneSettings(),
                            grain = preferences.getGrainSettings(),
                            chromatic = preferences.getChromaticAberrationSettings(),
                            transitionDuration = preferences.getEffectTransitionDurationMillis()
                    )
                }

                if (key == null) applyBlurState(immediate = true)

                if (key == WallpaperPreferences.KEY_IMAGE_FOLDERS) {
                    Log.d(TAG, "syncRendererSettings: image folders changed, updating repository")
                    folderRepository.updateFolders(preferences.getImageFolders().map { it.uri })
                }

                if (key == null ||
                                key == WallpaperPreferences.KEY_TRANSITION_ENABLED ||
                                key == WallpaperPreferences.KEY_TRANSITION_INTERVAL
                ) {
                    transitionScheduler.updateEnabled(preferences.isTransitionEnabled())
                }
            }
        }

        private fun applyBlurState(immediate: Boolean) {
            val r = renderer ?: return
            val blurOnLock = preferences.isBlurOnScreenLockEnabled()
            val isOnLockScreen =
                    (getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                            ?.isKeyguardLocked
                            ?: false
            val isScreenOn =
                    (getSystemService(POWER_SERVICE) as? android.os.PowerManager)?.isInteractive
                            ?: true

            val shouldForceBlur = blurOnLock && (isOnLockScreen || !isScreenOn)
            val effectiveBlur = shouldForceBlur || sessionBlurEnabled

            r.enableBlur(effectiveBlur, immediate || (shouldForceBlur && !isScreenOn))

            blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            if (!effectiveBlur && preferences.isBlurTimeoutEnabled()) {
                blurTimeoutHandler.postDelayed(
                        blurTimeoutRunnable,
                        preferences.getBlurTimeoutMillis()
                )
            }
        }

        override fun onRendererReady() {
            Log.d(TAG, "onRendererReady: renderer thread signalled ready")
            rendererReady = true
            syncRendererSettings(null)

            // If we have a cached image set, just re-upload it immediately
            val cached = cachedImageSet
            if (cached != null) {
                Log.d(TAG, "Restoring cached ImageSet")
                queueEvent { renderer?.setImage(cached) }
            } else {
                // Cold boot: find and load image
                scope.launch {
                    Log.d(TAG, "onRendererReady: performing cold boot image load")
                    val lastUri = preferences.getLastImageUri()
                    val uriToLoad =
                            if (lastUri != null && folderRepository.isImageUriValid(lastUri))
                                    lastUri
                            else folderRepository.nextImageUri()

                    if (uriToLoad != null) loadImage(uriToLoad) else loadDefaultImage()
                    transitionScheduler.start()
                }
            }
        }

        private fun reBlurCurrentImage() {
            Log.d(TAG, "reBlurCurrentImage: reloading blurred version for current image")
            val uri = currentImageUri
            scope.launch {
                val newSet =
                        if (uri != null) imageLoader.loadFromUri(uri, preferences.getBlurAmount())
                        else imageLoader.loadDefault(preferences.getBlurAmount())
                if (newSet != null) updateActiveImageSet(newSet, uri)
            }
        }

        override fun onReadyForNextImage() {
            Log.d(
                    TAG,
                    "onReadyForNextImage: next image is ready to be loaded (pending=$pendingImageUri)"
            )
            val uri = pendingImageUri ?: return
            pendingImageUri = null
            loadImage(uri)
        }

        private fun loadImage(uri: Uri) {
            Log.d(TAG, "loadImage: loading image from uri=$uri")
            scope.launch {
                val newSet = imageLoader.loadFromUri(uri, preferences.getBlurAmount())
                if (newSet != null) updateActiveImageSet(newSet, uri)
            }
        }

        private fun loadDefaultImage() {
            Log.d(TAG, "loadDefaultImage: loading bundled/default image")
            scope.launch {
                val newSet = imageLoader.loadDefault(preferences.getBlurAmount())
                if (newSet != null) updateActiveImageSet(newSet, null)
            }
        }

        private fun updateActiveImageSet(newSet: ImageSet, uri: Uri?) {
            Log.d(TAG, "updateActiveImageSet: applying new ImageSet (uri=${uri ?: "<default>"})")
            // Memory Management: Hold reference to the old set to recycle it later
            val oldSet = cachedImageSet

            cachedImageSet = newSet
            currentImageUri = uri
            if (uri != null) preferences.setLastImageUri(uri.toString())

            queueEvent {
                renderer?.setImage(newSet)
                // Once pushed to GL thread, we can safely dispose of the OLD bitmaps
                scope.launch(Dispatchers.Default) {
                    oldSet?.original?.recycle()
                    oldSet?.blurred?.forEach { b -> b.recycle() }
                }
            }
        }

        private fun requestRenderIfVisible() {
            if (engineVisible) requestRender()
        }

        private fun requestImageChange() {
            Log.d(TAG, "requestImageChange: triggered")
            scope.launch {
                if (renderer?.isAnimating() == true) {
                    val next = folderRepository.nextImageUri()
                    if (next != null) pendingImageUri = next
                    Log.d(
                            TAG,
                            "requestImageChange: renderer animating, queued next=$pendingImageUri"
                    )
                    return@launch
                }
                val nextUri = folderRepository.nextImageUri()
                Log.d(TAG, "requestImageChange: nextUri=$nextUri")
                if (nextUri != null) {
                    loadImage(nextUri)
                    preferences.setImageLastChangedAt(System.currentTimeMillis())
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            val touches = processTouches(event)
            if (touches.isNotEmpty()) {
                renderer?.setTouchPoints(touches)
            }

            when (tapGestureDetector.onTouchEvent(event)) {
                TapEvent.TWO_FINGER_DOUBLE_TAP -> {
                    Log.d(
                            TAG,
                            "onTouchEvent: TWO_FINGER_DOUBLE_TAP detected, requesting image change"
                    )
                    requestImageChange()
                    transitionScheduler.pauseForInteraction()
                }
                TapEvent.TRIPLE_TAP -> {
                    sessionBlurEnabled = !sessionBlurEnabled
                    Log.d(
                            TAG,
                            "onTouchEvent: TRIPLE_TAP toggled sessionBlurEnabled=$sessionBlurEnabled"
                    )
                    applyBlurState(immediate = false)
                    transitionScheduler.pauseForInteraction()
                }
                else -> {}
            }

            if (preferences.isBlurTimeoutEnabled()) {
                blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
                if (!sessionBlurEnabled)
                        blurTimeoutHandler.postDelayed(
                                blurTimeoutRunnable,
                                preferences.getBlurTimeoutMillis()
                        )
            }
            super.onTouchEvent(event)
        }

        private fun processTouches(event: MotionEvent): List<TouchData> {
            if (surfaceDimensions.width <= 0 || surfaceDimensions.height <= 0) return emptyList()

            touchList.clear()
            val action = event.actionMasked
            for (i in 0 until event.pointerCount) {
                val pid = event.getPointerId(i)
                val x = event.getX(i) / surfaceDimensions.width
                val y = 1f - (event.getY(i) / surfaceDimensions.height)
                val tact =
                        when {
                            (action == MotionEvent.ACTION_UP ||
                                    action == MotionEvent.ACTION_CANCEL) -> TouchAction.UP
                            (action == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) ->
                                    TouchAction.UP
                            (action == MotionEvent.ACTION_DOWN && i == 0) -> TouchAction.DOWN
                            (action == MotionEvent.ACTION_POINTER_DOWN && i == event.actionIndex) ->
                                    TouchAction.DOWN
                            else -> TouchAction.MOVE
                        }
                touchList.add(TouchData(pid, x, y, tact))
            }
            return ArrayList(touchList)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            Log.d(TAG, "onVisibilityChanged: visible=$visible")
            super.onVisibilityChanged(visible)
            engineVisible = visible
            if (visible) {
                applyBlurState(immediate = false)
                transitionScheduler.start()
                queueEvent { renderer?.onVisibilityChanged() }
            } else {
                transitionScheduler.stop()
            }
        }

        override fun onOffsetsChanged(x: Float, y: Float, xs: Float, ys: Float, xp: Int, yp: Int) {
            transitionScheduler.pauseForInteraction()
            renderer?.setParallaxOffset(x)
        }

        override fun onSurfaceDimensionsChanged(width: Int, height: Int) {
            Log.d(TAG, "onSurfaceDimensionsChanged: width=$width height=$height")
            surfaceDimensions = SurfaceDimensions(width, height)
            imageLoader.setScreenHeight(height)
        }

        private val shortcutReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        Log.d(TAG, "shortcutReceiver received action=${intent.action}")
                        when (intent.action) {
                            Actions.ACTION_NEXT_IMAGE -> requestImageChange()
                            Actions.ACTION_NEXT_DUOTONE -> applyNextDuotone()
                            Actions.ACTION_ENABLE_BLUR -> {
                                sessionBlurEnabled = true
                                applyBlurState(false)
                            }
                        }
                    }
                }

        private val screenUnlockReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == Intent.ACTION_USER_PRESENT) {
                            Log.d(
                                    TAG,
                                    "User present (Unlock). UnblurEnabled=${preferences.isUnblurOnUnlockEnabled()}"
                            )

                            // LATCH LOGIC:
                            // If "Blur on Lock" was active, and "Unblur on Unlock" is DISABLED,
                            // we convert the forced lock blur into a permanent session blur.
                            if (preferences.isBlurOnScreenLockEnabled() &&
                                            !preferences.isUnblurOnUnlockEnabled()
                            ) {
                                sessionBlurEnabled = true
                            }

                            applyBlurState(immediate = false)

                            if (preferences.isChangeImageOnUnlockEnabled()) {
                                requestImageChange()
                            }
                        }
                    }
                }

        private fun applyNextDuotone() {
            Log.d(TAG, "applyNextDuotone: cycling duotone preset")
            val nextIndex = (preferences.getDuotonePresetIndex() + 1) % DUOTONE_PRESETS.size
            val p = DUOTONE_PRESETS[nextIndex]
            preferences.applyDuotonePreset(p.lightColor, p.darkColor, true, nextIndex)
        }

        override fun onSurfaceCreated(h: SurfaceHolder) {
            super.onSurfaceCreated(h)
            rendererReady = false
        }

        override fun onSurfaceDestroyed(h: SurfaceHolder) {
            Log.d(TAG, "onSurfaceDestroyed")
            rendererReady = false
            // Don't recycle bitmaps here - keep them cached for quick re-binding
            // Only recycle in onDestroy()
            renderer?.onSurfaceDestroyed()
            super.onSurfaceDestroyed(h)
        }

        override fun onDestroy() {
            Log.d(TAG, "Engine onDestroy: cleaning up resources")
            preferences.unregisterListener(preferenceListener)
            try {
                unregisterReceiver(shortcutReceiver)
            } catch (_: Exception) {}
            try {
                unregisterReceiver(screenUnlockReceiver)
            } catch (_: Exception) {}
            transitionScheduler.cancel()
            scope.cancel()
            blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            // Final Cleanup
            cachedImageSet?.let {
                it.original.recycle()
                it.blurred.forEach { b -> b.recycle() }
            }
            super.onDestroy()
        }
    }
}
