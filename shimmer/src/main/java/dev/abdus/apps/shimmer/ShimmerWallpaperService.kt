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
import android.widget.Toast
import net.rbgrn.android.glwallpaperservice.GLWallpaperService
import java.util.concurrent.Executors
import kotlin.reflect.KClass

class ShimmerWallpaperService : GLWallpaperService() {
    companion object {
        const val TAG = "ShimmerWallpaperService"
    }

    override fun onCreateEngine(): Engine {
        return ShimmerWallpaperEngine()
    }

    inner class ShimmerWallpaperEngine : GLEngine(), ShimmerRenderer.Callbacks {

        private var renderer: ShimmerRenderer? = null
        @Volatile
        private var surfaceAvailable = false
        @Volatile
        private var engineVisible = true
        private val imageLoadExecutor = Executors.newSingleThreadScheduledExecutor()
        private val folderRepository = ImageFolderRepository(this@ShimmerWallpaperService)
        private val transitionScheduler =
            ImageTransitionScheduler(imageLoadExecutor) { handleScheduledAdvance() }
        private val imageLoader = ImageLoader(
            contentResolver = contentResolver,
            resources = resources,
            preferences = WallpaperPreferences.create(this@ShimmerWallpaperService)
        )

        private val tapGestureDetector = TapGestureDetector(this@ShimmerWallpaperService)
        private val preferences = WallpaperPreferences.create(this@ShimmerWallpaperService)

        private val commandQueue = RendererCommandQueue()

        // Image change state management (accessed only on folderScheduler thread)
        private var pendingImageUri: Uri? = null

        // Explicit session state for blur.
        // Initialized from preferences, but updated by transient actions (user toggle).
        // Survives surface destruction but not service restart.
        private var sessionBlurEnabled = preferences.getBlurAmount() > 0f

        // Track current image URI for re-blurring when blur amount changes
        private var currentImageUri: Uri? = null

        // Track initial load to prevent double-blur
        private var isInitialLoad = true

        // Blur timeout preferences/state
        @Volatile
        private var blurTimeoutEnabled = preferences.isBlurTimeoutEnabled()
        @Volatile
        private var blurTimeoutMillis = preferences.getBlurTimeoutMillis()
        private val blurTimeoutHandler = Handler(Looper.getMainLooper())
        private val blurTimeoutRunnable = Runnable {
            if (!blurTimeoutEnabled) return@Runnable
            if (!engineVisible) return@Runnable
            Log.d(TAG, "Blur timeout elapsed; applying blur")
            enableBlur(immediate = false, replayable = false)
        }

        private var rendererReady = false

        // Surface dimensions for touch coordinate conversion
        @Volatile
        private var surfaceWidthPx: Int = 0
        @Volatile
        private var surfaceHeightPx: Int = 0

        /**
         * Resolves the effective blur state based on priority:
         * 1. Lock screen override (Highest priority)
         * 2. Session state (User preference/toggle)
         */
        private fun resolveAndApplyBlurState(reason: String) {
            val blurOnLock = preferences.isBlurOnScreenLockEnabled()
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val isOnLockScreen = keyguardManager?.isKeyguardLocked ?: false
            val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val isScreenOn = powerManager?.isInteractive ?: true

            // Priority 1: Lock Screen Override
            // If "Blur on Lock" is enabled, force blur when screen is off or on lock screen
            val shouldForceBlur = blurOnLock && (isOnLockScreen || !isScreenOn)

            val effectiveBlurEnabled = if (shouldForceBlur) {
                true
            } else {
                sessionBlurEnabled
            }

            // Determine if transition should be immediate (e.g., when locking)
            val immediate = shouldForceBlur && !isScreenOn

            Log.d(TAG, "resolveBlurState($reason): force=$shouldForceBlur (locked=$isOnLockScreen, screenOn=$isScreenOn), session=$sessionBlurEnabled -> effective=$effectiveBlurEnabled")

            enqueueCommand(
                RendererCommand.ApplyBlur(enabled = effectiveBlurEnabled, immediate = immediate),
                allowWhenSurfaceUnavailable = true,
                allowWhenInvisible = true,
                // We do NOT want to replay this specific command state automatically from the queue,
                // because we re-resolve it explicitly on surface creation/ready events.
                replayable = false
            )
        }

        private fun isRenderable(): Boolean {
            val r = renderer
            return surfaceAvailable && engineVisible && rendererReady && (r?.isSurfaceReady() == true)
        }

        private fun requestRenderIfRenderable(reason: String) {
            if (isRenderable()) {
                super.requestRender()
            } else {
                Log.d(TAG, "requestRender skipped; renderable=false (reason=$reason)")
            }
        }

        override fun onRendererReady() {
            Log.d(TAG, "onRendererReady called")
            rendererReady = true
            Log.d(TAG, "onRendererReady applying preferences")
            applyPreferences()
            Log.d(TAG, "onRendererReady enqueueing replay of latest commands")
            commandQueue.enqueueReplayOfLatest()
            Log.d(TAG, "onRendererReady trying to drain commands")
            tryDrainCommands()

            isInitialLoad = false
            startBlurTimeoutIfUnblurred("rendererReady")

            // Ensure the correct blur state is applied immediately when renderer is ready
            resolveAndApplyBlurState("rendererReady")

            requestRenderIfRenderable("rendererReady")
        }

        override fun onReadyForNextImage() {
            Log.d(TAG, "onReadyForNextImage called on ${Thread.currentThread().name}")
            startBlurTimeoutIfUnblurred("onReadyForNextImage")
            imageLoadExecutor.execute {
                pendingImageUri?.let { uri ->
                    pendingImageUri = null
                    loadImage(uri)
                }
            }
        }

        override fun onSurfaceDimensionsChanged(width: Int, height: Int) {
            imageLoader.setScreenHeight(height)
            surfaceWidthPx = width
            surfaceHeightPx = height
            Log.d(TAG, "onSurfaceDimensionsChanged: ${width}x${height}")
        }

        private fun enqueueCommand(
            command: RendererCommand,
            allowWhenSurfaceUnavailable: Boolean = false,
            allowWhenInvisible: Boolean = false,
            replayable: Boolean = true,
        ) {
            if (!allowWhenSurfaceUnavailable && !surfaceAvailable) return
            if (!allowWhenInvisible && !engineVisible) return
            commandQueue.enqueue(command, replayable)
            tryDrainCommands()
        }

        private fun tryDrainCommands() {
            val r = renderer ?: return
            if (!surfaceAvailable || !rendererReady || !r.isSurfaceReady()) {
                return
            }
            if (!isRenderable()) {
                return
            }

            commandQueue.drain { command ->
                queueEvent {
                    try {
                        applyCommand(r, command)
                    } catch (e: Exception) {
                        Log.e(TAG, "applyCommand failed, deferring until surface is ready again", e)
                        // Mark renderer as not ready so we stop draining until the next surface creation.
                        rendererReady = false
                    }
                }
            }

            if (engineVisible) {
                super.requestRender()
            }
        }

        private fun applyCommand(renderer: ShimmerRenderer, command: RendererCommand) {
            when (command) {
                is RendererCommand.ApplyBlur -> {
                    renderer.enableBlur(command.enabled, command.immediate)
                    updateBlurState(command.enabled, "applyCommand.ApplyBlur")
                }

                is RendererCommand.SetImage -> {
                    renderer.setImage(command.imageSet)
                    markUserInteraction("applyCommand.SetImage")
                }

                is RendererCommand.SetDim -> renderer.setUserDimAmount(command.amount)
                is RendererCommand.SetDuotone -> renderer.setDuotoneSettings(
                    enabled = command.enabled,
                    alwaysOn = command.alwaysOn,
                    duotone = command.duotone
                )

                is RendererCommand.SetGrain -> {
                    renderer.setGrainSettings(
                        enabled = command.enabled,
                        amount = command.amount,
                        scale = command.scale
                    )
                }

                is RendererCommand.SetParallax -> {
                    renderer.setParallaxOffset(command.offset)
                    markUserInteraction("applyCommand.SetParallax")
                }

                is RendererCommand.SetEffectDuration -> renderer.setEffectTransitionDuration(command.durationMs)
                is RendererCommand.SetTouchPoints -> renderer.setTouchPoints(command.touches)
                is RendererCommand.SetChromaticAberration -> renderer.setChromaticAberrationSettings(
                    command.enabled,
                    command.intensity,
                    command.fadeDurationMillis
                )
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
                    Actions.ACTION_REFRESH_FOLDERS -> refreshFolders()
                }
            }
        }

        // BroadcastReceiver for handling screen unlock events
        private val screenUnlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    // Check if we should keep the blur from the lock screen (i.e. if user disabled unblur on unlock)
                    // If blurOnLock is enabled AND unblurOnUnlock is DISABLED, we explicitly latch the session state to blurred.
                    if (preferences.isBlurOnScreenLockEnabled() && !preferences.isUnblurOnUnlockEnabled()) {
                        Log.d(TAG, "Screen unlocked, Keeping blur (UnblurOnUnlock=false)")
                        sessionBlurEnabled = true
                    }

                    // Re-resolve blur state on unlock to restore session state if needed
                    resolveAndApplyBlurState("screenUnlocked")

                    if (preferences.isChangeImageOnUnlockEnabled()) {
                        Log.d(TAG, "Screen unlocked, changing image")
                        advanceToNextImage()
                    }
                }
            }
        }

        private val preferenceHandlers: Map<String, () -> Unit> = mapOf(
            WallpaperPreferences.KEY_BLUR_AMOUNT to ::onBlurPreferenceChanged,
            WallpaperPreferences.KEY_DIM_AMOUNT to ::applyDimPreference,
            WallpaperPreferences.KEY_DUOTONE_SETTINGS to ::applyDuotoneSettingsPreference,
            WallpaperPreferences.KEY_IMAGE_FOLDERS to ::applyImageFolderPreference,
            WallpaperPreferences.KEY_TRANSITION_ENABLED to ::applyTransitionEnabledPreference,
            WallpaperPreferences.KEY_TRANSITION_INTERVAL to ::applyTransitionIntervalPreference,
            WallpaperPreferences.KEY_EFFECT_TRANSITION_DURATION to ::applyEffectTransitionDurationPreference,
            WallpaperPreferences.KEY_GRAIN_SETTINGS to ::applyGrainPreference,
            WallpaperPreferences.KEY_CHROMATIC_ABERRATION_SETTINGS to ::applyChromaticAberrationPreference,
            WallpaperPreferences.KEY_BLUR_TIMEOUT_ENABLED to ::applyBlurTimeoutPreference,
            WallpaperPreferences.KEY_BLUR_TIMEOUT_MILLIS to ::applyBlurTimeoutPreference,
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

            // Register receiver for screen unlock events
            val screenUnlockFilter = IntentFilter(Intent.ACTION_USER_PRESENT)
            this@ShimmerWallpaperService.registerReceiver(screenUnlockReceiver, screenUnlockFilter)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "onSurfaceCreated called")
            super.onSurfaceCreated(holder)
            surfaceAvailable = true
            commandQueue.enqueueReplayOfLatest()
            tryDrainCommands()

            requestRenderIfRenderable("surfaceCreated")
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "onSurfaceDestroyed called")
            surfaceAvailable = false
            rendererReady = false
            renderer?.onSurfaceDestroyed()
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

            val blurOnLock = preferences.isBlurOnScreenLockEnabled()
            Log.d(TAG, "  isScreenOn=$isScreenOn, isOnLockScreen=$isOnLockScreen, blurOnLock=$blurOnLock")

            // Re-resolve blur state whenever visibility changes (covers lock/unlock/screen off)
            resolveAndApplyBlurState("visibilityChanged")

            markUserInteraction("visibilityChanged")

            if (engineVisible) {
                tapGestureDetector.reset()
                if (surfaceAvailable) {
                    requestRenderIfRenderable("visibilityChanged")
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
            try {
                this@ShimmerWallpaperService.unregisterReceiver(screenUnlockReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered, ignore
            }
            transitionScheduler.cancel()
            folderRepository.cleanup()
            imageLoadExecutor.shutdownNow()
            blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            super.onDestroy()
        }

        override fun onTouchEvent(event: MotionEvent) {
            markUserInteraction("onTouchEvent")
            
            // ALWAYS process chromatic aberration touches immediately for responsive feedback
            // This runs independently of gesture detection
            processTouchEventForChromaticAberration(event)
            
            // Then check for tap gestures (which may have delays for multi-tap detection)
            when (tapGestureDetector.onTouchEvent(event)) {
                TapEvent.TWO_FINGER_DOUBLE_TAP -> {
                    Log.d(TAG, "TapEvent.TWO_FINGER_DOUBLE_TAP - advancing to next image")
                    advanceToNextImage()
                }

                TapEvent.TRIPLE_TAP -> {
                    Log.d(TAG, "TapEvent.TRIPLE_TAP - toggling blur")
                    // Toggle session state
                    sessionBlurEnabled = !sessionBlurEnabled
                    resolveAndApplyBlurState("tripleTap")
                }

                TapEvent.NONE -> {
                    // No gesture detected, continue with default handling
                }
            }
            
            super.onTouchEvent(event)
        }

        private fun processTouchEventForChromaticAberration(event: MotionEvent) {
            if (surfaceWidthPx == 0 || surfaceHeightPx == 0) {
                return // Surface dimensions not available yet
            }

            val touches = mutableListOf<TouchData>()
            val action = event.actionMasked
            val actionIndex = event.actionIndex
            
            // Handle ACTION_UP and ACTION_POINTER_UP specially because the pointer
            // that was released is still accessible via actionIndex
            when (action) {
                MotionEvent.ACTION_UP -> {
                    // Single pointer release - get the pointer that was just released
                    val pointerId = event.getPointerId(0)
                    val x = event.getX(0) / surfaceWidthPx
                    val y = 1f - (event.getY(0) / surfaceHeightPx)  // Flip Y: Android is top-down, GL is bottom-up
                    touches.add(TouchData(pointerId, x, y, TouchAction.UP))
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Multiple pointer release - get the specific pointer that was released
                    val pointerId = event.getPointerId(actionIndex)
                    val x = event.getX(actionIndex) / surfaceWidthPx
                    val y = 1f - (event.getY(actionIndex) / surfaceHeightPx)  // Flip Y
                    touches.add(TouchData(pointerId, x, y, TouchAction.UP))
                    
                    // Also send updates for all other still-active pointers
                    for (i in 0 until event.pointerCount) {
                        if (i != actionIndex) {
                            val pid = event.getPointerId(i)
                            val px = event.getX(i) / surfaceWidthPx
                            val py = 1f - (event.getY(i) / surfaceHeightPx)  // Flip Y
                            touches.add(TouchData(pid, px, py, TouchAction.MOVE))
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    // All pointers cancelled - mark all as UP
                    for (i in 0 until event.pointerCount) {
                        val pointerId = event.getPointerId(i)
                        val x = event.getX(i) / surfaceWidthPx
                        val y = 1f - (event.getY(i) / surfaceHeightPx)  // Flip Y
                        touches.add(TouchData(pointerId, x, y, TouchAction.UP))
                    }
                }
                else -> {
                    // Process all active pointers for DOWN and MOVE actions
                    for (i in 0 until event.pointerCount) {
                        val pointerId = event.getPointerId(i)
                        val x = event.getX(i) / surfaceWidthPx
                        val y = 1f - (event.getY(i) / surfaceHeightPx)  // Flip Y: Android is top-down, GL is bottom-up
                        
                        val touchAction = when {
                            action == MotionEvent.ACTION_DOWN && i == 0 -> TouchAction.DOWN
                            action == MotionEvent.ACTION_POINTER_DOWN && i == actionIndex -> TouchAction.DOWN
                            else -> TouchAction.MOVE
                        }
                        
                        touches.add(TouchData(pointerId, x, y, touchAction))
                    }
                }
            }

            if (touches.isNotEmpty()) {
                enqueueCommand(
                    RendererCommand.SetTouchPoints(touches),
                    allowWhenSurfaceUnavailable = false,
                    allowWhenInvisible = false,
                    replayable = false
                )
            }
        }
        
        private fun actionToString(action: Int): String = when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "UNKNOWN($action)"
        }

        override fun requestRender() {
            requestRenderIfRenderable("explicitRequest")
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
            enqueueCommand(RendererCommand.SetParallax(xOffset))
            markUserInteraction("offsetsChanged")
        }

        private fun loadDefaultImage() {
            Log.d(TAG, "loadDefaultImage: Loading default wallpaper image")
            // Called on imageLoadExecutor thread
            if (folderRepository.hasFolders()) {
                return
            }

            val imageSet = imageLoader.loadDefault(blurAmount = preferences.getBlurAmount())
            if (imageSet != null) {
                // Mark that we're using the default image (no URI means default)
                currentImageUri = null
                enqueueCommand(
                    RendererCommand.SetImage(imageSet),
                    allowWhenSurfaceUnavailable = true,
                    allowWhenInvisible = true
                )
            } else {
                Log.e(TAG, "loadDefaultImage: Failed to load default image")
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
            applyGrainPreference()
            applyChromaticAberrationPreference()
            applyTransitionEnabledPreference()
            applyTransitionIntervalPreference()
            applyEffectTransitionDurationPreference()
            applyBlurTimeoutPreference()
        }

        private fun applyBlurPreference() {
            // Update session state to match new preference
            val blurAmount = preferences.getBlurAmount()
            sessionBlurEnabled = blurAmount > 0f
            resolveAndApplyBlurState("preferenceChanged")
        }

        private fun applyBlurTimeoutPreference() {
            blurTimeoutEnabled = preferences.isBlurTimeoutEnabled()
            blurTimeoutMillis = preferences.getBlurTimeoutMillis()
            if (!blurTimeoutEnabled) {
                blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            } else {
                startBlurTimeoutIfUnblurred("preferenceChange")
            }
        }

        private fun updateBlurState(enabled: Boolean, reason: String) {
            blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            if (!enabled) {
                scheduleBlurTimeout(reason)
            }
        }

        private fun scheduleBlurTimeout(reason: String) {
            if (!blurTimeoutEnabled) return
            if (!engineVisible) return

            blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            blurTimeoutHandler.postDelayed(blurTimeoutRunnable, blurTimeoutMillis)
            Log.d(TAG, "Scheduled blur timeout in ${blurTimeoutMillis}ms (reason=$reason)")
        }

        private fun startBlurTimeoutIfUnblurred(reason: String) {
            renderer?.let {
                if (!it.isBlurred()) {
                    scheduleBlurTimeout(reason)
                }
            }
        }

        private fun markUserInteraction(reason: String) {
            blurTimeoutHandler.removeCallbacks(blurTimeoutRunnable)
            scheduleBlurTimeout(reason)
        }

        private fun enableBlur(immediate: Boolean = false, replayable: Boolean = true) {
            sessionBlurEnabled = true
            resolveAndApplyBlurState("enableBlur")
        }

        private fun onBlurPreferenceChanged() {
            reBlurCurrentImage()
        }

        private fun applyDimPreference() {
            val dimAmount = preferences.getDimAmount()
            enqueueCommand(RendererCommand.SetDim(dimAmount))
        }

        private fun applyDuotoneSettingsPreference() {
            val settings = preferences.getDuotoneSettings()
            enqueueCommand(
                RendererCommand.SetDuotone(
                    enabled = settings.enabled,
                    alwaysOn = settings.alwaysOn,
                    duotone = Duotone(
                        lightColor = settings.lightColor,
                        darkColor = settings.darkColor,
                        opacity = if (settings.enabled) 1f else 0f
                    )
                )
            )
        }

        private fun applyGrainPreference() {
            val settings = preferences.getGrainSettings()
            enqueueCommand(
                RendererCommand.SetGrain(
                    enabled = settings.enabled,
                    amount = settings.amount,
                    scale = settings.scale
                )
            )
        }

        private fun applyChromaticAberrationPreference() {
            val settings = preferences.getChromaticAberrationSettings()
            enqueueCommand(
                RendererCommand.SetChromaticAberration(
                    enabled = settings.enabled,
                    intensity = settings.intensity,
                    fadeDurationMillis = settings.fadeDurationMillis.toInt()
                )
            )
        }

        private fun applyEffectTransitionDurationPreference() {
            val duration = preferences.getEffectTransitionDurationMillis()
            enqueueCommand(RendererCommand.SetEffectDuration(duration))
        }

        private fun applyImageFolderPreference() {
            val folders = preferences.getImageFolders()
            val folderUris = folders.map { it.uri }
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
                // Check if current image is still valid in the new folder set
                val currentUri = currentImageUri
                if (currentUri != null && folderRepository.isImageUriValid(currentUri)) {
                    Log.d(TAG, "setImageFolders: Current image $currentUri is still valid, keeping it")
                    transitionScheduler.start()
                    return@execute
                }

                // Current image is invalid or null, load a new one
                Log.d(TAG, "setImageFolders: Current image invalid or null, loading new image")
                if (loadLastImage()) {
                    transitionScheduler.start()
                } else {
                    Log.d(TAG, "setImageFolders: loading default image")
                    loadDefaultImage()
                }
            }
        }

        private fun loadLastImage(): Boolean {
            Log.d(TAG, "loadLastImage: Attempting to load last image")

            // Try to load last image from preferences
            val lastImageUri = preferences.getLastImageUri()
            var imageSet = imageLoader.loadLast(blurAmount = preferences.getBlurAmount())

            // If no last image, try to get next image from folder repository
            if (imageSet == null) {
                val nextUri = folderRepository.nextImageUri()
                if (nextUri != null) {
                    Log.d(TAG, "loadLastImage: No last image, using next from folder: $nextUri")
                    imageSet = imageLoader.loadFromUri(uri = nextUri, blurAmount = preferences.getBlurAmount())
                    if (imageSet != null) {
                        currentImageUri = nextUri
                        preferences.setLastImageUri(nextUri.toString())
                    }
                }
            } else if (lastImageUri != null) {
                // Successfully loaded last image - track its URI for re-blurring
                currentImageUri = lastImageUri
            }

            if (imageSet != null) {
                enqueueCommand(
                    RendererCommand.SetImage(imageSet),
                    allowWhenSurfaceUnavailable = true,
                    allowWhenInvisible = true
                )
                return true
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

        fun refreshFolders() {
            Log.d(TAG, "refreshFolders: Refreshing all folders")
            folderRepository.refreshAllFolders()
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
            // Called on imageLoadExecutor thread to load a specific image
            val imageSet = imageLoader.loadFromUri(uri = uri, blurAmount = preferences.getBlurAmount())
            if (imageSet != null) {
                Log.d(TAG, "loadImage: imageSet prepared, calling setImage with allowWhenSurfaceUnavailable = true")
                currentImageUri = uri // Track for re-blurring
                enqueueCommand(
                    RendererCommand.SetImage(imageSet),
                    allowWhenSurfaceUnavailable = true,
                    allowWhenInvisible = true
                )
                preferences.setLastImageUri(uri.toString())
                Log.d(TAG, "loadImage: COMPLETE")
            } else {
                Log.d(TAG, "loadImage: Failed to load image")
            }
        }

        private fun handleScheduledAdvance() {
            requestImageChange()
        }

        private fun reBlurCurrentImage() {
            Log.d(TAG, "reBlurCurrentImage: Re-blurring current image")
            val uri = currentImageUri
            val imageSet = if (uri != null) {
                // Reload the current image with new blur amount
                imageLoader.loadFromUri(uri = uri, blurAmount = preferences.getBlurAmount())
            } else {
                // No URI means we're using the default image - reload it
                Log.d(TAG, "reBlurCurrentImage: No URI, reloading default image")
                imageLoader.loadDefault(blurAmount = preferences.getBlurAmount())
            }
            
            if (imageSet != null) {
                enqueueCommand(
                    RendererCommand.SetImage(imageSet),
                    allowWhenSurfaceUnavailable = true,
                    allowWhenInvisible = true
                )
            }
        }

    }
}

private sealed interface RendererCommand {
    data class ApplyBlur(val enabled: Boolean, val immediate: Boolean = false) : RendererCommand
    data class SetImage(val imageSet: ImageSet) : RendererCommand
    data class SetDim(val amount: Float) : RendererCommand
    data class SetDuotone(
        val enabled: Boolean,
        val alwaysOn: Boolean,
        val duotone: Duotone,
    ) : RendererCommand

    data class SetGrain(
        val enabled: Boolean,
        val amount: Float,
        val scale: Float,
    ) : RendererCommand

    data class SetParallax(val offset: Float) : RendererCommand
    data class SetEffectDuration(val durationMs: Long) : RendererCommand
    data class SetTouchPoints(val touches: List<TouchData>) : RendererCommand
    data class SetChromaticAberration(
        val enabled: Boolean,
        val intensity: Float,
        val fadeDurationMillis: Int
    ) : RendererCommand
}

private class RendererCommandQueue {
    private val replaceableTypes: Set<KClass<out RendererCommand>> = setOf(
        RendererCommand.ApplyBlur::class,
        RendererCommand.SetImage::class,
        RendererCommand.SetDim::class,
        RendererCommand.SetDuotone::class,
        RendererCommand.SetGrain::class,
        RendererCommand.SetParallax::class,
        RendererCommand.SetEffectDuration::class,
        RendererCommand.SetChromaticAberration::class
    )

    private val queue = mutableListOf<RendererCommand>()
    private val latestReplaceable = LinkedHashMap<KClass<out RendererCommand>, RendererCommand>()

    @Synchronized
    fun enqueue(command: RendererCommand, replayable: Boolean) {
        val type = command::class
        if (type in replaceableTypes) {
            queue.removeAll { it::class == type }
            // replayable=false is used for transient, context-driven commands (e.g., blur on lock)
            // so they are not re-applied on renderer/surface recreation. Preference-driven commands remain
            // replayable to restore state after GL context loss.
            if (replayable) {
                latestReplaceable[type] = command
            }
        }
        queue.add(command)
    }

    @Synchronized
    fun enqueueReplayOfLatest() {
        latestReplaceable.values.forEach { command ->
            queue.removeAll { it::class == command::class }
            queue.add(command)
        }
    }

    @Synchronized
    fun drain(consumer: (RendererCommand) -> Unit) {
        if (queue.isEmpty()) return
        val snapshot = queue.toList()
        queue.clear()
        snapshot.forEach(consumer)
    }
}
