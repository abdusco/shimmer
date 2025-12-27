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
import kotlinx.coroutines.isActive
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
        private val favoritesRepository = FavoritesRepository(this@ShimmerWallpaperService, preferences, folderRepository)
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

            folderRepository.setOnCurrentImageInvalidatedListener({ currentImageUri }) {
                Log.d(TAG, "Current image is no longer valid, forcing switch")
                requestImageChange()
            }
        }

        private fun syncRendererSettings(key: String? = null) {
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

                if (key == null ||
                                key == WallpaperPreferences.KEY_IMAGE_CYCLE_ENABLED ||
                                key == WallpaperPreferences.KEY_IMAGE_CYCLE_INTERVAL
                ) {
                    transitionScheduler.updateEnabled(preferences.isImageCycleEnabled())
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
            rendererReady = true
            syncRendererSettings(null)

            val cached = cachedImageSet
            if (cached != null) {
                queueEvent { renderer?.setImage(cached) }
                return
            }

            scope.launch {
                val lastUri = folderRepository.getCurrentImageUri()
                val uriToLoad = if (lastUri != null && folderRepository.isImageUriValid(lastUri)) {
                    lastUri
                } else {
                    folderRepository.nextImageUri()
                }

                if (uriToLoad != null) loadImage(uriToLoad) else loadDefaultImage()
                transitionScheduler.start()
            }
        }

        private fun reBlurCurrentImage() {
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
            val uri = pendingImageUri ?: return
            pendingImageUri = null
            loadImage(uri)
        }

        private fun loadImage(uri: Uri) {
            imageLoadingJob?.cancel()

            imageLoadingJob = scope.launch {
                val blurAmount = preferences.getBlurAmount()
                val newSet = imageLoader.loadFromUri(uri, blurAmount)

                if (isActive && newSet != null) {
                    updateActiveImageSet(newSet, uri)
                } else {
                    newSet?.original?.recycle()
                    newSet?.blurred?.forEach { it.recycle() }
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
            val oldSet = cachedImageSet
            cachedImageSet = newSet
            currentImageUri = uri

            queueEvent {
                renderer?.setImage(newSet)
                scope.launch(Dispatchers.Default) {
                    oldSet?.recycleAll()
                }
            }
        }

        private fun requestImageChange() {
            if (renderer?.isAnimating() == true) {
                scope.launch {
                    val next = folderRepository.nextImageUri()
                    if (next != null) pendingImageUri = next
                }
                return
            }

            scope.launch {
                val nextUri = folderRepository.nextImageUri()
                if (nextUri != null) {
                    loadImage(nextUri)
                    transitionScheduler.resetTimer()
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
                GestureAction.NEXT_IMAGE -> requestImageChange()
                GestureAction.TOGGLE_BLUR -> {
                    sessionBlurEnabled = !sessionBlurEnabled
                    applyBlurState(immediate = false)
                    transitionScheduler.pauseForInteraction()
                }
                GestureAction.RANDOM_DUOTONE -> applyNextDuotone()
                GestureAction.FAVORITE -> addCurrentImageToFavorites()
                GestureAction.NONE -> {}
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
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
            surfaceDimensions = SurfaceDimensions(width, height)
            imageLoader.setScreenHeight(height)
        }

        private val shortcutReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.action) {
                            Actions.ACTION_NEXT_IMAGE -> requestImageChange()
                            Actions.ACTION_NEXT_DUOTONE -> applyNextDuotone()
                            Actions.ACTION_REFRESH_FOLDERS -> folderRepository.refreshAllFolders()
                            Actions.ACTION_ADD_TO_FAVORITES -> addCurrentImageToFavorites()
                            Actions.ACTION_SET_BLUR_PERCENT -> {
                                intent.let {
                                    Actions.BlurPercentAction.fromIntent(it)?.let { action ->
                                        preferences.setBlurAmount(action.percent ?: 0.5f)
                                    }
                                }
                            }
                            Actions.ACTION_ENABLE_BLUR -> {
                                sessionBlurEnabled = true
                                applyBlurState(false)
                            }
                            Actions.ACTION_SET_SPECIFIC_IMAGE -> {
                                val uri = intent.getParcelableExtra<Uri>(Actions.EXTRA_IMAGE_URI)
                                if (uri != null) {
                                    scope.launch {
                                        folderRepository.updateImageLastShown(uri)
                                        loadImage(uri)
                                    }
                                }
                            }
                        }
                    }
                }

        private val screenUnlockReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == Intent.ACTION_USER_PRESENT) {
                            if (preferences.isBlurOnScreenLockEnabled() &&
                                            !preferences.isUnblurOnUnlockEnabled()
                            ) {
                                sessionBlurEnabled = true
                            }

                            applyBlurState(immediate = false)

                            if (preferences.isCycleImageOnUnlockEnabled()) {
                                requestImageChange()
                            }
                        }
                    }
                }

        private fun applyNextDuotone() {
            val nextIndex = (preferences.getDuotonePresetIndex() + 1) % DUOTONE_PRESETS.size
            val p = DUOTONE_PRESETS[nextIndex]
            preferences.applyDuotonePreset(p.lightColor, p.darkColor, true, nextIndex)
        }

        private fun addCurrentImageToFavorites() {
            if (currentImageUri == null) return

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
            rendererReady = false
            renderer?.onSurfaceDestroyed()
            super.onSurfaceDestroyed(h)
        }

        override fun onDestroy() {
            touchEffectController.cleanup()
            preferences.unregisterListener(preferenceListener)
            try { unregisterReceiver(shortcutReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(screenUnlockReceiver) } catch (_: Exception) {}
            transitionScheduler.cancel()
            scope.cancel()
            blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            super.onDestroy()
            cachedImageSet?.recycleAll()
        }
    }
}