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
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ChromaticAberrationSettings
import dev.abdus.apps.shimmer.R

@Composable
fun ChromaticAberrationSettings(
    state: ChromaticAberrationSettings,
    onSettingsChange: (ChromaticAberrationSettings) -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RemoveRedEye,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Chromatic aberration",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { onSettingsChange(state.copy(enabled = it)) }
                )
            }
            Text(
                text = "Create a colorful distortion effect when touching the wallpaper",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(visible = state.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PercentSlider(
                        title = "Intensity",
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.icon_opacity),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        value = state.intensity,
                        onValueChange = { onSettingsChange(state.copy(intensity = it)) },
                        steps = (1f / 0.1f).toInt() - 1,
                    )

                    DurationSlider(
                        title = "Fade duration",
                        durationMillis = state.fadeDurationMillis,
                        onDurationChange = { onSettingsChange(state.copy(fadeDurationMillis = it)) },
                        icon = Icons.Outlined.Timer,
                        durationRange = 0L..3000L,
                        steps = (3000L / 250L).toInt() - 1,
                    )
                }
            }
        }
    }
}

