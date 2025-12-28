package dev.abdus.apps.shimmer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageCycleScheduler(
    private val scope: CoroutineScope,
    private val getIntervalMs: () -> Long,
    private val onCycleImage: () -> Unit
) {
    private var job: Job? = null
    private var isRunning = false
    private var isVisible = false

    private var cycleStartTime = 0L
    private var pausedAt = 0L
    private var totalPausedDuration = 0L
    private var pauseJob: Job? = null

    fun updateEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    fun start() {
        if (isRunning) return

        isRunning = true
        if (cycleStartTime == 0L) resetTimer()

        job = scope.launch {
            while (isActive && isRunning) {
                val elapsed = getElapsedTime()
                val interval = getIntervalMs()
                val remaining = interval - elapsed

                if (remaining <= 0 && pausedAt == 0L) {
                    if (isVisible) {
                        withContext(Dispatchers.Main) {
                            onCycleImage()
                        }
                        resetTimer()
                    }
                    delay(1000L)
                } else {
                    delay(remaining.coerceIn(500L, 5000L))
                }
            }
            job = null
        }
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
    }

    fun pauseTemporarily(durationMs: Long = 2000L) {
        pauseJob?.cancel()
        pausedAt = System.currentTimeMillis()

        pauseJob = scope.launch {
            delay(durationMs)
            if (pausedAt != 0L) {
                totalPausedDuration += System.currentTimeMillis() - pausedAt
                pausedAt = 0L
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
    }

    fun reset() {
        stop()
        pauseJob?.cancel()
        cycleStartTime = 0L
        totalPausedDuration = 0L
        pausedAt = 0L
    }

    private fun getElapsedTime(): Long {
        val now = System.currentTimeMillis()
        val totalElapsed = now - cycleStartTime
        val effectivePause = if (pausedAt != 0L) {
            now - pausedAt
        } else {
            0L
        }
        return totalElapsed - totalPausedDuration - effectivePause
    }

    fun resetTimer() {
        cycleStartTime = System.currentTimeMillis()
        totalPausedDuration = 0L
        pausedAt = 0L
    }
}