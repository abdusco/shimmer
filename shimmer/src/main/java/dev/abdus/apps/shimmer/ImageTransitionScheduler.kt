package dev.abdus.apps.shimmer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Owns the scheduled task that advances images on a fixed cadence. This keeps
 * the wallpaper engine focused on rendering concerns.
 */
class ImageTransitionScheduler(
    private val scope: CoroutineScope,
    private val onAdvanceRequest: () -> Unit
) {

    private var job: Job? = null
    private var transitionIntervalMillis = WallpaperPreferences.DEFAULT_TRANSITION_INTERVAL_MILLIS
    private var transitionEnabled = true

    fun updateInterval(intervalMillis: Long) {
        transitionIntervalMillis = intervalMillis
        restartIfNecessary()
    }

    fun updateEnabled(enabled: Boolean) {
        transitionEnabled = enabled
        restartIfNecessary()
    }

    fun start() {
        restartIfNecessary()
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun restartIfNecessary() {
        job?.cancel()
        if (!transitionEnabled) {
            job = null
            return
        }
        val delayMillis = max(transitionIntervalMillis, 1_000L)
        job = scope.launch {
            delay(delayMillis) // Initial delay
            while (isActive) {
                onAdvanceRequest()
                delay(delayMillis)
            }
        }
    }
}
