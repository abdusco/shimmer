package dev.abdus.apps.buzei

import android.animation.TimeInterpolator
import android.view.animation.DecelerateInterpolator
import android.os.SystemClock
import kotlin.math.min

class TickingFloatAnimator(
    private val duration: Int,
    private val interpolator: TimeInterpolator = DecelerateInterpolator()
) {

    private var startValue: Float = 0f
    private var endValue: Float = 0f
    private var onEnd: () -> Unit = {}

    private var startTime: Long = 0
    var currentValue: Float = 0f
    var isRunning = false
        private set

    fun start(startValue: Float = currentValue, endValue: Float, onEnd: () -> Unit = {}) {
        this.startValue = startValue
        this.endValue = endValue
        this.onEnd = onEnd
        isRunning = true
        startTime = SystemClock.elapsedRealtime()
        tick()
    }

    fun snapTo(value: Float) {
        isRunning = false
        currentValue = value
    }

    fun finish() {
        if (isRunning) {
            isRunning = false
            currentValue = endValue.also { onEnd() }
        }
    }

    fun tick(): Boolean {
        if (!isRunning) {
            return false
        }

        val t = min((SystemClock.elapsedRealtime() - startTime).toFloat() / duration, 1f)
        isRunning = t < 1f
        currentValue = if (isRunning) {
            startValue + interpolator.getInterpolation(t) * (endValue - startValue)
        } else {
            endValue.also { onEnd() }
        }
        return isRunning
    }
}
