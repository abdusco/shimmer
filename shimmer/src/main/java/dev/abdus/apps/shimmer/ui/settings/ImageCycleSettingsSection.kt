package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ImageCycleSettings
import kotlin.math.roundToInt

@Composable
fun ImageCycleSettingsSection(
    settings: ImageCycleSettings,
    onUpdate: (ImageCycleSettings) -> Unit,
) {
    val options = TRANSITION_INTERVAL_OPTIONS
    val sliderIndex = options.indexOfFirst { it.millis == settings.intervalMillis }.takeIf { it >= 0 } ?: 0
    val selectedOption = options.getOrElse(sliderIndex) { options.first() }
    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                title = "Image cycling",
                description = "Automatically cycle through images at regular intervals",
                iconVector = Icons.Outlined.SwapHoriz,
                actionSlot = {
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { onUpdate(settings.copy(enabled = it)) }
                    )
                }
            )

            AnimatedVisibility(visible = settings.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Cycle every", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = sliderIndex.toFloat(),
                        onValueChange = { raw ->
                            val nextIndex = raw.roundToInt().coerceIn(0, options.lastIndex)
                            onUpdate(settings.copy(intervalMillis = options[nextIndex].millis))
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
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Cycle when screen unlocks", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = settings.cycleOnUnlock,
                    onCheckedChange = { onUpdate(settings.copy(cycleOnUnlock = it)) }
                )
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

