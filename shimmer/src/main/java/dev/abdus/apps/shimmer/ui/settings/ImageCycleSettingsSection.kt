package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun ImageCycleSettingsSection(
    enabled: Boolean,
    intervalMillis: Long,
    cycleImageOnUnlock: Boolean,
    onImageCycleEnabledChange: (Boolean) -> Unit,
    onImageCycleIntervalChange: (Long) -> Unit,
    onCycleImageOnUnlockChange: (Boolean) -> Unit,
) {
    val options = TRANSITION_INTERVAL_OPTIONS
    val sliderIndex = options.indexOfFirst { it.millis == intervalMillis }.takeIf { it >= 0 } ?: 0
    val selectedOption = options.getOrElse(sliderIndex) { options.first() }
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(vertical = PADDING_Y, horizontal = PADDING_X), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SwapHoriz, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Text("Cycle images automatically", style = MaterialTheme.typography.titleMedium)
                }
                Switch(checked = enabled, onCheckedChange = onImageCycleEnabledChange)
            }
            Text(
                "Automatically cycle through images from your selected folders at regular intervals",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.animation.AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Cycle every", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = sliderIndex.toFloat(),
                        onValueChange = { raw ->
                            val nextIndex = raw.roundToInt().coerceIn(0, options.lastIndex)
                            onImageCycleIntervalChange(options[nextIndex].millis)
                        },
                        valueRange = 0f..options.lastIndex.toFloat(),
                        steps = (options.size - 2).coerceAtLeast(0),
                    )
                    Text(
                        "Next cycle in ${selectedOption.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Cycle when screen unlocks", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = cycleImageOnUnlock, onCheckedChange = onCycleImageOnUnlockChange)
            }
        }
    }
}

data class TransitionDurationOption(val millis: Long, val label: String)

val TRANSITION_INTERVAL_OPTIONS = listOf(
    TransitionDurationOption(5_000L, "5s"),
    TransitionDurationOption(30_000L, "30s"),
    TransitionDurationOption(60_000L, "1min"),
    TransitionDurationOption(5 * 60_000L, "5min"),
    TransitionDurationOption(15 * 60_000L, "15min"),
    TransitionDurationOption(60 * 60_000L, "1h"),
    TransitionDurationOption(3 * 60 * 60_000L, "3h"),
    TransitionDurationOption(6 * 60 * 60_000L, "6h"),
    TransitionDurationOption(24 * 60 * 60_000L, "24h"),
)

