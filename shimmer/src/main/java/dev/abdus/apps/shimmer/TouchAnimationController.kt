package dev.abdus.apps.shimmer

import android.view.animation.AccelerateDecelerateInterpolator

class TouchAnimationController {
    private val activeTouches = mutableListOf<TouchPoint>()
    private var chromaticSettings = ChromaticAberrationSettings(
        enabled = false,
        intensity = 0f,
        fadeDurationMillis = 0L
    )
    private val activeIdBuffer = IntArray(MAX_TOUCH_POINTS)
    private val upIdBuffer = IntArray(MAX_TOUCH_POINTS)
    private var touchPointsArray = FloatArray(0)
    private var touchIntensitiesArray = FloatArray(0)

    companion object {
        // Realistically, we don't need more than 5 touches for a good effect.
        // People don't tend to use both their hands at the same time.
        private const val MAX_TOUCH_POINTS = 5
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
            releaseAllActiveTouches()
            return
        }

        var hasActivePointers = false
        var activeCount = 0
        var upCount = 0
        for (touch in touches) {
            if (touch.action == TouchAction.UP) {
                if (upCount < MAX_TOUCH_POINTS) {
                    upIdBuffer[upCount++] = touch.id
                }
            } else {
                hasActivePointers = true
                if (activeCount < MAX_TOUCH_POINTS) {
                    activeIdBuffer[activeCount++] = touch.id
                }
            }
        }
        if (!hasActivePointers) {
            releaseAllActiveTouches()
            return
        }

        // Release any touch that no longer appears in the active pointer set.
        for (touchPoint in activeTouches) {
            if (!touchPoint.isReleased &&
                !containsId(activeIdBuffer, activeCount, touchPoint.id) &&
                !containsId(upIdBuffer, upCount, touchPoint.id)) {
                releaseTouch(touchPoint)
            }
        }

        for (touch in touches) {
            when (touch.action) {
                TouchAction.DOWN -> {
                    val existingTouch = findActiveTouch(touch.id)
                    if (existingTouch != null) {
                        existingTouch.x = touch.x
                        existingTouch.y = touch.y
                    } else if (activeTouches.size < MAX_TOUCH_POINTS) {
                        activeTouches.add(createTouchPoint(touch))
                    }
                }
                TouchAction.MOVE -> {
                    val existingTouch = findActiveTouch(touch.id)
                    if (existingTouch != null) {
                        existingTouch.x = touch.x
                        existingTouch.y = touch.y
                    } else if (activeTouches.size < MAX_TOUCH_POINTS) {
                        activeTouches.add(createTouchPoint(touch))
                    }
                }
                TouchAction.UP -> {
                    findActiveTouch(touch.id)?.let {
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

        val pointsSize = touchCount * 3
        if (touchPointsArray.size != pointsSize) {
            touchPointsArray = FloatArray(pointsSize)
        }
        if (touchIntensitiesArray.size != touchCount) {
            touchIntensitiesArray = FloatArray(touchCount)
        }

        for (i in 0 until touchCount) {
            val touch = activeTouches[i]
            // as vec3 touch point format: (x, y, radius)
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
            radiusAnimator = TickingFloatAnimator((fadeMs * 1.5f).toInt(), AccelerateDecelerateInterpolator()),
            fadeAnimator = TickingFloatAnimator(fadeMs, AccelerateDecelerateInterpolator())
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
        var i = activeTouches.size - 1
        while (i >= 0) {
            val touchPoint = activeTouches[i]
            touchPoint.radiusAnimator.tick()
            touchPoint.radius = touchPoint.radiusAnimator.currentValue

            touchPoint.fadeAnimator.tick()
            touchPoint.intensity = touchPoint.fadeAnimator.currentValue

            if (!touchPoint.radiusAnimator.isRunning && !touchPoint.fadeAnimator.isRunning &&
                touchPoint.radius <= 0.01f && touchPoint.intensity <= 0.01f) {
                activeTouches.removeAt(i)
            }
            i--
        }
    }

    private fun releaseAllActiveTouches() {
        for (touchPoint in activeTouches) {
            if (!touchPoint.isReleased) {
                releaseTouch(touchPoint)
            }
        }
    }

    private fun findActiveTouch(id: Int): TouchPoint? {
        for (touchPoint in activeTouches) {
            if (touchPoint.id == id && !touchPoint.isReleased) return touchPoint
        }
        return null
    }

    private fun containsId(ids: IntArray, count: Int, id: Int): Boolean {
        for (i in 0 until count) {
            if (ids[i] == id) return true
        }
        return false
    }
}
