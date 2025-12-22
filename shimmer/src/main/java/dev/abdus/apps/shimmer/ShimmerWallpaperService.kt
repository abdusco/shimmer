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
import kotlinx.coroutines.isActive
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
            if (engineVisible) super.requestRender()
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val preferences = WallpaperPreferences.create(this@ShimmerWallpaperService)
        private val folderRepository = ImageFolderRepository(this@ShimmerWallpaperService)
        private val favoritesRepository = FavoritesRepository(this@ShimmerWallpaperService, preferences)
        private val imageLoader = ImageLoader(contentResolver, resources)
        private val transitionScheduler =
                ImageTransitionScheduler(scope, preferences) {
                    Log.d(TAG, "TransitionScheduler triggered image advance")
                    requestImageChange()
                }
        private val tapGestureDetector = TapGestureDetector(this@ShimmerWallpaperService)
        private val touchEffectController = TouchEffectController(
            onTouchPointsChanged = { touchPoints ->
                renderer?.setTouchPoints(touchPoints)
            },
        )

        private var cachedImageSet: ImageSet? = null
        private var pendingImageUri: Uri? = null
        private var imageLoadingJob: kotlinx.coroutines.Job? = null
        private var currentImageUri: Uri? = null
        private var sessionBlurEnabled = preferences.getBlurAmount() > 0f

        private var surfaceDimensions = SurfaceDimensions(0, 0)

        private var gestureActionMap: Map<TapEvent, GestureAction> = emptyMap()

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
            setEGLContextClientVersion(3)
            setRenderer(renderer!!)
            setRenderMode(RENDERMODE_WHEN_DIRTY)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)

            refreshGestureActionCache()
            preferences.registerListener(preferenceListener)
            Actions.registerReceivers(this@ShimmerWallpaperService, shortcutReceiver)
            registerReceiver(screenUnlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))

            folderRepository.updateFolders(resolveSourceFolders())
        }

        private fun syncRendererSettings(key: String? = null) {
            Log.d(TAG, "syncRendererSettings called, key=")
            if (key != null) Log.d(TAG, "syncRendererSettings key=$key")
            val r = renderer ?: return
            if (!rendererReady) return

            if (key == WallpaperPreferences.KEY_GESTURE_TRIPLE_TAP_ACTION ||
                key == WallpaperPreferences.KEY_GESTURE_TWO_FINGER_DOUBLE_TAP_ACTION ||
                key == WallpaperPreferences.KEY_GESTURE_THREE_FINGER_DOUBLE_TAP_ACTION
            ) {
                refreshGestureActionCache()
                return
            }

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
                    folderRepository.updateFolders(resolveSourceFolders())
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

            val cached = cachedImageSet
            if (cached != null) {
                Log.d(TAG, "Restoring cached ImageSet")
                queueEvent { renderer?.setImage(cached) }
                return
            }

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

        private fun reBlurCurrentImage() {
            Log.d(TAG, "reBlurCurrentImage: reloading blurred version for current image")
            val uri = currentImageUri
            scope.launch(Dispatchers.IO) {
                val newSet = if (uri != null) {
                    imageLoader.loadFromUri(uri, preferences.getBlurAmount())
                } else {
                    imageLoader.loadDefault(preferences.getBlurAmount())
                }

                if (newSet != null) {
                    updateActiveImageSet(newSet, uri)
                }
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
            imageLoadingJob?.cancel()

            imageLoadingJob = scope.launch {
                Log.d(TAG, "loadImage: loading image from uri=$uri")
                val blurAmount = preferences.getBlurAmount()

                val newSet = imageLoader.loadFromUri(uri, blurAmount)

                if (isActive && newSet != null) {
                    updateActiveImageSet(newSet, uri)
                } else {
                    newSet?.original?.recycle()
                    newSet?.blurred?.forEach { it.recycle() }
                    Log.d(TAG, "loadImage: job cancelled or failed, cleaned up bitmaps for $uri")
                }
            }
        }


        private fun loadDefaultImage() {
            imageLoadingJob?.cancel()
            imageLoadingJob = scope.launch {
                val newSet = imageLoader.loadDefault(preferences.getBlurAmount())
                if (isActive && newSet != null) {
                    updateActiveImageSet(newSet, null)
                } else {
                    newSet?.original?.recycle()
                    newSet?.blurred?.forEach { it.recycle() }
                }
            }
        }

        private fun updateActiveImageSet(newSet: ImageSet, uri: Uri?) {
            Log.d(TAG, "updateActiveImageSet: applying new ImageSet (uri=${uri ?: "<default>"})")
            val oldSet = cachedImageSet

            cachedImageSet = newSet
            currentImageUri = uri
            if (uri != null) preferences.setLastImageUri(uri.toString())

            queueEvent {
                renderer?.setImage(newSet)
                // Once pushed to GL thread, we can safely dispose of the OLD bitmaps
                scope.launch(Dispatchers.Default) {
                    oldSet?.recycleAll()
                }
            }
        }

        private fun requestImageChange() {
            Log.d(TAG, "requestImageChange: triggered")

            // If we're already animating a transition, just queue the next URI
            if (renderer?.isAnimating() == true) {
                scope.launch {
                    val next = folderRepository.nextImageUri()
                    if (next != null) pendingImageUri = next
                }
                return
            }

            // Otherwise, cancel any current loading and start the next one
            scope.launch {
                val nextUri = folderRepository.nextImageUri()
                if (nextUri != null) {
                    loadImage(nextUri)
                    transitionScheduler.resetTimer()
                    preferences.setImageLastChangedAt(System.currentTimeMillis())
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            touchEffectController.onTouchEvent(
                event,
                surfaceDimensions.width,
                surfaceDimensions.height
            )

            val tapEvent = tapGestureDetector.onTouchEvent(event)
            if (tapEvent != TapEvent.NONE) {
                val action = gestureActionMap[tapEvent] ?: GestureAction.NONE
                handleGestureAction(action, tapEvent)
            }

            if (preferences.isBlurTimeoutEnabled()) {
                blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
                if (!sessionBlurEnabled) {
                    blurTimeoutHandler.postDelayed(
                        blurTimeoutRunnable,
                        preferences.getBlurTimeoutMillis()
                    )
                }
            }

            super.onTouchEvent(event)
        }

        private fun refreshGestureActionCache() {
            gestureActionMap = mapOf(
                TapEvent.TRIPLE_TAP to preferences.getGestureAction(TapEvent.TRIPLE_TAP),
                TapEvent.TWO_FINGER_DOUBLE_TAP to preferences.getGestureAction(TapEvent.TWO_FINGER_DOUBLE_TAP),
                TapEvent.THREE_FINGER_DOUBLE_TAP to preferences.getGestureAction(TapEvent.THREE_FINGER_DOUBLE_TAP)
            )
        }

        private fun handleGestureAction(action: GestureAction, tapEvent: TapEvent) {
            when (action) {
                GestureAction.NEXT_IMAGE -> {
                    Log.d(TAG, "onTouchEvent: $tapEvent -> NEXT_IMAGE")
                    requestImageChange()
                }
                GestureAction.TOGGLE_BLUR -> {
                    sessionBlurEnabled = !sessionBlurEnabled
                    Log.d(TAG, "onTouchEvent: $tapEvent -> TOGGLE_BLUR sessionBlurEnabled=$sessionBlurEnabled")
                    applyBlurState(immediate = false)
                    transitionScheduler.pauseForInteraction()
                }
                GestureAction.RANDOM_DUOTONE -> {
                    Log.d(TAG, "onTouchEvent: $tapEvent -> RANDOM_DUOTONE")
                    applyNextDuotone()
                }
                GestureAction.FAVORITE -> {
                    Log.d(TAG, "onTouchEvent: $tapEvent -> FAVORITE")
                    addCurrentImageToFavorites()
                }
                GestureAction.NONE -> {}
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            Log.d(TAG, "onVisibilityChanged: visible=$visible")
            super.onVisibilityChanged(visible)
            engineVisible = visible
            if (visible) {
                transitionScheduler.onWallpaperVisible()
                applyBlurState(immediate = false)
                transitionScheduler.start()
                queueEvent { renderer?.onVisibilityChanged() }
            } else {
                transitionScheduler.onWallpaperHidden()
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
                            Actions.ACTION_REFRESH_FOLDERS -> folderRepository.updateFolders(resolveSourceFolders())
                            Actions.ACTION_ADD_TO_FAVORITES -> addCurrentImageToFavorites()
                            Actions.ACTION_SET_BLUR_PERCENT -> {
                                intent?.let { it ->
                                    Actions.BlurPercentAction.fromIntent(it!!)?.let { action ->
                                        preferences.setBlurAmount(action.percent!!)
                                    }
                                }
                            }
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

        private fun resolveSourceFolders(): List<ImageFolder> {
            val folders = preferences.getImageFolders().toMutableList()
            val favoritesUri = FavoritesFolderResolver.getEffectiveFavoritesUri(preferences)
            if (folders.none { it.uri == favoritesUri.toString() }) {
                folders.add(ImageFolder(uri = favoritesUri.toString()))
            }
            return folders
        }

        private fun addCurrentImageToFavorites() {
            if (currentImageUri == null) {
                return
            }

            scope.launch {
                val result = favoritesRepository.saveFavorite(currentImageUri!!)
                if (result.isSuccess) {
                    val saved = result.getOrNull()!!
                    Actions.broadcastFavoriteAdded(this@ShimmerWallpaperService, result = saved)
                } else {
                    Log.w(TAG, "addCurrentImageToFavorites: failed for $currentImageUri", result.exceptionOrNull())
                }
            }
        }

        override fun onSurfaceCreated(h: SurfaceHolder) {
            super.onSurfaceCreated(h)
            rendererReady = false
        }

        override fun onSurfaceDestroyed(h: SurfaceHolder) {
            Log.d(TAG, "onSurfaceDestroyed")
            rendererReady = false
            renderer?.onSurfaceDestroyed()
            super.onSurfaceDestroyed(h)
        }

        override fun onDestroy() {
            Log.d(TAG, "Engine onDestroy: cleaning up resources")

            touchEffectController.cleanup()

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

            super.onDestroy()
            cachedImageSet?.recycleAll()
        }
    }
}
