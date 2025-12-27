package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
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
    ElevatedCard(
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(
                title = "Chromatic aberration",
                description = "Create a colorful distortion effect when touching the wallpaper",
                iconVector = Icons.Outlined.RemoveRedEye,
                actionSlot = {
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { onSettingsChange(state.copy(enabled = it)) }
                    )
                }
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

