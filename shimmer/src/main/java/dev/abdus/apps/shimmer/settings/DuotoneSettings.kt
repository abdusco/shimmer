package dev.abdus.apps.shimmer.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.DuotoneBlendMode
import dev.abdus.apps.shimmer.DuotonePreset
import dev.abdus.apps.shimmer.DUOTONE_PRESETS

@Composable
fun DuotoneSettings(
    enabled: Boolean,
    alwaysOn: Boolean,
    lightColorText: String,
    darkColorText: String,
    lightColorPreview: Color,
    darkColorPreview: Color,
    blendMode: DuotoneBlendMode,
    onEnabledChange: (Boolean) -> Unit,
    onAlwaysOnChange: (Boolean) -> Unit,
    onLightColorChange: (String) -> Unit,
    onDarkColorChange: (String) -> Unit,
    onBlendModeChange: (DuotoneBlendMode) -> Unit,
    onPresetSelected: (DuotonePreset) -> Unit,
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
                verticalAlignment = Alignment.CenterVertically,
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
                        text = "Duotone effect",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "Apply a two-color gradient effect that maps image colors to light and dark tones",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Apply when unblurred",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = alwaysOn,
                            onCheckedChange = onAlwaysOnChange
                        )
                    }
                    ColorHexField(
                        label = "Light color (#RRGGBB)",
                        value = lightColorText,
                        onValueChange = onLightColorChange,
                        previewColor = lightColorPreview
                    )
                    ColorHexField(
                        label = "Dark color (#RRGGBB)",
                        value = darkColorText,
                        onValueChange = onDarkColorChange,
                        previewColor = darkColorPreview
                    )
                    DuotoneBlendModeDropdown(
                        selectedBlendMode = blendMode,
                        onBlendModeSelected = onBlendModeChange
                    )
                    DuotonePresetDropdown(onPresetSelected = onPresetSelected)
                }
            }
        }
    }
}

@Composable
private fun DuotoneBlendModeDropdown(
    selectedBlendMode: DuotoneBlendMode,
    onBlendModeSelected: (DuotoneBlendMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modes = remember {
        listOf(
            DuotoneBlendMode.NORMAL to "Normal",
            DuotoneBlendMode.SOFT_LIGHT to "Soft Light",
            DuotoneBlendMode.SCREEN to "Screen"
        )
    }
    val selectedName = modes.find { it.first == selectedBlendMode }?.second ?: "Normal"
    
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(text = "Blend mode: $selectedName")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            modes.forEach { pair ->
                DropdownMenuItem(
                    text = { Text(text = pair.second) },
                    onClick = {
                        expanded = false
                        onBlendModeSelected(pair.first)
                    }
                )
            }
        }
    }
}

@Composable
private fun DuotonePresetDropdown(
    onPresetSelected: (DuotonePreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) {
            Text(text = "Choose preset")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Column(
                modifier = Modifier
                    .size(width = 280.dp, height = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DUOTONE_PRESETS.forEach { preset ->
                    DropdownMenuItem(
                        text = { DuotonePresetOptionRow(preset) },
                        onClick = {
                            expanded = false
                            onPresetSelected(preset)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DuotonePresetOptionRow(preset: DuotonePreset) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row {
            PresetColorSwatch(colorIntToComposeColor(preset.darkColor))
            PresetColorSwatch(colorIntToComposeColor(preset.lightColor))
        }
        Text(text = preset.name)
    }
}

@Composable
private fun PresetColorSwatch(color: Color) {
    Surface(
        modifier = Modifier.size(24.dp),
        color = color,
        shadowElevation = 0.dp
    ) {}
}

