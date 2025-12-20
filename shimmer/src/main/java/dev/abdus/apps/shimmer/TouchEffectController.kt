package dev.abdus.apps.shimmer

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent

/**
 * Manages touch effect activation with delay and coordinates with the renderer.
 * Touch effects are activated after a short delay to avoid triggering on quick taps.
 */
class TouchEffectController(
    private val onTouchPointsChanged: (List<TouchData>) -> Unit,
    private val activationDelayMs: Long = 250L
) {
    private val handler = Handler(Looper.getMainLooper())
    private val touchList = ArrayList<TouchData>(10)
    private var isActive = false
    
    private val activationRunnable = Runnable {
        isActive = true
        if (touchList.isNotEmpty()) {
            onTouchPointsChanged(ArrayList(touchList))
        }
    }
    
    /**
     * Process a touch event and update touch effects accordingly.
     * Returns the current list of touch points for other gesture processing.
     */
    fun onTouchEvent(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int): List<TouchData> {
        val currentTouches = extractTouchPoints(event, surfaceWidth, surfaceHeight)
        val action = event.actionMasked
        
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // New touch sequence - schedule activation after delay
                isActive = false
                handler.removeCallbacks(activationRunnable)
                handler.postDelayed(activationRunnable, activationDelayMs)
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Touch sequence ended - cancel pending activation and clear effects
                handler.removeCallbacks(activationRunnable)
                
                if (isActive) {
                    // Send final touch state or clear if no touches remain
                    onTouchPointsChanged(
                        if (currentTouches.isNotEmpty()) currentTouches else emptyList()
                    )
                }
                isActive = false
            }
            
            else -> {
                // Touch move - update effects if already active
                if (isActive && currentTouches.isNotEmpty()) {
                    onTouchPointsChanged(currentTouches)
                }
            }
        }
        
        return currentTouches
    }
    
    /**
     * Extract normalized touch points from a MotionEvent.
     * Coordinates are normalized to [0, 1] with Y flipped for OpenGL.
     */
    private fun extractTouchPoints(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): List<TouchData> {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return emptyList()
        
        touchList.clear()
        val action = event.actionMasked
        
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            val x = event.getX(i) / surfaceWidth
            val y = 1f - (event.getY(i) / surfaceHeight) // Flip Y for OpenGL
            
            val touchAction = when {
                action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL -> 
                    TouchAction.UP
                action == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex -> 
                    TouchAction.UP
                action == MotionEvent.ACTION_DOWN && i == 0 -> 
                    TouchAction.DOWN
                action == MotionEvent.ACTION_POINTER_DOWN && i == event.actionIndex -> 
                    TouchAction.DOWN
                else -> 
                    TouchAction.MOVE
            }
            
            touchList.add(TouchData(pointerId, x, y, touchAction))
        }
        
        return ArrayList(touchList)
    }
    
    /**
     * Cancel any pending activation and clear effects.
     * Call this when the engine is being destroyed.
     */
    fun cleanup() {
        handler.removeCallbacks(activationRunnable)
        touchList.clear()
        isActive = false
    }
}