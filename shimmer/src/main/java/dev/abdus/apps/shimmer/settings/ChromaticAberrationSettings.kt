package dev.abdus.apps.shimmer.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp

@Composable
fun ChromaticAberrationSettings(
    enabled: Boolean,
    intensity: Float,
    fadeDurationMillis: Long,
    onEnabledChange: (Boolean) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onFadeDurationChange: (Long) -> Unit,
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
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Chromatic aberration",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "Create a colorful distortion effect when touching the wallpaper",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PercentSlider(
                        title = "Intensity",
                        icon = Icons.Outlined.RemoveRedEye,
                        value = intensity,
                        onValueChange = onIntensityChange,
                        steps = (1f / 0.1f).toInt() - 1,
                    )
                    
                    DurationSlider(
                        title = "Fade duration",
                        durationMillis = fadeDurationMillis,
                        onDurationChange = onFadeDurationChange,
                        icon = Icons.Outlined.Timer,
                        durationRange = 0L..3000L,
                        steps = (3000L / 250L).toInt() - 1,
                    )
                }
            }
        }
    }
}

