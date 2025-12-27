package dev.abdus.apps.shimmer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ChromaticAberrationSettings
import dev.abdus.apps.shimmer.DuotoneSettings
import dev.abdus.apps.shimmer.GrainSettings

data class EffectsState(
    val blurAmount: Float,
    val dimAmount: Float,
    val grainSettings: GrainSettings,
    val duotone: DuotoneSettings,
    val chromaticAberration: ChromaticAberrationSettings,
    val effectTransitionDurationMillis: Long,
    val blurOnScreenLock: Boolean,
    val blurTimeoutEnabled: Boolean,
    val blurTimeoutMillis: Long,
)

sealed interface EffectsAction {
    data class SetBlurAmount(val amount: Float) : EffectsAction
    data class SetDimAmount(val amount: Float) : EffectsAction
    data class SetEffectTransitionDuration(val durationMillis: Long) : EffectsAction
    data class UpdateDuotone(val settings: DuotoneSettings) : EffectsAction
    data class UpdateGrain(val settings: GrainSettings) : EffectsAction
    data class UpdateChromaticAberration(val settings: ChromaticAberrationSettings) : EffectsAction
    data class SetBlurOnScreenLock(val enabled: Boolean) : EffectsAction
    data class SetBlurTimeoutEnabled(val enabled: Boolean) : EffectsAction
    data class SetBlurTimeoutMillis(val millis: Long) : EffectsAction
}

@Composable
fun EffectsTab(
    modifier: Modifier = Modifier,
    state: EffectsState,
    onAction: (EffectsAction) -> Unit,
) {
    // Local state for blur slider to provide instant UI feedback and allow debouncing
    var localBlurAmount by remember(state.blurAmount) { mutableFloatStateOf(state.blurAmount) }

    // Debounce blur changes (300ms delay after user stops dragging)
    DebouncedEffect(localBlurAmount, delayMillis = 300) { newValue ->
        if (newValue != state.blurAmount) {
            onAction(EffectsAction.SetBlurAmount(newValue))
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ImageEffectsSettingsSection(
                blurAmount = localBlurAmount,
                dimAmount = state.dimAmount,
                effectTransitionDurationMillis = state.effectTransitionDurationMillis,
                onBlurAmountChange = { localBlurAmount = it },
                onDimAmountChange = { onAction(EffectsAction.SetDimAmount(it)) },
                onEffectTransitionDurationChange = { onAction(EffectsAction.SetEffectTransitionDuration(it)) }
            )
        }

        item {
            GrainSettings(
                state = state.grainSettings,
                onSettingsChange = { onAction(EffectsAction.UpdateGrain(it)) }
            )
        }

        item {
            DuotoneSettings(
                state = state.duotone,
                onSettingsChange = { onAction(EffectsAction.UpdateDuotone(it)) }
            )
        }

        item {
            ChromaticAberrationSettings(
                state = state.chromaticAberration,
                onSettingsChange = { onAction(EffectsAction.UpdateChromaticAberration(it)) }
            )
        }

        item {
            EventsSettingsSection(
                blurOnScreenLock = state.blurOnScreenLock,
                blurTimeoutEnabled = state.blurTimeoutEnabled,
                blurTimeoutMillis = state.blurTimeoutMillis,
                onBlurOnScreenLockChange = { onAction(EffectsAction.SetBlurOnScreenLock(it)) },
                onBlurTimeoutEnabledChange = { onAction(EffectsAction.SetBlurTimeoutEnabled(it)) },
                onBlurTimeoutMillisChange = { onAction(EffectsAction.SetBlurTimeoutMillis(it)) }
            )
        }
    }
}
