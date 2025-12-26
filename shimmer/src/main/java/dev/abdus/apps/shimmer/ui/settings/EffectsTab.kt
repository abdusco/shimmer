package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun EffectsTab(
    modifier: Modifier = Modifier,
    state: EffectsUiState,
    actions: EffectsActions,
) {
    // Local state for sliders to provide instant UI feedback
    var localBlurAmount by remember(state.blurAmount) { mutableFloatStateOf(state.blurAmount) }
    var localDimAmount by remember(state.dimAmount) { mutableFloatStateOf(state.dimAmount) }
    var localGrainEnabled by remember(state.grainSettings.enabled) { mutableStateOf(state.grainSettings.enabled) }
    var localGrainAmount by remember(state.grainSettings.amount) { mutableFloatStateOf(state.grainSettings.amount) }
    var localGrainScale by remember(state.grainSettings.scale) { mutableFloatStateOf(state.grainSettings.scale) }
    var localChromaticAberrationIntensity by remember(state.chromaticAberration.intensity) {
        mutableFloatStateOf(state.chromaticAberration.intensity)
    }
    var localChromaticAberrationFadeDuration by remember(state.chromaticAberration.fadeDurationMillis) {
        mutableLongStateOf(state.chromaticAberration.fadeDurationMillis)
    }

    // Debounce blur changes (300ms delay after user stops dragging)
    DebouncedEffect(localBlurAmount, delayMillis = 300) { newValue ->
        if (newValue != state.blurAmount) {
            actions.onBlurAmountChange(newValue)
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
            ImageEffectsSettingsSection(
                blurAmount = localBlurAmount,
                dimAmount = localDimAmount,
                effectTransitionDurationMillis = state.effectTransitionDurationMillis,
                onBlurAmountChange = { localBlurAmount = it },
                onDimAmountChange = {
                    localDimAmount = it
                    actions.onDimAmountChange(it)
                },
                onEffectTransitionDurationChange = actions.onEffectTransitionDurationChange
            )
        }

        item {
            GrainSettings(
                enabled = localGrainEnabled,
                amount = localGrainAmount,
                scale = localGrainScale,
                onEnabledChange = {
                    localGrainEnabled = it
                    actions.onGrainEnabledChange(it)
                },
                onAmountChange = {
                    localGrainAmount = it
                    actions.onGrainAmountChange(it)
                    if (!localGrainEnabled) {
                        localGrainEnabled = true
                        actions.onGrainEnabledChange(true)
                    }
                },
                onScaleChange = {
                    localGrainScale = it
                    actions.onGrainScaleChange(it)
                    if (!localGrainEnabled) {
                        localGrainEnabled = true
                        actions.onGrainEnabledChange(true)
                    }
                }
            )
        }

        item {
            DuotoneSettings(
                enabled = state.duotone.enabled,
                alwaysOn = state.duotone.alwaysOn,
                lightColorText = colorIntToHex(state.duotone.lightColor),
                darkColorText = colorIntToHex(state.duotone.darkColor),
                lightColorPreview = colorIntToComposeColor(state.duotone.lightColor),
                darkColorPreview = colorIntToComposeColor(state.duotone.darkColor),
                blendMode = state.duotone.blendMode,
                onEnabledChange = actions.onDuotoneEnabledChange,
                onAlwaysOnChange = actions.onDuotoneAlwaysOnChange,
                onLightColorChange = actions.onDuotoneLightColorChange,
                onDarkColorChange = actions.onDuotoneDarkColorChange,
                onBlendModeChange = actions.onDuotoneBlendModeChange,
                onPresetSelected = { preset ->
                    actions.onDuotonePresetSelected(preset)
                }
            )
        }

        item {
            ChromaticAberrationSettings(
                enabled = state.chromaticAberration.enabled,
                intensity = localChromaticAberrationIntensity,
                fadeDurationMillis = localChromaticAberrationFadeDuration,
                onEnabledChange = actions.onChromaticAberrationEnabledChange,
                onIntensityChange = {
                    localChromaticAberrationIntensity = it
                    actions.onChromaticAberrationIntensityChange(it)
                },
                onFadeDurationChange = {
                    localChromaticAberrationFadeDuration = it
                    actions.onChromaticAberrationFadeDurationChange(it)
                }
            )
        }

        item {
            EventsSettingsSection(
                blurOnScreenLock = state.blurOnScreenLock,
                blurTimeoutEnabled = state.blurTimeoutEnabled,
                blurTimeoutMillis = state.blurTimeoutMillis,
                onBlurOnScreenLockChange = actions.onBlurOnScreenLockChange,
                onBlurTimeoutEnabledChange = actions.onBlurTimeoutEnabledChange,
                onBlurTimeoutMillisChange = actions.onBlurTimeoutMillisChange
            )
        }
    }
}
