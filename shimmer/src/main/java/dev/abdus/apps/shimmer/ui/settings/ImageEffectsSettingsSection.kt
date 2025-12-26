package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.R

@Composable
fun ImageEffectsSettingsSection(
    blurAmount: Float,
    dimAmount: Float,
    effectTransitionDurationMillis: Long,
    onBlurAmountChange: (Float) -> Unit,
    onDimAmountChange: (Float) -> Unit,
    onEffectTransitionDurationChange: (Long) -> Unit,
) {
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = PADDING_X, vertical = PADDING_Y),
            verticalArrangement = Arrangement.spacedBy(PADDING_Y),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = "Image Effects",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = "Adjust blur and dim effects applied to the wallpaper images",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PercentSlider(
                title = "Blur amount",
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.icon_blur),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                value = blurAmount,
                onValueChange = onBlurAmountChange,
                steps = (1f / 0.05f).toInt() - 1,
            )
            PercentSlider(
                title = "Dim amount",
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Brightness4,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                value = dimAmount,
                onValueChange = onDimAmountChange,
                steps = (1f / 0.05f).toInt() - 1,
            )
            DurationSlider(
                title = "Effect transition duration",
                durationMillis = effectTransitionDurationMillis,
                onDurationChange = onEffectTransitionDurationChange,
                durationRange = 0L..3000L,
                steps = (3000L / 250L).toInt() - 1,
                icon = Icons.Outlined.Speed,
            )
        }
    }
}

