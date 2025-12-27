package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
    ElevatedCard(shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                title = "Image Effects",
                description = "Adjust blur and dim effects applied to the wallpaper",
                iconVector = Icons.Outlined.Image,
            )

            PercentSlider(
                title = "Blur amount",
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.icon_blur),
                        contentDescription = null,
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

