package dev.abdus.apps.shimmer.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.RemoveRedEye
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
fun GrainSettings(
    enabled: Boolean,
    amount: Float,
    scale: Float,
    onEnabledChange: (Boolean) -> Unit,
    onAmountChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
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
                        imageVector = Icons.Outlined.Contrast,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Film grain",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "Add a film grain texture effect to give images a vintage film look",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PercentSlider(
                        title = "Grain amount",
                        icon = Icons.Outlined.RemoveRedEye,
                        value = amount,
                        onValueChange = onAmountChange,
                        steps = (1f / 0.1f).toInt() - 1,
                    )
                    
                    PercentSlider(
                        title = "Grain size",
                        icon = Icons.Outlined.RemoveRedEye,
                        value = scale,
                        onValueChange = onScaleChange,
                        steps = (1f / 0.1f).toInt() - 1,
                    )
                }
            }
        }
    }
}

