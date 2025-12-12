package dev.abdus.apps.shimmer

import kotlin.math.abs

/**
 * Exponential smoothing animator for continuous values like parallax scrolling.
 * 
 * Unlike time-based animation, this uses exponential decay where the value moves
 * a fixed percentage of the remaining distance each frame. This creates natural,
 * adaptive motion that:
 * - Tracks closely during slow, continuous updates (dragging)
 * - Smooths out choppy, rapid updates (flinging)
 * - Has natural deceleration without artificial timing
 * 
 * @param smoothingFactor Percentage of distance to move per frame (0.0-1.0)
 *                        Higher = more responsive but less smooth (e.g., 0.3)
 *                        Lower = more smooth but slower response (e.g., 0.1)
 *                        Default 0.15 provides good balance
 */
class SmoothingFloatAnimator(
    private val smoothingFactor: Float = 0.15f,
    private val snapThreshold: Float = 0.001f
) {
    var currentValue: Float = 0f
        private set
    
    var targetValue: Float = 0f
        private set
    
    val isAnimating: Boolean
        get() = abs(targetValue - currentValue) > snapThreshold
    
    /**
     * Sets a new target value. The current value will smoothly move toward it.
     */
    fun setTarget(target: Float) {
        targetValue = target
    }
    
    /**
     * Updates the current value by moving toward the target.
     * Call this every frame.
     * 
     * @return true if still animating, false if reached target
     */
    fun tick(): Boolean {
        if (!isAnimating) {
            return false
        }
        
        // Exponential smoothing: move a fraction of the remaining distance
        val distance = targetValue - currentValue
        currentValue += distance * smoothingFactor
        
        // Snap to target when very close to avoid floating point drift
        if (abs(targetValue - currentValue) < snapThreshold) {
            currentValue = targetValue
            return false
        }
        
        return true
    }
    
    /**
     * Immediately sets both current and target to the given value (no animation).
     */
    fun reset(value: Float) {
        currentValue = value
        targetValue = value
    }
}

