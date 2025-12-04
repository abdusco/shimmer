package dev.abdus.apps.buzei

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration

enum class TapEvent {
    TRIPLE_TAP,
    TWO_FINGER_DOUBLE_TAP,
    NONE
}

/**
 * Generic tap tracker for detecting N taps with M fingers.
 */
private class TapTracker(
    private val requiredTaps: Int,
    private val tapIntervalMs: Long,
    private val pointerCount: Int,
    private val movementSlopSquared: Float,
    private val tapSlopSquared: Float  // For multi-tap region checking
) {
    private var tapCount = 0
    private var lastTapTime = 0L
    private var isPotential = false

    // Store initial pointer positions for current tap
    private val downX = FloatArray(pointerCount)
    private val downY = FloatArray(pointerCount)

    // Store first tap location to ensure all taps in same region (for multi-tap gestures)
    private var firstTapX = 0f
    private var firstTapY = 0f

    /**
     * Process a motion event and return true if the gesture is detected.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only single-finger tracker should respond to ACTION_DOWN
                if (pointerCount == 1) {
                    if (event.pointerCount == 1) {
                        startTracking(event)
                    } else {
                        reset()
                    }
                }
                // Multi-finger trackers ignore ACTION_DOWN
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Only multi-finger tracker should respond when pointer count matches
                if (pointerCount > 1 && event.pointerCount == pointerCount) {
                    startTracking(event)
                } else if (isPotential && event.pointerCount > pointerCount) {
                    // Too many fingers - cancel if we were tracking
                    cancel()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isPotential && hasMovedTooMuch(event)) {
                    if (pointerCount == 1) reset() else cancel()
                }
            }

            MotionEvent.ACTION_UP -> {
                // Only single-finger tracker should respond to ACTION_UP
                if (pointerCount == 1 && event.pointerCount == 1 && isPotential) {
                    return registerTap()
                } else if (pointerCount == 1) {
                    reset()
                }
                // Multi-finger trackers ignore ACTION_UP
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Only multi-finger tracker should respond when pointer count matches
                if (pointerCount > 1 && event.pointerCount == pointerCount && isPotential) {
                    return registerTap()
                } else if (pointerCount > 1 && isPotential) {
                    cancel()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isPotential) {
                    if (pointerCount == 1) reset() else cancel()
                }
            }
        }
        return false
    }

    private fun startTracking(event: MotionEvent) {
        isPotential = true
        for (i in 0 until pointerCount.coerceAtMost(event.pointerCount)) {
            downX[i] = event.getX(i)
            downY[i] = event.getY(i)
        }
    }

    private fun hasMovedTooMuch(event: MotionEvent): Boolean {
        if (event.pointerCount < pointerCount) return true

        for (i in 0 until pointerCount) {
            val dx = event.getX(i) - downX[i]
            val dy = event.getY(i) - downY[i]
            if (dx * dx + dy * dy > movementSlopSquared) {
                return true
            }
        }
        return false
    }

    private fun registerTap(): Boolean {
        val currentTime = SystemClock.uptimeMillis()
        val timeSinceLastTap = currentTime - lastTapTime

        // Check if this tap is part of the current sequence
        val isContinuation = tapCount > 0 &&
                timeSinceLastTap <= tapIntervalMs &&
                isInSameRegion()

        if (isContinuation) {
            tapCount++
        } else {
            // Start new sequence
            tapCount = 1
            firstTapX = downX[0]
            firstTapY = downY[0]
        }

        lastTapTime = currentTime
        isPotential = false

        if (tapCount >= requiredTaps) {
            reset()
            return true
        }
        return false
    }

    private fun isInSameRegion(): Boolean {
        val dx = downX[0] - firstTapX
        val dy = downY[0] - firstTapY
        return dx * dx + dy * dy <= tapSlopSquared
    }

    private fun cancel() {
        isPotential = false
    }

    private fun reset() {
        tapCount = 0
        lastTapTime = 0L
        isPotential = false
    }
}

class TapGestureDetector(context: Context) {
    companion object {
        const val TAP_INVERVAL_MS = 500L
    }

    private val tapSlopSquared =
        ViewConfiguration.get(context).scaledDoubleTapSlop.let { (it * it).toFloat() }
    private val movementSlopSquared =
        ViewConfiguration.get(context).scaledTouchSlop.let { (it * it).toFloat() }

    // Single finger tracking (e.g., triple tap)
    private val singleFingerTracker = TapTracker(
        requiredTaps = 3,
        pointerCount = 1,
        tapIntervalMs = TAP_INVERVAL_MS,
        movementSlopSquared = movementSlopSquared,
        tapSlopSquared = tapSlopSquared
    )

    // Two finger tracking (e.g., double tap)
    private val twoFingerTracker = TapTracker(
        requiredTaps = 2,
        pointerCount = 2,
        tapIntervalMs = TAP_INVERVAL_MS,
        movementSlopSquared = movementSlopSquared,
        tapSlopSquared = tapSlopSquared
    )

    /**
     * Process a touch event and return the detected gesture, if any.
     */
    fun onTouchEvent(event: MotionEvent): TapEvent {
        // Check two-finger double tap first
        if (twoFingerTracker.onTouchEvent(event)) {
            return TapEvent.TWO_FINGER_DOUBLE_TAP
        }

        // Check single-finger triple tap
        if (singleFingerTracker.onTouchEvent(event)) {
            return TapEvent.TRIPLE_TAP
        }

        return TapEvent.NONE
    }
}
