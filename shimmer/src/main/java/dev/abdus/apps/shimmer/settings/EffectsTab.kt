package dev.abdus.apps.shimmer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.abdus.apps.shimmer.ChromaticAberrationSettings
import dev.abdus.apps.shimmer.DuotoneBlendMode
import dev.abdus.apps.shimmer.DuotonePreset
import dev.abdus.apps.shimmer.DuotoneSettings
import dev.abdus.apps.shimmer.GrainSettings
import dev.abdus.apps.shimmer.DUOTONE_PRESETS

@Composable
fun EffectsTab(
    modifier: Modifier = Modifier,
    blurAmount: Float,
    dimAmount: Float,
    grainSettings: GrainSettings,
    duotone: DuotoneSettings,
    chromaticAberration: ChromaticAberrationSettings,
    effectTransitionDurationMillis: Long,
    blurOnScreenLock: Boolean,
    blurTimeoutEnabled: Boolean,
    blurTimeoutMillis: Long,
    onBlurAmountChange: (Float) -> Unit,
    onDimAmountChange: (Float) -> Unit,
    onEffectTransitionDurationChange: (Long) -> Unit,
    onDuotoneEnabledChange: (Boolean) -> Unit,
    onDuotoneAlwaysOnChange: (Boolean) -> Unit,
    onDuotoneLightColorChange: (String) -> Unit,
    onDuotoneDarkColorChange: (String) -> Unit,
    onDuotoneBlendModeChange: (DuotoneBlendMode) -> Unit,
    onGrainEnabledChange: (Boolean) -> Unit,
    onGrainAmountChange: (Float) -> Unit,
    onGrainScaleChange: (Float) -> Unit,
    onDuotonePresetSelected: (DuotonePreset) -> Unit,
    onBlurOnScreenLockChange: (Boolean) -> Unit,
    onBlurTimeoutEnabledChange: (Boolean) -> Unit,
    onBlurTimeoutMillisChange: (Long) -> Unit,
    onChromaticAberrationEnabledChange: (Boolean) -> Unit,
    onChromaticAberrationIntensityChange: (Float) -> Unit,
    onChromaticAberrationFadeDurationChange: (Long) -> Unit,
) {
    // Local state for sliders to provide instant UI feedback
    var localBlurAmount by remember { mutableFloatStateOf(blurAmount) }
    var localDimAmount by remember { mutableFloatStateOf(dimAmount) }
    var localGrainEnabled by remember { mutableStateOf(grainSettings.enabled) }
    var localGrainAmount by remember { mutableFloatStateOf(grainSettings.amount) }
    var localGrainScale by remember { mutableFloatStateOf(grainSettings.scale) }
    var localChromaticAberrationIntensity by remember { mutableFloatStateOf(chromaticAberration.intensity) }
    var localChromaticAberrationFadeDuration by remember { mutableLongStateOf(chromaticAberration.fadeDurationMillis) }

    // Sync local state when preferences change externally
    LaunchedEffect(blurAmount) {
        localBlurAmount = blurAmount
    }

    LaunchedEffect(dimAmount) {
        localDimAmount = dimAmount
    }

    LaunchedEffect(grainSettings.enabled) {
        localGrainEnabled = grainSettings.enabled
    }

    LaunchedEffect(grainSettings.amount) {
        localGrainAmount = grainSettings.amount
    }

    LaunchedEffect(grainSettings.scale) {
        localGrainScale = grainSettings.scale
    }

    LaunchedEffect(chromaticAberration.intensity) {
        localChromaticAberrationIntensity = chromaticAberration.intensity
    }

    LaunchedEffect(chromaticAberration.fadeDurationMillis) {
        localChromaticAberrationFadeDuration = chromaticAberration.fadeDurationMillis
    }

    // Debounce blur changes (300ms delay after user stops dragging)
    DebouncedEffect(localBlurAmount, delayMillis = 300) { newValue ->
        if (newValue != blurAmount) {
            onBlurAmountChange(newValue)
        }
    }

    // Debounce dim changes (300ms delay after user stops dragging)
    DebouncedEffect(localDimAmount, delayMillis = 300) { newValue ->
        if (newValue != dimAmount) {
            onDimAmountChange(newValue)
        }
    }

    DebouncedEffect(localGrainAmount, delayMillis = 300) { newValue ->
        if (newValue != grainSettings.amount) {
            onGrainAmountChange(newValue)
        }
    }

    DebouncedEffect(localGrainScale, delayMillis = 300) { newValue ->
        if (newValue != grainSettings.scale) {
            onGrainScaleChange(newValue)
        }
    }

    DebouncedEffect(localChromaticAberrationIntensity, delayMillis = 300) { newValue ->
        if (newValue != chromaticAberration.intensity) {
            onChromaticAberrationIntensityChange(newValue)
        }
    }

    DebouncedEffect(localChromaticAberrationFadeDuration, delayMillis = 300) { newValue ->
        if (newValue != chromaticAberration.fadeDurationMillis) {
            onChromaticAberrationFadeDurationChange(newValue)
        }
    }

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
            ImageEffectsSettings(
                blurAmount = localBlurAmount,
                dimAmount = localDimAmount,
                effectTransitionDurationMillis = effectTransitionDurationMillis,
                onBlurAmountChange = { localBlurAmount = it },
                onDimAmountChange = { localDimAmount = it },
                onEffectTransitionDurationChange = onEffectTransitionDurationChange
            )
        }

        item {
            GrainSettings(
                enabled = localGrainEnabled,
                amount = localGrainAmount,
                scale = localGrainScale,
                onEnabledChange = {
                    localGrainEnabled = it
                    onGrainEnabledChange(it)
                },
                onAmountChange = {
                    localGrainAmount = it
                    if (!localGrainEnabled) {
                        localGrainEnabled = true
                        onGrainEnabledChange(true)
                    }
                },
                onScaleChange = {
                    localGrainScale = it
                    if (!localGrainEnabled) {
                        localGrainEnabled = true
                        onGrainEnabledChange(true)
                    }
                }
            )
        }

        item {
            DuotoneSettings(
                enabled = duotone.enabled,
                alwaysOn = duotone.alwaysOn,
                lightColorText = colorIntToHex(duotone.lightColor),
                darkColorText = colorIntToHex(duotone.darkColor),
                lightColorPreview = colorIntToComposeColor(duotone.lightColor),
                darkColorPreview = colorIntToComposeColor(duotone.darkColor),
                blendMode = duotone.blendMode,
                onEnabledChange = onDuotoneEnabledChange,
                onAlwaysOnChange = onDuotoneAlwaysOnChange,
                onLightColorChange = onDuotoneLightColorChange,
                onDarkColorChange = onDuotoneDarkColorChange,
                onBlendModeChange = onDuotoneBlendModeChange,
                onPresetSelected = { preset ->
                    onDuotonePresetSelected(preset)
                    val presetIndex = DUOTONE_PRESETS.indexOf(preset)
                    if (presetIndex >= 0) {
                        // Preset selection is handled by the parent
                    }
                }
            )
        }

        item {
            ChromaticAberrationSettings(
                enabled = chromaticAberration.enabled,
                intensity = localChromaticAberrationIntensity,
                fadeDurationMillis = localChromaticAberrationFadeDuration,
                onEnabledChange = onChromaticAberrationEnabledChange,
                onIntensityChange = { localChromaticAberrationIntensity = it },
                onFadeDurationChange = { localChromaticAberrationFadeDuration = it }
            )
        }

        item {
            EventsSettings(
                blurOnScreenLock = blurOnScreenLock,
                blurTimeoutEnabled = blurTimeoutEnabled,
                blurTimeoutMillis = blurTimeoutMillis,
                onBlurOnScreenLockChange = onBlurOnScreenLockChange,
                onBlurTimeoutEnabledChange = onBlurTimeoutEnabledChange,
                onBlurTimeoutMillisChange = onBlurTimeoutMillisChange
            )
        }
    }
}

