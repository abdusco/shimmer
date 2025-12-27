package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.GrainSettings
import dev.abdus.apps.shimmer.R

@Composable
fun GrainSettings(
    state: GrainSettings,
    onSettingsChange: (GrainSettings) -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                title = "Film grain",
                description = "Add a film grain texture effect to give images a vintage film look",
                iconPainter = painterResource(R.drawable.icon_grain),
                actionSlot = {
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { onSettingsChange(state.copy(enabled = it)) },
                    )
                }
            )

            AnimatedVisibility(visible = state.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PercentSlider(
                        title = "Grain amount",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.RemoveRedEye,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        value = state.amount,
                        onValueChange = { onSettingsChange(state.copy(amount = it)) },
                        steps = (1f / 0.1f).toInt() - 1,
                    )

                    PercentSlider(
                        title = "Grain size",
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.RemoveRedEye,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        value = state.scale,
                        onValueChange = { onSettingsChange(state.copy(scale = it)) },
                        steps = (1f / 0.1f).toInt() - 1,
                    )
                }
            }
        }
    }
}

