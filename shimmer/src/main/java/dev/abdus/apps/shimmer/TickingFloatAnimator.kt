package dev.abdus.apps.shimmer

import android.animation.TimeInterpolator
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * Manual tick-based animator for OpenGL rendering.
 *
 * Unlike Android's ValueAnimator which requires a Looper thread (main thread),
 * this animator works on any thread including the GL thread. It's manually
 * ticked in onDrawFrame() for smooth, synchronized animations.
 *
 * This is the right approach for OpenGL rendering where animations need to be
 * frame-synchronized rather than time-synchronized.
 */
class TickingFloatAnimator(
    var durationMillis: Int,
    private val interpolator: TimeInterpolator = DecelerateInterpolator()
) {

    private var startValue: Float = 0f
    private var endValue: Float = 0f
    private var onEnd: () -> Unit = {}

    private var startTime: Long = 0
    var currentValue: Float = 0f
    var isRunning = false
        private set
    var progress: Float = 0f
        private set

    fun start(startValue: Float = currentValue, endValue: Float, onEnd: () -> Unit = {}) {
        this.startValue = startValue
        this.endValue = endValue
        this.onEnd = onEnd
        isRunning = true
        startTime = SystemClock.elapsedRealtime()
        tick()
    }

    fun tick(): Boolean {
        if (!isRunning) {
            return false
        }

        val t = min((SystemClock.elapsedRealtime() - startTime).toFloat() / durationMillis, 1f)
        progress = t // Set the progress property
        isRunning = t < 1f
        currentValue = if (isRunning) {
            startValue + interpolator.getInterpolation(t) * (endValue - startValue)
        } else {
            endValue.also { onEnd() }
        }
        return isRunning
    }

    fun reset() {
        isRunning = false
        progress = 1f
    }
}
