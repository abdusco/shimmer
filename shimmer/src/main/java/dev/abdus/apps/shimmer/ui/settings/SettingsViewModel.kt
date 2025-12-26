package dev.abdus.apps.shimmer.ui.settings

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abdus.apps.shimmer.Actions
import dev.abdus.apps.shimmer.ChromaticAberrationSettings
import dev.abdus.apps.shimmer.DUOTONE_PRESETS
import dev.abdus.apps.shimmer.DuotoneBlendMode
import dev.abdus.apps.shimmer.DuotonePreset
import dev.abdus.apps.shimmer.DuotoneSettings
import dev.abdus.apps.shimmer.GestureAction
import dev.abdus.apps.shimmer.GrainSettings
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.TapEvent
import dev.abdus.apps.shimmer.WallpaperPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val selectedTab: SettingsTab = SettingsTab.SOURCES,
    val blurAmount: Float = WallpaperPreferences.DEFAULT_BLUR_AMOUNT,
    val dimAmount: Float = WallpaperPreferences.DEFAULT_DIM_AMOUNT,
    val grainSettings: GrainSettings = GrainSettings(
        WallpaperPreferences.DEFAULT_GRAIN_ENABLED,
        WallpaperPreferences.DEFAULT_GRAIN_AMOUNT,
        WallpaperPreferences.DEFAULT_GRAIN_SCALE,
    ),
    val duotoneSettings: DuotoneSettings = DuotoneSettings(
        WallpaperPreferences.DEFAULT_DUOTONE_ENABLED,
        WallpaperPreferences.DEFAULT_DUOTONE_ALWAYS_ON,
        WallpaperPreferences.DEFAULT_DUOTONE_LIGHT,
        WallpaperPreferences.DEFAULT_DUOTONE_DARK,
        -1,
        DuotoneBlendMode.NORMAL,
    ),
    val chromaticAberration: ChromaticAberrationSettings = ChromaticAberrationSettings(
        WallpaperPreferences.DEFAULT_CHROMATIC_ABERRATION_ENABLED,
        WallpaperPreferences.DEFAULT_CHROMATIC_ABERRATION_INTENSITY,
        WallpaperPreferences.DEFAULT_CHROMATIC_ABERRATION_FADE_DURATION,
    ),
    val imageCycleIntervalMillis: Long = WallpaperPreferences.DEFAULT_TRANSITION_INTERVAL_MILLIS,
    val imageCycleEnabled: Boolean = true,
    val effectTransitionDurationMillis: Long = WallpaperPreferences.DEFAULT_EFFECT_TRANSITION_DURATION_MILLIS,
    val blurOnScreenLock: Boolean = false,
    val blurTimeoutEnabled: Boolean = false,
    val blurTimeoutMillis: Long = WallpaperPreferences.DEFAULT_BLUR_TIMEOUT_MILLIS,
    val cycleImageOnUnlock: Boolean = false,
    val currentWallpaperUri: Uri? = null,
    val currentWallpaperName: String? = null,
    val imageFolders: List<ImageFolderUiModel> = emptyList(),
    val tripleTapAction: GestureAction = WallpaperPreferences.DEFAULT_TRIPLE_TAP_ACTION,
    val twoFingerDoubleTapAction: GestureAction = WallpaperPreferences.DEFAULT_TWO_FINGER_DOUBLE_TAP_ACTION,
    val threeFingerDoubleTapAction: GestureAction = WallpaperPreferences.DEFAULT_THREE_FINGER_DOUBLE_TAP_ACTION,
) {
    val sources: SourcesUiState
        get() = SourcesUiState(
            currentWallpaperUri = currentWallpaperUri,
            currentWallpaperName = currentWallpaperName,
            imageFolders = imageFolders,
            imageCycleEnabled = imageCycleEnabled,
            imageCycleIntervalMillis = imageCycleIntervalMillis,
            cycleImageOnUnlock = cycleImageOnUnlock,
        )

    val effects: EffectsUiState
        get() = EffectsUiState(
            blurAmount = blurAmount,
            dimAmount = dimAmount,
            grainSettings = grainSettings,
            duotone = duotoneSettings,
            chromaticAberration = chromaticAberration,
            effectTransitionDurationMillis = effectTransitionDurationMillis,
            blurOnScreenLock = blurOnScreenLock,
            blurTimeoutEnabled = blurTimeoutEnabled,
            blurTimeoutMillis = blurTimeoutMillis,
        )

    val gestures: GesturesUiState
        get() = GesturesUiState(
            tripleTapAction = tripleTapAction,
            twoFingerDoubleTapAction = twoFingerDoubleTapAction,
            threeFingerDoubleTapAction = threeFingerDoubleTapAction,
        )
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ImageFolderRepository(application)
    private val preferences = WallpaperPreferences.create(application)

    val sourcesActions = SourcesActions(
        onViewCurrentWallpaper = ::viewCurrentWallpaper,
        onImageCycleEnabledChange = ::setImageCycleEnabled,
        onImageCycleIntervalChange = ::setImageCycleIntervalMillis,
        onCycleImageOnUnlockChange = ::setCycleImageOnUnlock,
        onNavigateToFolderDetail = ::navigateToFolderDetail,
        onNavigateToFolderSelection = ::navigateToFolderSelection,
    )

    val effectsActions = EffectsActions(
        onBlurAmountChange = ::setBlurAmount,
        onDimAmountChange = ::setDimAmount,
        onEffectTransitionDurationChange = ::setEffectTransitionDurationMillis,
        onDuotoneEnabledChange = ::setDuotoneEnabled,
        onDuotoneAlwaysOnChange = ::setDuotoneAlwaysOn,
        onDuotoneLightColorChange = ::setDuotoneLightColor,
        onDuotoneDarkColorChange = ::setDuotoneDarkColor,
        onDuotoneBlendModeChange = ::setDuotoneBlendMode,
        onGrainEnabledChange = ::setGrainEnabled,
        onGrainAmountChange = ::setGrainAmount,
        onGrainScaleChange = ::setGrainScale,
        onDuotonePresetSelected = ::applyDuotonePreset,
        onBlurOnScreenLockChange = ::setBlurOnScreenLock,
        onBlurTimeoutEnabledChange = ::setBlurTimeoutEnabled,
        onBlurTimeoutMillisChange = ::setBlurTimeoutMillis,
        onChromaticAberrationEnabledChange = ::setChromaticAberrationEnabled,
        onChromaticAberrationIntensityChange = ::setChromaticAberrationIntensity,
        onChromaticAberrationFadeDurationChange = ::setChromaticAberrationFadeDuration,
    )

    val gesturesActions = GesturesActions(onGestureChange = ::setGestureAction)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        updateStateFromPrefs(key)
    }

    init {
        preferences.registerListener(prefsListener)
        loadInitialState()
        observeRepository()
    }

    override fun onCleared() {
        super.onCleared()
        preferences.unregisterListener(prefsListener)
    }

    private fun loadInitialState() {
        _uiState.update { current ->
            current.copy(
                selectedTab = SettingsTab.entries.getOrElse(preferences.getLastSelectedTab()) { SettingsTab.SOURCES },
                blurAmount = preferences.getBlurAmount(),
                dimAmount = preferences.getDimAmount(),
                grainSettings = preferences.getGrainSettings(),
                duotoneSettings = preferences.getDuotoneSettings(),
                chromaticAberration = preferences.getChromaticAberrationSettings(),
                imageCycleIntervalMillis = preferences.getTransitionIntervalMillis(),
                imageCycleEnabled = preferences.isTransitionEnabled(),
                effectTransitionDurationMillis = preferences.getEffectTransitionDurationMillis(),
                blurOnScreenLock = preferences.isBlurOnScreenLockEnabled(),
                blurTimeoutEnabled = preferences.isBlurTimeoutEnabled(),
                blurTimeoutMillis = preferences.getBlurTimeoutMillis(),
                cycleImageOnUnlock = preferences.isChangeImageOnUnlockEnabled(),
                tripleTapAction = preferences.getGestureAction(TapEvent.TRIPLE_TAP),
                twoFingerDoubleTapAction = preferences.getGestureAction(TapEvent.TWO_FINGER_DOUBLE_TAP),
                threeFingerDoubleTapAction = preferences.getGestureAction(TapEvent.THREE_FINGER_DOUBLE_TAP),
            )
        }
    }

    private fun observeRepository() {
        viewModelScope.launch {
            repository.foldersMetadataFlow.collect { metadata ->
                val folders = metadata.map { (uri, meta) ->
                    ImageFolderUiModel(
                        id = meta.folderId,
                        uri = uri,
                        displayName = repository.getFolderDisplayName(uri),
                        displayPath = repository.formatTreeUriPath(uri),
                        thumbnailUri = meta.thumbnailUri,
                        imageCount = meta.imageCount,
                        enabled = meta.isEnabled,
                        isLocal = meta.isLocal,
                    )
                }
                _uiState.update { it.copy(imageFolders = folders) }
            }
        }

        viewModelScope.launch {
            repository.currentImageUriFlow.collect { uri ->
                val name = repository.getCurrentImageName(uri)
                _uiState.update {
                    it.copy(
                        currentWallpaperUri = uri,
                        currentWallpaperName = name,
                    )
                }
            }
        }
    }

    private fun updateStateFromPrefs(key: String?) {
        if (key == null) return
        _uiState.update { current ->
            when (key) {
                WallpaperPreferences.KEY_BLUR_AMOUNT -> current.copy(blurAmount = preferences.getBlurAmount())
                WallpaperPreferences.KEY_DIM_AMOUNT -> current.copy(dimAmount = preferences.getDimAmount())
                WallpaperPreferences.KEY_GRAIN_SETTINGS -> current.copy(grainSettings = preferences.getGrainSettings())
                WallpaperPreferences.KEY_DUOTONE_SETTINGS -> current.copy(duotoneSettings = preferences.getDuotoneSettings())
                WallpaperPreferences.KEY_CHROMATIC_ABERRATION_SETTINGS -> current.copy(chromaticAberration = preferences.getChromaticAberrationSettings())
                WallpaperPreferences.KEY_TRANSITION_INTERVAL -> current.copy(imageCycleIntervalMillis = preferences.getTransitionIntervalMillis())
                WallpaperPreferences.KEY_TRANSITION_ENABLED -> current.copy(imageCycleEnabled = preferences.isTransitionEnabled())
                WallpaperPreferences.KEY_EFFECT_TRANSITION_DURATION -> current.copy(effectTransitionDurationMillis = preferences.getEffectTransitionDurationMillis())
                WallpaperPreferences.KEY_BLUR_ON_SCREEN_LOCK -> current.copy(blurOnScreenLock = preferences.isBlurOnScreenLockEnabled())
                WallpaperPreferences.KEY_CHANGE_IMAGE_ON_UNLOCK -> current.copy(cycleImageOnUnlock = preferences.isChangeImageOnUnlockEnabled())
                WallpaperPreferences.KEY_BLUR_TIMEOUT_ENABLED -> current.copy(blurTimeoutEnabled = preferences.isBlurTimeoutEnabled())
                WallpaperPreferences.KEY_BLUR_TIMEOUT_MILLIS -> current.copy(blurTimeoutMillis = preferences.getBlurTimeoutMillis())
                WallpaperPreferences.KEY_GESTURE_TRIPLE_TAP_ACTION -> current.copy(tripleTapAction = preferences.getGestureAction(TapEvent.TRIPLE_TAP))
                WallpaperPreferences.KEY_GESTURE_TWO_FINGER_DOUBLE_TAP_ACTION -> current.copy(twoFingerDoubleTapAction = preferences.getGestureAction(TapEvent.TWO_FINGER_DOUBLE_TAP))
                WallpaperPreferences.KEY_GESTURE_THREE_FINGER_DOUBLE_TAP_ACTION -> current.copy(threeFingerDoubleTapAction = preferences.getGestureAction(TapEvent.THREE_FINGER_DOUBLE_TAP))
                WallpaperPreferences.KEY_LAST_SELECTED_TAB -> current.copy(selectedTab = SettingsTab.entries.getOrElse(preferences.getLastSelectedTab()) { SettingsTab.SOURCES })
                else -> current
            }
        }
    }

    fun setTab(tab: SettingsTab) {
        preferences.setLastSelectedTab(tab.ordinal)
    }

    fun setBlurAmount(amount: Float) = preferences.setBlurAmount(amount)
    fun setDimAmount(amount: Float) = preferences.setDimAmount(amount)

    fun setGrainEnabled(enabled: Boolean) = preferences.setGrainEnabled(enabled)
    fun setGrainAmount(amount: Float) = preferences.setGrainAmount(amount)
    fun setGrainScale(scale: Float) = preferences.setGrainScale(scale)

    fun setDuotoneEnabled(enabled: Boolean) = preferences.setDuotoneEnabled(enabled)
    fun setDuotoneAlwaysOn(alwaysOn: Boolean) = preferences.setDuotoneAlwaysOn(alwaysOn)
    fun setDuotoneBlendMode(mode: DuotoneBlendMode) = preferences.setDuotoneBlendMode(mode)

    fun setDuotoneLightColor(colorHex: String) {
        parseColorHex(colorHex)?.let { preferences.setDuotoneLightColor(it) }
    }

    fun setDuotoneDarkColor(colorHex: String) {
        parseColorHex(colorHex)?.let { preferences.setDuotoneDarkColor(it) }
    }

    fun applyDuotonePreset(preset: DuotonePreset) {
        preferences.setDuotoneLightColor(preset.lightColor)
        preferences.setDuotoneDarkColor(preset.darkColor)
        val index = DUOTONE_PRESETS.indexOf(preset)
        if (index >= 0) preferences.setDuotonePresetIndex(index)
    }


    fun setChromaticAberrationEnabled(enabled: Boolean) = preferences.setChromaticAberrationEnabled(enabled)
    fun setChromaticAberrationIntensity(intensity: Float) = preferences.setChromaticAberrationIntensity(intensity)
    fun setChromaticAberrationFadeDuration(duration: Long) = preferences.setChromaticAberrationFadeDuration(duration)

    fun setImageCycleEnabled(enabled: Boolean) = preferences.setImageCycleEnabled(enabled)
    fun setImageCycleIntervalMillis(millis: Long) = preferences.setImageCycleIntervalMillis(millis)
    fun setCycleImageOnUnlock(enabled: Boolean) = preferences.setCycleImageOnUnlock(enabled)

    fun setEffectTransitionDurationMillis(millis: Long) = preferences.setEffectTransitionDurationMillis(millis)
    fun setBlurOnScreenLock(enabled: Boolean) = preferences.setBlurOnScreenLock(enabled)
    fun setBlurTimeoutEnabled(enabled: Boolean) = preferences.setBlurTimeoutEnabled(enabled)
    fun setBlurTimeoutMillis(millis: Long) = preferences.setBlurTimeoutMillis(millis)

    fun setGestureAction(event: TapEvent, action: GestureAction) = preferences.setGestureAction(event, action)

    fun viewCurrentWallpaper() {
        uiState.value.currentWallpaperUri?.let { uri ->
            Actions.viewImage(getApplication(), uri)
        }
    }

    fun navigateToFolderDetail(folderId: Long, folderName: String) {
        val intent = FolderDetailActivity.createIntent(getApplication(), folderId, folderName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
    }

    fun navigateToFolderSelection() {
        val intent = Intent(getApplication(), FolderSelectionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
    }
}
