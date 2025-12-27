package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.DUOTONE_PRESETS
import dev.abdus.apps.shimmer.DuotoneBlendMode
import dev.abdus.apps.shimmer.DuotonePreset
import dev.abdus.apps.shimmer.DuotoneSettings
import dev.abdus.apps.shimmer.R

@Composable
fun DuotoneSettings(
    state: DuotoneSettings,
    onSettingsChange: (DuotoneSettings) -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                title = "Duotone effect",
                description = "Apply a two-color gradient effect that maps image colors to light and dark tones",
                iconPainter = painterResource(R.drawable.icon_filter),
                actionSlot = {
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { onSettingsChange(state.copy(enabled = it)) },
                    )
                },
            )

            AnimatedVisibility(visible = state.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Always on",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = state.alwaysOn,
                            onCheckedChange = { onSettingsChange(state.copy(alwaysOn = it)) },
                        )
                    }
                    ColorHexField(
                        label = "Light color (#RRGGBB)",
                        value = colorIntToHex(state.lightColor),
                        onValueChange = { hex ->
                            parseColorHex(hex)?.let { onSettingsChange(state.copy(lightColor = it)) }
                        },
                        previewColor = colorIntToComposeColor(state.lightColor),
                    )
                    ColorHexField(
                        label = "Dark color (#RRGGBB)",
                        value = colorIntToHex(state.darkColor),
                        onValueChange = { hex ->
                            parseColorHex(hex)?.let { onSettingsChange(state.copy(darkColor = it)) }
                        },
                        previewColor = colorIntToComposeColor(state.darkColor),
                    )
                    Row {
                        DuotonePresetDropdown(
                            onPresetSelected = { preset ->
                                val index = DUOTONE_PRESETS.indexOf(preset)
                                onSettingsChange(
                                    state.copy(
                                        lightColor = preset.lightColor,
                                        darkColor = preset.darkColor,
                                        presetIndex = index,
                                    ),
                                )
                            },
                        )

                        Spacer(modifier = Modifier.size(8f.dp).weight(1f))

                        DuotoneBlendModeDropdown(
                            selectedBlendMode = state.blendMode,
                            onBlendModeSelected = { onSettingsChange(state.copy(blendMode = it)) },
                        )
                    }
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
            DuotoneBlendMode.SCREEN to "Screen",
        )
    }
    val selectedName = modes.find { it.first == selectedBlendMode }?.second ?: "Normal"

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.icon_blend_mode),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = "Blend mode: $selectedName")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.padding(horizontal = 8.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            modes.forEach { pair ->
                DropdownMenuItem(
                    text = { Text(text = pair.second) },
                    onClick = {
                        expanded = false
                        onBlendModeSelected(pair.first)
                    },
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
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = "Choose preset")
        }
        DropdownMenu(
            expanded = expanded,
            modifier = Modifier.padding(horizontal = 8.dp),
            onDismissRequest = { expanded = false },
        ) {
            Column(
                modifier = Modifier
                    .size(width = 280.dp, height = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                DUOTONE_PRESETS.forEach { preset ->
                    DropdownMenuItem(
                        text = { DuotonePresetOptionRow(preset) },
                        onClick = {
                            expanded = false
                            onPresetSelected(preset)
                        },
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
        shadowElevation = 0.dp,
    ) {}
}
