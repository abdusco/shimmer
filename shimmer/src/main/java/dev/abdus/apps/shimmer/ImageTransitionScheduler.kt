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
 */

class ImageTransitionScheduler(
    private val scope: CoroutineScope,
    private val preferences: WallpaperPreferences,
    private val onAdvanceRequest: () -> Unit
) {
    private var job: Job? = null
    private var isEnabled = false

    // In-memory only! No disk I/O for interaction pauses.
    private var userInteractingUntil = 0L

    fun updateEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (isEnabled) start() else stop()
    }

    fun start() {
        stop()
        if (!isEnabled) return

        job = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()

                // 1. Check Interaction Pause (In-Memory)
                if (now < userInteractingUntil) {
                    delay(max(userInteractingUntil - now, 1000L))
                    continue
                }

                // 2. Check Persistence (Disk Read - infrequent)
                val lastTime = preferences.getImageLastChangedAt()
                val interval = preferences.getTransitionIntervalMillis()
                val elapsed = now - lastTime
                val remaining = interval - elapsed

                if (remaining <= 0) {
                    // Time to change!
                    withContext(Dispatchers.Main) {
                        onAdvanceRequest()
                    }
                    // This is a rare event (minutes/hours), so disk write is fine here
                    preferences.setImageLastChangedAt(System.currentTimeMillis())
                } else {
                    // Wait for the next check
                    delay(max(remaining, 1000L))
                }
            }
        }
    }

    /**
     * Prevents image changes for the next [interval] milliseconds.
     * Called on every touch/parallax update. FAST (No disk I/O).
     */
    fun pauseForInteraction() {
        val interval = preferences.getTransitionIntervalMillis()
        userInteractingUntil = System.currentTimeMillis() + interval
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun cancel() = stop()
}