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

    // Single finger tap tracking
    private var tapCount = 0
    private var lastTapTime = 0L
    private var firstTapX = 0f
    private var firstTapY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isPotentialTap = false

    // Two finger tap tracking
    private var twoFingerTapCount = 0
    private var lastTwoFingerTapTime = 0L
    private var isPotentialTwoFingerTap = false
    private var twoFingerDownX1 = 0f
    private var twoFingerDownY1 = 0f
    private var twoFingerDownX2 = 0f
    private var twoFingerDownY2 = 0f

    fun onTwoFingerDoubleTap(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // First finger down - not a two finger tap yet
                isPotentialTwoFingerTap = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isPotentialTwoFingerTap = true
                    twoFingerDownX1 = event.getX(0)
                    twoFingerDownY1 = event.getY(0)
                    twoFingerDownX2 = event.getX(1)
                    twoFingerDownY2 = event.getY(1)
                } else {
                    // More than 2 fingers - cancel
                    cancelTwoFingerTap()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPotentialTwoFingerTap && event.pointerCount == 2) {
                    // Check if fingers moved too much
                    val movement1 = distanceSquared(event.getX(0), event.getY(0), twoFingerDownX1, twoFingerDownY1)
                    val movement2 = distanceSquared(event.getX(1), event.getY(1), twoFingerDownX2, twoFingerDownY2)
                    if (movement1 > movementSlopSquared || movement2 > movementSlopSquared) {
                        cancelTwoFingerTap()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isPotentialTwoFingerTap && event.pointerCount == 2) {
                    // One of two fingers lifted - register tap
                    val currentTime = SystemClock.uptimeMillis()
                    val timeSinceLastTap = currentTime - lastTwoFingerTapTime

                    if (twoFingerTapCount > 0 && timeSinceLastTap <= tapIntervalMs) {
                        twoFingerTapCount++
                    } else {
                        twoFingerTapCount = 1
                    }
                    lastTwoFingerTapTime = currentTime
                    isPotentialTwoFingerTap = false

                    if (twoFingerTapCount >= 2) {
                        resetTwoFingerTapSequence()
                        return true
                    }
                } else {
                    cancelTwoFingerTap()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelTwoFingerTap()
            }
        }
        return false
    }

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

    private fun cancelTwoFingerTap() {
        isPotentialTwoFingerTap = false
    }

    private fun resetTwoFingerTapSequence() {
        twoFingerTapCount = 0
        lastTwoFingerTapTime = 0L
        isPotentialTwoFingerTap = false
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }
}
