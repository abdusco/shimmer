package dev.abdus.apps.shimmer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Owns the scheduled task that advances images on a fixed cadence. This keeps
 * the wallpaper engine focused on rendering concerns.
 * 
 * Design principles:
 * - When user interacts (touch/parallax), pause the timer but DON'T reset it
 * - When user manually changes image, RESET the timer completely
 * - When wallpaper becomes invisible, pause but preserve remaining time
 * - When wallpaper becomes visible, resume from where we left off
 */
class ImageTransitionScheduler(
    private val scope: CoroutineScope,
    private val preferences: WallpaperPreferences,
    private val onAdvanceRequest: () -> Unit
) {
    private var job: Job? = null
    private var isEnabled = false
    private var isWallpaperVisible = true
    
    // Track when the current interval started
    private var intervalStartTime = 0L
    
    // Track accumulated pause time during user interaction
    private var accumulatedPauseMillis = 0L
    private var pauseStartTime = 0L
    private var isPaused = false

    fun updateEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (isEnabled) start() else stop()
    }
    
    fun onWallpaperVisible() {
        isWallpaperVisible = true
        if (isEnabled) {
            start() // Resume if we were running
        }
    }
    
    fun onWallpaperHidden() {
        isWallpaperVisible = false
        // When going invisible, pause but preserve state
        pauseTimer()
    }

    fun start() {
        stop()
        if (!isEnabled || !isWallpaperVisible) return
        
        // If this is a fresh start (not a resume), reset timing
        if (intervalStartTime == 0L) {
            resetTimer()
        }

        job = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val interval = preferences.getTransitionIntervalMillis()
                
                // Calculate actual elapsed time, accounting for pauses
                val elapsed = if (isPaused) {
                    // While paused, don't advance elapsed time
                    (pauseStartTime - intervalStartTime) - accumulatedPauseMillis
                } else {
                    (now - intervalStartTime) - accumulatedPauseMillis
                }
                
                val remaining = interval - elapsed
                
                if (remaining <= 0 && !isPaused && isWallpaperVisible) {
                    // Time to change!
                    withContext(Dispatchers.Main) {
                        onAdvanceRequest()
                    }
                    // Auto-reset timer after successful transition
                    resetTimer()
                    delay(1000L) // Small delay before next check
                } else {
                    // Wait and check again
                    delay(max(1000L, remaining.coerceAtMost(5000L)))
                }
            }
        }
    }

    /**
     * Pause the timer during user interaction (touch/parallax).
     * This temporarily stops the countdown but preserves progress.
     * The timer auto-resumes after a short debounce period.
     */
    fun pauseForInteraction() {
        if (!isPaused) {
            isPaused = true
            pauseStartTime = System.currentTimeMillis()
        }
        
        // Auto-resume after 2 seconds of no interaction
        scope.launch {
            delay(2000L)
            resumeTimer()
        }
    }
    
    /**
     * Pause the timer (used when wallpaper goes invisible).
     */
    private fun pauseTimer() {
        if (!isPaused) {
            isPaused = true
            pauseStartTime = System.currentTimeMillis()
        }
    }
    
    /**
     * Resume the timer after a pause.
     */
    private fun resumeTimer() {
        if (isPaused) {
            val pauseDuration = System.currentTimeMillis() - pauseStartTime
            accumulatedPauseMillis += pauseDuration
            isPaused = false
        }
    }

    /**
     * Reset the timer completely. Called when:
     * - User manually changes the image
     * - Automatic transition completes
     * - Scheduler is started fresh
     */
    fun resetTimer() {
        intervalStartTime = System.currentTimeMillis()
        accumulatedPauseMillis = 0L
        isPaused = false
        pauseStartTime = 0L
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun cancel() {
        stop()
        // Clear all state
        intervalStartTime = 0L
        accumulatedPauseMillis = 0L
        isPaused = false
        pauseStartTime = 0L
    }
}