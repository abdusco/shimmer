package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/**
 * Debounces value changes - only executes the callback after [delayMillis] of inactivity.
 * Automatically cancels pending execution when value changes, implementing true debounce behavior.
 * Useful for expensive operations like image reprocessing.
 */
@Composable
fun <T> DebouncedEffect(
    value: T,
    delayMillis: Long = 500,
    onDebounced: suspend (T) -> Unit,
) {
    LaunchedEffect(value) {
        delay(delayMillis)
        onDebounced(value)
    }
}

