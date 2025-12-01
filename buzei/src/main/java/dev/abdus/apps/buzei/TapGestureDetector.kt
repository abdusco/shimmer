package dev.abdus.apps.buzei

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration

class TapGestureDetector(
    context: Context,
    private val tapsRequired: Int = 3,
    private val tapIntervalMs: Long = 500L
) {

    private val tapSlopSquared =
        ViewConfiguration.get(context).scaledDoubleTapSlop.let { (it * it).toFloat() }
    private val movementSlopSquared =
        ViewConfiguration.get(context).scaledTouchSlop.let { (it * it).toFloat() }

    private var tapCount = 0
    private var lastTapTime = 0L
    private var firstTapX = 0f
    private var firstTapY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isPotentialTap = false

    fun onTripleTap(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_MOVE -> handleActionMove(event)
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> cancelCurrentTap(resetSequence = true)
            MotionEvent.ACTION_UP -> {
                if (event.pointerCount != 1 || !isPotentialTap) {
                    cancelCurrentTap(resetSequence = true)
                    return false
                }
                isPotentialTap = false

                val currentTime = SystemClock.uptimeMillis()
                val timeSinceLastTap = currentTime - lastTapTime

                val isContinuation = tapCount > 0 &&
                        timeSinceLastTap <= tapIntervalMs &&
                        distanceSquared(event.x, event.y, firstTapX, firstTapY) <= tapSlopSquared

                if (isContinuation) {
                    tapCount++
                } else {
                    tapCount = 1
                    firstTapX = event.x
                    firstTapY = event.y
                }
                lastTapTime = currentTime

                if (tapCount >= tapsRequired) {
                    resetTapSequence()
                    return true
                }
            }
        }
        return false
    }

    private fun handleActionDown(event: MotionEvent) {
        if (event.pointerCount == 1) {
            isPotentialTap = true
            downX = event.x
            downY = event.y
        } else {
            cancelCurrentTap(resetSequence = true)
        }
    }

    private fun handleActionMove(event: MotionEvent) {
        if (!isPotentialTap) {
            return
        }
        val movement = distanceSquared(event.x, event.y, downX, downY)
        if (movement > movementSlopSquared) {
            cancelCurrentTap(resetSequence = true)
        }
    }

    private fun cancelCurrentTap(resetSequence: Boolean) {
        isPotentialTap = false
        if (resetSequence) {
            resetTapSequence()
        }
    }

    private fun resetTapSequence() {
        tapCount = 0
        lastTapTime = 0L
        firstTapX = 0f
        firstTapY = 0f
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }
}
