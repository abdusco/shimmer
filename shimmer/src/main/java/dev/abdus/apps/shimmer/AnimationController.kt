package dev.abdus.apps.shimmer

import android.graphics.Color
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.createBitmap

class AnimationController(private var durationMillis: Int = 1000) {
    var targetRenderState: RenderState
        private set

    // Animated "current" values, updated in place each tick. Kept as primitives rather
    // than a rebuilt RenderState so a continuously animating frame allocates nothing.
    var currentBlurPercent = 0f
        private set
    var currentDimAmount = 0f
        private set
    var currentDuotoneLightColor = 0
        private set
    var currentDuotoneDarkColor = 0
        private set
    var currentDuotoneOpacity = 0f
        private set

    // Animators for individual properties
    val blurAmountAnimator = TickingFloatAnimator(durationMillis, DecelerateInterpolator())
    val dimAmountAnimator = TickingFloatAnimator(durationMillis, DecelerateInterpolator())
    val duotoneOpacityAnimator = TickingFloatAnimator(durationMillis, DecelerateInterpolator())
    val imageTransitionAnimator = TickingFloatAnimator(durationMillis, DecelerateInterpolator())

    // Duotone color animation endpoints (manual interpolation within tick)
    private var fromDuotoneLightColor: Int = 0
    private var toDuotoneLightColor: Int = 0
    private var fromDuotoneDarkColor: Int = 0
    private var toDuotoneDarkColor: Int = 0

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
        targetRenderState = defaultState
        applyStateImmediately(defaultState)
    }

    private fun applyStateImmediately(state: RenderState) {
        currentBlurPercent = state.blurPercent
        currentDimAmount = state.dimAmount
        currentDuotoneLightColor = state.duotone.lightColor
        currentDuotoneDarkColor = state.duotone.darkColor
        currentDuotoneOpacity = state.duotone.opacity
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
                currentBlurPercent
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
                currentDimAmount
            }
            dimAmountAnimator.start(
                startValue = startDimAmount,
                endValue = newTarget.dimAmount
            )
        }

        // Duotone properties (opacity and colors)
        if (oldTarget.duotone != newTarget.duotone) {
            // The currently displayed colors become the start of the new interpolation.
            fromDuotoneLightColor = currentDuotoneLightColor
            fromDuotoneDarkColor = currentDuotoneDarkColor
            toDuotoneLightColor = newTarget.duotone.lightColor
            toDuotoneDarkColor = newTarget.duotone.darkColor

            // Start or restart the opacity animator.
            // If it was already running, this effectively "redirects" it to the new target opacity
            // from its current interpolated value. If not running, it starts from the current actual opacity.
            val startOpacity = if (duotoneOpacityAnimator.isRunning) {
                duotoneOpacityAnimator.currentValue
            } else {
                currentDuotoneOpacity
            }

            duotoneOpacityAnimator.start(
                startValue = startOpacity,
                endValue = newTarget.duotone.opacity
            )
        }

        // Image transition: animate all image changes (fade in from black or crossfade)
        if (oldTarget.imageSet != newTarget.imageSet) { // Object reference check
            // FIX: If the ImageSet object actually changed (new blur levels generated),
            // we MUST start from 0f because currentImage is a brand new texture object.
            // If we start from 'progress' (e.g. 0.9), the new blur snaps in nearly instantly.
            imageTransitionAnimator.start(startValue = 0f, endValue = 1f)
        }

    }

    // Public methods to immediately set state (for initial preference load)
    fun setRenderStateImmediately(newState: RenderState) {
        targetRenderState = newState
        applyStateImmediately(newState)
    }

    fun setDuotoneColorsImmediately(lightColor: Int, darkColor: Int) {
        fromDuotoneLightColor = lightColor
        toDuotoneLightColor = lightColor
        currentDuotoneLightColor = lightColor
        fromDuotoneDarkColor = darkColor
        toDuotoneDarkColor = darkColor
        currentDuotoneDarkColor = darkColor
    }

    fun tick(): Boolean {
        // Update all animators
        val blurAnimating = blurAmountAnimator.tick()
        val dimAnimating = dimAmountAnimator.tick()
        val duotoneOpacityAnimating = duotoneOpacityAnimator.tick()
        val imageAnimating = imageTransitionAnimator.tick()

        // Update the animated values in place
        val interpolatingDuotone = duotoneOpacityAnimating && duotoneOpacityAnimator.progress < 1f
        currentDuotoneLightColor = if (interpolatingDuotone) {
            interpolateColor(fromDuotoneLightColor, toDuotoneLightColor, duotoneOpacityAnimator.progress)
        } else {
            targetRenderState.duotone.lightColor
        }
        currentDuotoneDarkColor = if (interpolatingDuotone) {
            interpolateColor(fromDuotoneDarkColor, toDuotoneDarkColor, duotoneOpacityAnimator.progress)
        } else {
            targetRenderState.duotone.darkColor
        }
        currentDuotoneOpacity =
            if (duotoneOpacityAnimating) duotoneOpacityAnimator.currentValue
            else targetRenderState.duotone.opacity
        currentBlurPercent =
            if (blurAnimating) blurAmountAnimator.currentValue else targetRenderState.blurPercent
        currentDimAmount =
            if (dimAnimating) dimAmountAnimator.currentValue else targetRenderState.dimAmount

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
