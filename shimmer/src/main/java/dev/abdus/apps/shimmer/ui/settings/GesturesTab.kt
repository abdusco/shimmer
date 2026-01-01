package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.GestureAction
import dev.abdus.apps.shimmer.GestureSettings
import dev.abdus.apps.shimmer.R
import dev.abdus.apps.shimmer.TapGesture

data class GesturesState(
    val settings: GestureSettings,
)

sealed interface GesturesAction {
    data class SetGestureAction(val event: TapGesture, val action: GestureAction) : GesturesAction
}

@Composable
fun GesturesTab(
    modifier: Modifier = Modifier,
    state: GesturesState,
    onAction: (GesturesAction) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TouchGesturesSection(state, onAction)
        }
    }
}

@Composable
private fun TouchGesturesSection(state: GesturesState, onAction: (GesturesAction) -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                title = "Gestures",
                description = "Use multi-touch gestures to perform quick actions on the wallpaper",
                iconVector = Icons.Outlined.TouchApp,
            )

            GestureActionRow(
                title = "Triple tap (1 finger)",
                action = state.settings.tripleTapAction,
                onActionChange = { onAction(GesturesAction.SetGestureAction(TapGesture.TRIPLE_TAP, it)) },
                iconPainter = painterResource(id = R.drawable.icon_one_finger),
            )

            GestureActionRow(
                title = "Double tap (2 fingers)",
                iconPainter = painterResource(id = R.drawable.icon_two_fingers),
                action = state.settings.twoFingerDoubleTapAction,
                onActionChange = { onAction(GesturesAction.SetGestureAction(TapGesture.TWO_FINGER_DOUBLE_TAP, it)) },
            )

            GestureActionRow(
                title = "Double tap (3 fingers)",
                iconPainter = painterResource(id = R.drawable.icon_three_fingers),
                action = state.settings.threeFingerDoubleTapAction,
                onActionChange = { onAction(GesturesAction.SetGestureAction(TapGesture.THREE_FINGER_DOUBLE_TAP, it)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureActionRow(
    title: String,
    iconPainter: Painter,

    action: GestureAction,
    onActionChange: (GestureAction) -> Unit,
) {
    val actionLabel = remember(action) { gestureActionLabel(action) }
    var expanded by remember { mutableStateOf(false) }

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f)) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.size(8.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(modifier = Modifier.size(8.dp))

        Box {
            TextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.padding(horizontal = 8.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                GESTURE_ACTION_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                color = if (option.action == action)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            expanded = false
                            onActionChange(option.action)
                        },
                        leadingIcon = if (option.action == action) {
                            { Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                    )
                }
            }
        }
    }
}

private data class GestureActionOption(
    val action: GestureAction,
    val label: String,
)

private val GESTURE_ACTION_OPTIONS = listOf(
    GestureActionOption(GestureAction.TOGGLE_BLUR, "Toggle blur"),
    GestureActionOption(GestureAction.ADD_TO_FAVORITES, "Add to favorites"),
    GestureActionOption(GestureAction.RANDOM_DUOTONE, "Random duotone"),
    GestureActionOption(GestureAction.NEXT_IMAGE, "Next image"),
    GestureActionOption(GestureAction.NONE, "None"),
)

private fun gestureActionLabel(action: GestureAction): String {
    return GESTURE_ACTION_OPTIONS.firstOrNull { it.action == action }?.label ?: "None"
}
