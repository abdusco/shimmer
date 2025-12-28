package dev.abdus.apps.shimmer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class ImageCycleScheduler(
    private val scope: CoroutineScope,
    private val preferences: WallpaperPreferences,
    private val onCycleImage: () -> Unit
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
                val interval = preferences.getImageCycleIntervalMillis()
                
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
                        onCycleImage()
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

    fun pauseForInteraction() {
        if (!isPaused) {
            isPaused = true
            pauseStartTime = System.currentTimeMillis()
        }
        
        scope.launch {
            delay(2000L)
            resumeTimer()
        }
    }
    
    private fun pauseTimer() {
        if (!isPaused) {
            isPaused = true
            pauseStartTime = System.currentTimeMillis()
        }
    }
    
    private fun resumeTimer() {
        if (isPaused) {
            val pauseDuration = System.currentTimeMillis() - pauseStartTime
            accumulatedPauseMillis += pauseDuration
            isPaused = false
        }
    }

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
        intervalStartTime = 0L
        accumulatedPauseMillis = 0L
        isPaused = false
        pauseStartTime = 0L
    }
}