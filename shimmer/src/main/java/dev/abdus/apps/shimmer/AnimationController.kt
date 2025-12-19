package dev.abdus.apps.shimmer

import android.graphics.Color
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.createBitmap

class AnimationController(private var durationMillis: Int) {
    var currentRenderState: RenderState
        private set

    var targetRenderState: RenderState
        private set

    // Animators for individual properties
    val blurAmountAnimator = TickingFloatAnimator(durationMillis, DecelerateInterpolator())
    val dimAmountAnimator = TickingFloatAnimator(durationMillis, DecelerateInterpolator())
    val duotoneOpacityAnimator = TickingFloatAnimator(durationMillis, DecelerateInterpolator())
    val imageTransitionAnimator = TickingFloatAnimator(durationMillis, DecelerateInterpolator())
    // Exponential smoothing for parallax provides natural, adaptive motion for both dragging and flinging
    val parallaxOffsetAnimator = SmoothingFloatAnimator()

    // Duotone color animators (manual interpolation within tick)
    private var currentDuotoneLightColor: Int = 0
    private var targetDuotoneLightColor: Int = 0
    private var currentDuotoneDarkColor: Int = 0
    private var targetDuotoneDarkColor: Int = 0

    // Callback invoked when image-relevant animations complete
    var onImageAnimationComplete: (() -> Unit)? = null
    
    // Track animation state to detect transitions
    private var wasImageAnimatingLastFrame = false
    
    // Force a re-render for one tick after target state updates (handles non-animated property changes)
    private var forceUpdateOneFrame = false

    // Touch point management for chromatic aberration
    private val activeTouches = mutableListOf<TouchPoint>()

    companion object {
        private const val MAX_TOUCH_POINTS = 10
    }

    init {
        val defaultState = RenderState(
            imageSet = ImageSet(original = createBitmap(1, 1)), // Placeholder
            blurPercent = 0f,
            dimAmount = 0f,
            duotone = Duotone(
                lightColor = Color.WHITE, // From WallpaperPreferences.DEFAULT_DUOTONE_LIGHT
                darkColor = Color.BLACK, // From WallpaperPreferences.DEFAULT_DUOTONE_DARK
                opacity = 0f,
                blendMode = DuotoneBlendMode.NORMAL
            ),
            duotoneAlwaysOn = false,
            parallaxOffset = 0.5f,
            grain = GrainSettings(), // Film grain off by default
            chromaticAberration = ChromaticAberrationSettings(
                enabled = true, // From WallpaperPreferences.DEFAULT_CHROMATIC_ABERRATION_ENABLED
                intensity = 0.5f, // From WallpaperPreferences.DEFAULT_CHROMATIC_ABERRATION_INTENSITY
                fadeDurationMillis = 500L // From WallpaperPreferences.DEFAULT_CHROMATIC_ABERRATION_FADE_DURATION
            ),
        )
        currentRenderState = defaultState
        targetRenderState = defaultState
        parallaxOffsetAnimator.reset(0.5f)
    }

    fun setDuration(durationMillis: Int) {
        this.durationMillis = durationMillis
        blurAmountAnimator.durationMillis = durationMillis
        dimAmountAnimator.durationMillis = durationMillis
        duotoneOpacityAnimator.durationMillis = durationMillis
        imageTransitionAnimator.durationMillis = durationMillis
    }

    fun updateTargetState(newTarget: RenderState) {
        val oldTarget = targetRenderState
        targetRenderState = newTarget
        forceUpdateOneFrame = true

        // Blur amount
        if (oldTarget.blurPercent != newTarget.blurPercent) {
            val startBlurAmount = if (blurAmountAnimator.isRunning) {
                blurAmountAnimator.currentValue
            } else {
                currentRenderState.blurPercent
            }
            blurAmountAnimator.start(
                startValue = startBlurAmount,
                endValue = newTarget.blurPercent
            )
        }

        // Dim amount
        if (oldTarget.dimAmount != newTarget.dimAmount) {
            val startDimAmount = if (dimAmountAnimator.isRunning) {
                dimAmountAnimator.currentValue
            } else {
                currentRenderState.dimAmount
            }
            dimAmountAnimator.start(
                startValue = startDimAmount,
                endValue = newTarget.dimAmount
            )
        }

        // Parallax offset (exponential smoothing for natural motion)
        if (oldTarget.parallaxOffset != newTarget.parallaxOffset) {
            parallaxOffsetAnimator.setTarget(newTarget.parallaxOffset)
        }


        // Duotone properties (opacity and colors)
        if (oldTarget.duotone != newTarget.duotone) {
            val currentDuotoneState = currentRenderState.duotone

            // Determine the starting colors for interpolation
            val startLightColor = if (duotoneOpacityAnimator.isRunning) {
                // If an opacity animation is in progress, the current displayed color is interpolated
                interpolateColor(currentDuotoneLightColor, targetDuotoneLightColor, duotoneOpacityAnimator.progress)
            } else {
                // Otherwise, it's the current render state's color
                currentRenderState.duotone.lightColor
            }

            val startDarkColor = if (duotoneOpacityAnimator.isRunning) {
                // If an opacity animation is in progress, the current displayed color is interpolated
                interpolateColor(currentDuotoneDarkColor, targetDuotoneDarkColor, duotoneOpacityAnimator.progress)
            } else {
                // Otherwise, it's the current render state's color
                currentDuotoneState.darkColor
            }

            // Set the new starting and ending colors for the interpolation
            currentDuotoneLightColor = startLightColor
            currentDuotoneDarkColor = startDarkColor
            targetDuotoneLightColor = newTarget.duotone.lightColor
            targetDuotoneDarkColor = newTarget.duotone.darkColor

            // Start or restart the opacity animator.
            // If it was already running, this effectively "redirects" it to the new target opacity
            // from its current interpolated value. If not running, it starts from the current actual opacity.
            val startOpacity = if (duotoneOpacityAnimator.isRunning) {
                duotoneOpacityAnimator.currentValue
            } else {
                currentDuotoneState.opacity
            }

            duotoneOpacityAnimator.start(
                startValue = startOpacity,
                endValue = newTarget.duotone.opacity
            )
        }

        // Image transition: animate all image changes (fade in from black or crossfade)
        if (oldTarget.imageSet.original != newTarget.imageSet.original) {
            val startImageTransitionValue = if (imageTransitionAnimator.isRunning) {
                imageTransitionAnimator.progress
            } else {
                0f // Start fade-in from beginning (transparent)
            }
            imageTransitionAnimator.start(startValue = startImageTransitionValue, endValue = 1f)
        }

        // Chromatic aberration: clear touches if disabled
        if (oldTarget.chromaticAberration.enabled && !newTarget.chromaticAberration.enabled) {
            activeTouches.clear()
        }
    }

    // Public methods to immediately set state (for initial preference load)
    fun setRenderStateImmediately(newState: RenderState) {
        currentRenderState = newState
        targetRenderState = newState
        parallaxOffsetAnimator.reset(newState.parallaxOffset)
    }

    fun setDuotoneColorsImmediately(lightColor: Int, darkColor: Int) {
        currentDuotoneLightColor = lightColor
        targetDuotoneLightColor = lightColor
        currentDuotoneDarkColor = darkColor
        targetDuotoneDarkColor = darkColor
    }

    fun tick(): Boolean {
        // Update all animators
        val blurAnimating = blurAmountAnimator.tick()
        val dimAnimating = dimAmountAnimator.tick()
        val duotoneOpacityAnimating = duotoneOpacityAnimator.tick()
        val imageAnimating = imageTransitionAnimator.tick()
        val parallaxAnimating = parallaxOffsetAnimator.tick()
        
        // Update touch point animations
        updateTouchPointAnimations()

        // Linearly interpolate duotone colors if duotoneOpacityAnimator is running
        val animatedDuotoneLightColor = if (duotoneOpacityAnimating && duotoneOpacityAnimator.progress < 1f) {
            interpolateColor(currentDuotoneLightColor, targetDuotoneLightColor, duotoneOpacityAnimator.progress)
        } else {
            targetRenderState.duotone.lightColor
        }
        val animatedDuotoneDarkColor = if (duotoneOpacityAnimating && duotoneOpacityAnimator.progress < 1f) {
            interpolateColor(currentDuotoneDarkColor, targetDuotoneDarkColor, duotoneOpacityAnimator.progress)
        } else {
            targetRenderState.duotone.darkColor
        }

        // Build the current animated state
        currentRenderState = RenderState(
            imageSet = targetRenderState.imageSet,
            blurPercent = if (blurAnimating) blurAmountAnimator.currentValue else targetRenderState.blurPercent,
            dimAmount = if (dimAnimating) dimAmountAnimator.currentValue else targetRenderState.dimAmount,
            duotone = Duotone(
                lightColor = animatedDuotoneLightColor,
                darkColor = animatedDuotoneDarkColor,
                opacity = if (duotoneOpacityAnimating) duotoneOpacityAnimator.currentValue else targetRenderState.duotone.opacity,
                blendMode = targetRenderState.duotone.blendMode
            ),
            duotoneAlwaysOn = targetRenderState.duotoneAlwaysOn,
            parallaxOffset = parallaxOffsetAnimator.currentValue,
            grain = targetRenderState.grain,
            chromaticAberration = targetRenderState.chromaticAberration,
        )

        // Detect when image-relevant animations complete and invoke callback
        val isImageAnimating = blurAnimating || imageAnimating
        if (wasImageAnimatingLastFrame && !isImageAnimating) {
            onImageAnimationComplete?.invoke()
        }
        wasImageAnimatingLastFrame = isImageAnimating

        val touchAnimating = activeTouches.isNotEmpty()
        val keepAlive = forceUpdateOneFrame || blurAnimating || dimAnimating || duotoneOpacityAnimating || imageAnimating || parallaxAnimating || touchAnimating
        forceUpdateOneFrame = false
        return keepAlive
    }


    fun setTouchPoints(touches: List<TouchData>) {
        val chromaticAberration = currentRenderState.chromaticAberration
        if (!chromaticAberration.enabled || chromaticAberration.intensity <= 0) return

        for (touch in touches) {
            when (touch.action) {
                TouchAction.DOWN -> {
                    // Find existing non-released touch with this pointer ID
                    val existingTouch = activeTouches.find { it.id == touch.id && !it.isReleased }
                    if (existingTouch != null) {
                        // Already active, just update position
                        existingTouch.x = touch.x
                        existingTouch.y = touch.y
                    } else if (activeTouches.size < MAX_TOUCH_POINTS) {
                        // Create new touch point (even if there's a released touch with same ID)
                        val newTouch = createTouchPoint(touch)
                        activeTouches.add(newTouch)
                    }
                }
                TouchAction.MOVE -> {
                    activeTouches.find { it.id == touch.id && !it.isReleased }?.let {
                        it.x = touch.x
                        it.y = touch.y
                    }
                }
                TouchAction.UP -> {
                    activeTouches.find { it.id == touch.id && !it.isReleased }?.let {
                        releaseTouch(it)
                    }
                }
            }
        }
    }

    fun getTouchPointArrays(): Pair<FloatArray, FloatArray> {
        val touchCount = activeTouches.size.coerceAtMost(MAX_TOUCH_POINTS)
        val chromaticAberration = currentRenderState.chromaticAberration
        val intensity = if (chromaticAberration.enabled) chromaticAberration.intensity else 0f
        
        val touchPointsArray = FloatArray(touchCount * 3)
        val touchIntensitiesArray = FloatArray(touchCount)
        
        for (i in 0 until touchCount) {
            val touch = activeTouches[i]
            touchPointsArray[i * 3] = touch.x
            touchPointsArray[i * 3 + 1] = touch.y
            touchPointsArray[i * 3 + 2] = touch.radius
            touchIntensitiesArray[i] = touch.intensity * intensity
        }
        
        return touchPointsArray to touchIntensitiesArray
    }

    fun hasActiveTouches(): Boolean = activeTouches.isNotEmpty()

    private fun createTouchPoint(touch: TouchData): TouchPoint {
        val fadeMs = currentRenderState.chromaticAberration.fadeDurationMillis.toInt()
        val newTouch = TouchPoint(
            id = touch.id,
            x = touch.x,
            y = touch.y,
            radius = 0f,
            intensity = 1f,
            radiusAnimator = TickingFloatAnimator(4000, DecelerateInterpolator()),
            fadeAnimator = TickingFloatAnimator(fadeMs, DecelerateInterpolator())
        )
        newTouch.radiusAnimator.start(startValue = 0f, endValue = 1f)
        // Set fadeAnimator to not be running, but with intensity at 1.0
        newTouch.fadeAnimator.reset()
        newTouch.fadeAnimator.currentValue = 1f
        return newTouch
    }

    private fun releaseTouch(touchPoint: TouchPoint) {
        touchPoint.radiusAnimator.start(startValue = touchPoint.radius, endValue = 0f)
        touchPoint.fadeAnimator.start(startValue = touchPoint.intensity, endValue = 0f)
        touchPoint.isReleased = true
    }

    private fun updateTouchPointAnimations() {
        val touchesToRemove = mutableListOf<TouchPoint>()
        for (touchPoint in activeTouches) {
            // Tick animators (they handle their own running state internally)
            touchPoint.radiusAnimator.tick()
            touchPoint.radius = touchPoint.radiusAnimator.currentValue

            touchPoint.fadeAnimator.tick()
            touchPoint.intensity = touchPoint.fadeAnimator.currentValue

            // Remove touch points that have fully faded out AND finished their radius animation
            if (!touchPoint.radiusAnimator.isRunning && !touchPoint.fadeAnimator.isRunning &&
                touchPoint.radius <= 0.01f && touchPoint.intensity <= 0.01f) {
                touchesToRemove.add(touchPoint)
            }
        }
        // Remove completed touch points
        activeTouches.removeAll(touchesToRemove)
    }

    // Helper for color interpolation (copied from ShimmerRenderer)
    private fun interpolateColor(from: Int, to: Int, t: Float): Int {
        val fromR = Color.red(from)
        val fromG = Color.green(from)
        val fromB = Color.blue(from)
        val toR = Color.red(to)
        val toG = Color.green(to)
        val toB = Color.blue(to)

        val r = (fromR + (toR - fromR) * t).toInt().coerceIn(0, 255)
        val g = (fromG + (toG - fromG) * t).toInt().coerceIn(0, 255)
        val b = (fromB + (toB - fromB) * t).toInt().coerceIn(0, 255)

        return Color.rgb(r, g, b)
    }
}