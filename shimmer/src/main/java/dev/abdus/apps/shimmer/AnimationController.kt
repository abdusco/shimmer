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

    init {
        val defaultState = RenderState(
            imageSet = ImageSet(original = createBitmap(1, 1)), // Placeholder
            blurPercent = 0f,
            dimAmount = 0f,
            duotone = Duotone(
                lightColor = Color.WHITE, // From WallpaperPreferences.DEFAULT_DUOTONE_LIGHT
                darkColor = Color.BLACK, // From WallpaperPreferences.DEFAULT_DUOTONE_DARK
                opacity = 0f
            ),
            duotoneAlwaysOn = false,
            parallaxOffset = 0.5f,
            grain = GrainSettings(), // Film grain off by default
        )
        currentRenderState = defaultState
        targetRenderState = defaultState
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
    }

    // Public methods to immediately set state (for initial preference load)
    fun setRenderStateImmediately(newState: RenderState) {
        currentRenderState = newState
        targetRenderState = newState
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
                opacity = if (duotoneOpacityAnimating) duotoneOpacityAnimator.currentValue else targetRenderState.duotone.opacity
            ),
            duotoneAlwaysOn = targetRenderState.duotoneAlwaysOn,
            parallaxOffset = targetRenderState.parallaxOffset, // Parallax is not animated, it snaps
            grain = targetRenderState.grain,
        )

        // Detect when image-relevant animations complete and invoke callback
        val isImageAnimating = blurAnimating || imageAnimating
        if (wasImageAnimatingLastFrame && !isImageAnimating) {
            onImageAnimationComplete?.invoke()
        }
        wasImageAnimatingLastFrame = isImageAnimating

        val keepAlive = forceUpdateOneFrame || blurAnimating || dimAnimating || duotoneOpacityAnimating || imageAnimating
        forceUpdateOneFrame = false
        return keepAlive
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