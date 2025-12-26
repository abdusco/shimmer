package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.Bolt
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
import dev.abdus.apps.shimmer.WallpaperPreferences
import kotlin.math.roundToInt

@Composable
fun EventsSettingsSection(
    blurOnScreenLock: Boolean,
    blurTimeoutEnabled: Boolean,
    blurTimeoutMillis: Long,
    onBlurOnScreenLockChange: (Boolean) -> Unit,
    onBlurTimeoutEnabledChange: (Boolean) -> Unit,
    onBlurTimeoutMillisChange: (Long) -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = PADDING_Y, horizontal = PADDING_X),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Events",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = "Configure how the wallpaper responds to screen lock and unlock events",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Blur on screen lock",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = blurOnScreenLock,
                    onCheckedChange = onBlurOnScreenLockChange
                )
            }

            BlurTimeoutSetting(
                enabled = blurTimeoutEnabled,
                timeoutMillis = blurTimeoutMillis,
                onEnabledChange = onBlurTimeoutEnabledChange,
                onTimeoutChange = onBlurTimeoutMillisChange
            )
        }
    }
}

@Composable
private fun BlurTimeoutSetting(
    enabled: Boolean,
    timeoutMillis: Long,
    onEnabledChange: (Boolean) -> Unit,
    onTimeoutChange: (Long) -> Unit,
) {
    val min = WallpaperPreferences.MIN_BLUR_TIMEOUT_MILLIS
    val max = WallpaperPreferences.MAX_BLUR_TIMEOUT_MILLIS
    val step = 5_000L
    val steps = ((max - min) / step).toInt()
    val sliderValue = ((timeoutMillis - min) / step).coerceIn(0, steps.toLong()).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    Text(
                        text = "Blur after timeout",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (enabled) "After ${timeoutMillis / 1000}s" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
        AnimatedVisibility(visible = enabled) {
            Slider(
                value = sliderValue,
                onValueChange = { raw ->
                    val nextIndex = raw.roundToInt().coerceIn(0, steps)
                    val nextTimeout = min + (nextIndex * step)
                    if (nextTimeout != timeoutMillis) {
                        onTimeoutChange(nextTimeout)
                    }
                },
                valueRange = 0f..steps.toFloat(),
                steps = (steps - 1).coerceAtLeast(0)
            )
        }
    }
}

