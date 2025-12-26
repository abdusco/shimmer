package dev.abdus.apps.shimmer.ui.settings

import android.net.Uri
import dev.abdus.apps.shimmer.ChromaticAberrationSettings
import dev.abdus.apps.shimmer.DuotoneBlendMode
import dev.abdus.apps.shimmer.DuotonePreset
import dev.abdus.apps.shimmer.DuotoneSettings
import dev.abdus.apps.shimmer.GestureAction
import dev.abdus.apps.shimmer.GrainSettings
import dev.abdus.apps.shimmer.TapEvent

data class SourcesUiState(
    val currentWallpaperUri: Uri?,
    val currentWallpaperName: String?,
    val imageFolders: List<ImageFolderUiModel>,
    val imageCycleEnabled: Boolean,
    val imageCycleIntervalMillis: Long,
    val cycleImageOnUnlock: Boolean,
)

data class EffectsUiState(
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

data class GesturesUiState(
    val tripleTapAction: GestureAction,
    val twoFingerDoubleTapAction: GestureAction,
    val threeFingerDoubleTapAction: GestureAction,
)

data class SourcesActions(
    val onViewCurrentWallpaper: () -> Unit,
    val onNavigateToFolderDetail: (Long, String) -> Unit,
    val onNavigateToFolderSelection: () -> Unit,
    val onImageCycleEnabledChange: (Boolean) -> Unit,
    val onImageCycleIntervalChange: (Long) -> Unit,
    val onCycleImageOnUnlockChange: (Boolean) -> Unit,
)

data class EffectsActions(
    val onBlurAmountChange: (Float) -> Unit,
    val onDimAmountChange: (Float) -> Unit,
    val onEffectTransitionDurationChange: (Long) -> Unit,
    val onDuotoneEnabledChange: (Boolean) -> Unit,
    val onDuotoneAlwaysOnChange: (Boolean) -> Unit,
    val onDuotoneLightColorChange: (String) -> Unit,
    val onDuotoneDarkColorChange: (String) -> Unit,
    val onDuotoneBlendModeChange: (DuotoneBlendMode) -> Unit,
    val onGrainEnabledChange: (Boolean) -> Unit,
    val onGrainAmountChange: (Float) -> Unit,
    val onGrainScaleChange: (Float) -> Unit,
    val onDuotonePresetSelected: (DuotonePreset) -> Unit,
    val onBlurOnScreenLockChange: (Boolean) -> Unit,
    val onBlurTimeoutEnabledChange: (Boolean) -> Unit,
    val onBlurTimeoutMillisChange: (Long) -> Unit,
    val onChromaticAberrationEnabledChange: (Boolean) -> Unit,
    val onChromaticAberrationIntensityChange: (Float) -> Unit,
    val onChromaticAberrationFadeDurationChange: (Long) -> Unit,
)

data class GesturesActions(
    val onGestureChange: (TapEvent, GestureAction) -> Unit,
)
