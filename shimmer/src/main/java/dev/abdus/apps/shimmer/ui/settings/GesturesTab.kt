package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.GestureAction
import dev.abdus.apps.shimmer.R
import dev.abdus.apps.shimmer.TapEvent

@Composable
fun GesturesTab(
    modifier: Modifier = Modifier,
    state: GesturesUiState,
    actions: GesturesActions,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PADDING_X,
            top = PADDING_Y,
            end = PADDING_X,
            bottom = PADDING_Y,
        ),
        verticalArrangement = Arrangement.spacedBy(PADDING_X),
    ) {
        item {
            // Use a Card or Surface with a subtler look
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.extraLarge,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header Section
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TouchApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Gestures",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Bind gestures to quick actions on the wallpaper.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

                    GestureActionRow(
                        action = state.tripleTapAction,
                        onActionChange = { actions.onGestureChange(TapEvent.TRIPLE_TAP, it) },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.icon_one_finger),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Triple tap (1 finger)")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp)

                    GestureActionRow(
                        action = state.twoFingerDoubleTapAction,
                        onActionChange = { actions.onGestureChange(TapEvent.TWO_FINGER_DOUBLE_TAP, it) },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.icon_two_fingers),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Double tap (2 fingers)")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp)

                    GestureActionRow(
                        action = state.threeFingerDoubleTapAction,
                        onActionChange = { actions.onGestureChange(TapEvent.THREE_FINGER_DOUBLE_TAP, it) },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.icon_three_fingers),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Double tap (3 fingers)")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GestureActionRow(
    action: GestureAction,
    onActionChange: (GestureAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val actionLabel = remember(action) { gestureActionLabel(action) }
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            content()
        }

        Box {
            // Using a TextButton or a simple Box makes it look cleaner than an OutlinedButton
            TextButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.padding(horizontal = 8.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                GESTURE_ACTION_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                color = if (option.action == action)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            expanded = false
                            onActionChange(option.action)
                        },
                        // Highlight the selected item
                        leadingIcon = if (option.action == action) {
                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp)) }
                        } else null
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
    GestureActionOption(GestureAction.FAVORITE, "Favorite"),
    GestureActionOption(GestureAction.RANDOM_DUOTONE, "Random duotone"),
    GestureActionOption(GestureAction.NEXT_IMAGE, "Next image"),
    GestureActionOption(GestureAction.NONE, "None"),
)

private fun gestureActionLabel(action: GestureAction): String {
    return GESTURE_ACTION_OPTIONS.firstOrNull { it.action == action }?.label ?: "None"
}
