package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

val PADDING_X = 24.dp
val PADDING_Y = 24.dp


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


@Composable
fun PercentSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    steps: Int = 19,
    labelFormatter: (Float) -> String = { formatPercent(it) },
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.invoke()
            Text(
                text = "$title: ${labelFormatter(value)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            steps = steps,
            valueRange = 0f..1f,
        )
    }
}

fun formatPercent(value: Float): String = "${(value * 100).roundToInt()}%"

@Composable
fun DurationSlider(
    title: String,
    durationMillis: Long,
    onDurationChange: (Long) -> Unit,
    durationRange: LongRange,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    steps: Int = 20 - 1,
) {
    val minDuration = durationRange.first.toFloat()
    val maxDuration = durationRange.last.toFloat()
    val durationSpan = maxDuration - minDuration

    // Convert duration to 0..1 slider value
    val sliderValue = ((durationMillis - minDuration) / durationSpan).coerceIn(0f, 1f)

    PercentSlider(
        title = title,
        icon = {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    modifier = Modifier.size(20.dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        value = sliderValue,
        onValueChange = { sliderVal ->
            // Convert slider value (0..1) back to duration range
            val newDuration = (sliderVal * durationSpan + minDuration).roundToInt().toLong()
                .coerceIn(durationRange.first, durationRange.last)
            onDurationChange(newDuration)
        },
        enabled = enabled,
        steps = steps,
        labelFormatter = { sliderVal ->
            val duration = (sliderVal * durationSpan + minDuration).roundToInt().toLong()
                .coerceIn(durationRange.first, durationRange.last)
            formatDurationMs(duration)
        },
    )
}

fun formatDurationMs(millis: Long): String {
    return if (millis == 0L) {
        "0s"
    } else if (millis < 1000L) {
        "${millis}ms"
    } else {
        val seconds = millis / 1000.0
        if (seconds == seconds.toInt().toDouble()) {
            "${seconds.toInt()}s"
        } else {
            "${seconds}s"
        }
    }
}

@Composable
fun ColorHexField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    previewColor: Color,
) {
    // Maintain local state for editing to allow free typing without parent overwriting
    var localValue by remember { mutableStateOf(value.uppercase()) }
    // Track the last valid value we sent to distinguish our updates from external ones
    var lastValidSentValue by remember { mutableStateOf<String?>(null) }

    // Sync from parent only when parent value represents an external change
    androidx.compose.runtime.LaunchedEffect(value) {
        val normalizedParent = value.uppercase()
        val normalizedLocal = localValue.uppercase()

        // Only sync if parent has a valid color that's different from what we last sent
        // This prevents parent from overwriting user's partial input while typing
        if (isValidHexColor(normalizedParent)) {
            // Parent has valid color - sync only if it's different from what we last sent
            // (meaning it's an external update, not our own update being reflected back)
            if (normalizedParent != lastValidSentValue) {
                localValue = normalizedParent
                lastValidSentValue = normalizedParent
            }
        } else if (normalizedParent.isEmpty() && normalizedLocal.isNotEmpty()) {
            // Handle reset case
            localValue = ""
            lastValidSentValue = ""
        }
    }

    val normalized = localValue.uppercase()
    val isError = normalized.isNotEmpty() && !isValidHexColor(normalized)

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = normalized,
            onValueChange = { newValue ->
                val uppercased = newValue.uppercase()
                localValue = uppercased
                onValueChange(uppercased)
                // Update lastValidSentValue only when we send a valid color
                // This helps us distinguish our updates from external ones
                if (isValidHexColor(uppercased)) {
                    lastValidSentValue = uppercased
                }
            },
            label = { Text(label) },
            singleLine = true,
            isError = isError,
        )
        Spacer(modifier = Modifier.width(12.dp))
        ColorSwatch(previewColor, RoundedCornerShape(8.dp))
    }
}

@Composable
fun ColorSwatch(color: Color, shape: Shape) {
    Surface(
        modifier = Modifier.size(40.dp),
        color = color,
        shape = shape,
        shadowElevation = 2.dp,
    ) {}
}

fun colorIntToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

fun isValidHexColor(value: String): Boolean =
    HEX_COLOR_REGEX.matches(if (value.startsWith("#")) value else "#$value")

fun parseColorHex(value: String): Int? {
    if (value.isBlank()) {
        return null
    }
    val candidate = if (value.startsWith("#")) value else "#$value"
    return try {
        candidate.toColorInt()
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val HEX_COLOR_REGEX = "^#[0-9A-Fa-f]{6}$".toRegex()

fun colorIntToComposeColor(colorInt: Int): Color =
    Color(colorInt.toLong() and 0xFFFFFFFFL)

