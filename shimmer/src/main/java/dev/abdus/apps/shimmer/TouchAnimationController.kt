package dev.abdus.apps.shimmer

import android.view.animation.DecelerateInterpolator

class TouchAnimationController {
    private val activeTouches = mutableListOf<TouchPoint>()
    private var chromaticSettings = ChromaticAberrationSettings(
        enabled = false,
        intensity = 0f,
        fadeDurationMillis = 0L
    )

    companion object {
        private const val MAX_TOUCH_POINTS = 10
    }

    fun setSettings(settings: ChromaticAberrationSettings) {
        chromaticSettings = settings
        if (!settings.enabled || settings.intensity <= 0f) {
            activeTouches.clear()
            return
        }

        val fadeMs = settings.fadeDurationMillis.toInt()
        activeTouches.forEach { touch ->
            touch.fadeAnimator.durationMillis = fadeMs
        }
    }

    fun setActiveTouches(touches: List<TouchData>) {
        if (!chromaticSettings.enabled || chromaticSettings.intensity <= 0f) return
        if (touches.isEmpty()) {
            activeTouches.filter { !it.isReleased }.forEach { releaseTouch(it) }
            return
        }

        val hasActivePointers = touches.any { it.action != TouchAction.UP }
        if (!hasActivePointers) {
            activeTouches.filter { !it.isReleased }.forEach { releaseTouch(it) }
            return
        }

        val activeIds = touches.asSequence()
            .filter { it.action != TouchAction.UP }
            .map { it.id }
            .toHashSet()
        val upIds = touches.asSequence()
            .filter { it.action == TouchAction.UP }
            .map { it.id }
            .toHashSet()

        // Release any touch that no longer appears in the active pointer set.
        activeTouches
            .filter { !it.isReleased && it.id !in activeIds && it.id !in upIds }
            .forEach { releaseTouch(it) }

        for (touch in touches) {
            when (touch.action) {
                TouchAction.DOWN -> {
                    val existingTouch = activeTouches.find { it.id == touch.id && !it.isReleased }
                    if (existingTouch != null) {
                        existingTouch.x = touch.x
                        existingTouch.y = touch.y
                    } else if (activeTouches.size < MAX_TOUCH_POINTS) {
                        activeTouches.add(createTouchPoint(touch))
                    }
                }
                TouchAction.MOVE -> {
                    val existingTouch = activeTouches.find { it.id == touch.id && !it.isReleased }
                    if (existingTouch != null) {
                        existingTouch.x = touch.x
                        existingTouch.y = touch.y
                    } else if (activeTouches.size < MAX_TOUCH_POINTS) {
                        activeTouches.add(createTouchPoint(touch))
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

    fun tick(): Boolean {
        updateTouchPointAnimations()
        return activeTouches.isNotEmpty()
    }

    fun getTouchPointArrays(): Pair<FloatArray, FloatArray> {
        val touchCount = activeTouches.size.coerceAtMost(MAX_TOUCH_POINTS)
        val intensity = if (chromaticSettings.enabled) chromaticSettings.intensity else 0f

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

    private fun createTouchPoint(touch: TouchData): TouchPoint {
        val fadeMs = chromaticSettings.fadeDurationMillis.toInt()
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
            touchPoint.radiusAnimator.tick()
            touchPoint.radius = touchPoint.radiusAnimator.currentValue

            touchPoint.fadeAnimator.tick()
            touchPoint.intensity = touchPoint.fadeAnimator.currentValue

            if (!touchPoint.radiusAnimator.isRunning && !touchPoint.fadeAnimator.isRunning &&
                touchPoint.radius <= 0.01f && touchPoint.intensity <= 0.01f) {
                touchesToRemove.add(touchPoint)
            }
        }
        activeTouches.removeAll(touchesToRemove)
    }
}
