package dev.abdus.apps.shimmer

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

enum class TapEvent {
    TRIPLE_TAP,
    TWO_FINGER_DOUBLE_TAP,
    NONE
}

/**
 * Generic tap tracker for detecting N taps with M fingers.
 */

class TapGestureDetector(context: Context) {

    companion object {
        private const val DOUBLE_TAP_TIMEOUT = 350L // Maximum time between taps
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var tapCount = 0
    private var lastTapTime = 0L
    private var lastTapFingerCount = 0
    private var maxPointersInCurrentTap = 0
    private var isCurrentGestureAborted = false

    private val startPointers = mutableMapOf<Int, PointF>()

    fun onTouchEvent(event: MotionEvent): TapEvent {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val now = SystemClock.uptimeMillis()

                // If the time between taps is too long, reset the sequence
                if (now - lastTapTime > DOUBLE_TAP_TIMEOUT) {
                    tapCount = 0
                }

                resetCurrentTapState()
                trackPointer(event, pointerIndex)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                trackPointer(event, pointerIndex)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isCurrentGestureAborted) {
                    for (i in 0 until event.pointerCount) {
                        val id = event.getPointerId(i)
                        val startPos = startPointers[id] ?: continue
                        if (dist(startPos.x, startPos.y, event.getX(i), event.getY(i)) > touchSlop) {
                            isCurrentGestureAborted = true
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val result = if (!isCurrentGestureAborted) {
                    handleTapCompleted(maxPointersInCurrentTap)
                } else {
                    tapCount = 0
                    TapEvent.NONE
                }

                lastTapTime = now
                startPointers.clear()
                return result
            }

            MotionEvent.ACTION_CANCEL -> {
                tapCount = 0
                startPointers.clear()
                resetCurrentTapState()
            }
        }

        return TapEvent.NONE
    }

    private fun trackPointer(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        startPointers[id] = PointF(event.getX(index), event.getY(index))

        if (event.pointerCount > maxPointersInCurrentTap) {
            maxPointersInCurrentTap = event.pointerCount
        }
    }

    private fun handleTapCompleted(fingerCount: Int): TapEvent {
        // If the number of fingers changed mid-sequence (e.g. 1-finger tap then 2-finger tap),
        // we treat this as the start of a brand new sequence.
        if (fingerCount != lastTapFingerCount) {
            tapCount = 1
        } else {
            tapCount++
        }

        lastTapFingerCount = fingerCount

        return when {
            fingerCount == 2 && tapCount == 2 -> {
                reset()
                TapEvent.TWO_FINGER_DOUBLE_TAP
            }

            fingerCount == 1 && tapCount == 3 -> {
                reset()
                TapEvent.TRIPLE_TAP
            }

            // Abort if finger count is unsupported or tap sequence is too long
            fingerCount > 2 || tapCount > 3 -> {
                tapCount = 0
                TapEvent.NONE
            }

            else -> TapEvent.NONE
        }
    }

    private fun resetCurrentTapState() {
        isCurrentGestureAborted = false
        maxPointersInCurrentTap = 0
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        // Manhattan distance is faster than sqrt for simple slop checks
        return abs(x1 - x2) + abs(y1 - y2)
    }

    fun reset() {
        tapCount = 0
        lastTapTime = 0L
        lastTapFingerCount = 0
        resetCurrentTapState()
        startPointers.clear()
    }
}