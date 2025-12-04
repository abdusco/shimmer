package dev.abdus.apps.shimmer

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Owns the scheduled task that advances images on a fixed cadence. This keeps
 * the wallpaper engine focused on rendering concerns.
 */
class ImageTransitionScheduler(
    private val executor: ScheduledExecutorService,
    private val onAdvanceRequest: () -> Unit
) {

    private var future: ScheduledFuture<*>? = null
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
        future?.cancel(false)
        future = null
    }

    fun restartAfterManualAdvance() {
        if (!transitionEnabled) {
            return
        }
        restartIfNecessary()
    }

    private fun restartIfNecessary() {
        future?.cancel(false)
        if (!transitionEnabled) {
            future = null
            return
        }
        val delayMillis = max(transitionIntervalMillis, 1_000L)
        future = executor.scheduleWithFixedDelay(
            { onAdvanceRequest() },
            delayMillis,
            delayMillis,
            TimeUnit.MILLISECONDS
        )
    }
}
