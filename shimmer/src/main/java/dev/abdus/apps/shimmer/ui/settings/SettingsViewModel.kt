package dev.abdus.apps.shimmer.ui.settings

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.abdus.apps.shimmer.Actions
import dev.abdus.apps.shimmer.ChromaticAberrationSettings
import dev.abdus.apps.shimmer.DuotoneSettings
import dev.abdus.apps.shimmer.GestureAction
import dev.abdus.apps.shimmer.GrainSettings
import dev.abdus.apps.shimmer.ImageCycleSettings
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.TapGesture
import dev.abdus.apps.shimmer.WallpaperPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val selectedTab: SettingsTab,
    val blurAmount: Float,
    val dimAmount: Float,
    val grainSettings: GrainSettings,
    val duotoneSettings: DuotoneSettings,
    val chromaticAberration: ChromaticAberrationSettings,
    val imageCycleSettings: ImageCycleSettings,
    val effectTransitionDurationMillis: Long,
    val blurOnScreenLock: Boolean,
    val blurTimeoutEnabled: Boolean,
    val blurTimeoutMillis: Long,
    val currentWallpaperUri: Uri? = null,
    val currentWallpaperName: String? = null,
    val imageFolders: List<ImageFolderUiModel> = emptyList(),
    val gestureActions: Map<TapGesture, GestureAction>,
) {
    companion object {
        fun fromPreferences(preferences: WallpaperPreferences) = SettingsUiState(
            selectedTab = SettingsTab.entries.getOrElse(preferences.getLastSelectedTab()) { SettingsTab.SOURCES },
            blurAmount = preferences.getBlurAmount(),
            dimAmount = preferences.getDimAmount(),
            grainSettings = preferences.getGrainSettings(),
            duotoneSettings = preferences.getDuotoneSettings(),
            chromaticAberration = preferences.getChromaticAberrationSettings(),
            imageCycleSettings = preferences.getImageCycleSettings(),
            effectTransitionDurationMillis = preferences.getEffectTransitionDurationMillis(),
            blurOnScreenLock = preferences.isBlurOnScreenLockEnabled(),
            blurTimeoutEnabled = preferences.isBlurTimeoutEnabled(),
            blurTimeoutMillis = preferences.getBlurTimeoutMillis(),
            gestureActions = preferences.getGestureActions(),
        )
    }

    val sources: SourcesState
        get() = SourcesState(
            currentWallpaperUri = currentWallpaperUri,
            currentWallpaperName = currentWallpaperName,
            imageFolders = imageFolders,
            imageCycleSettings = imageCycleSettings,
        )

    val effects: EffectsState
        get() = EffectsState(
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

    val gestures: GesturesState
        get() = GesturesState(
            actions = gestureActions
        )
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ImageFolderRepository(application)
    private val preferences = WallpaperPreferences.create(application)

    private val _uiState = MutableStateFlow(SettingsUiState.fromPreferences(preferences))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        updateStateFromPrefs(key)
    }

    init {
        preferences.registerListener(prefsListener)
        observeRepository()
    }

    fun handleSettingsAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.TabSelected -> preferences.setLastSelectedTab(action.tab.ordinal)
        }
    }

    fun handleSourcesAction(action: SourcesAction) {
        when (action) {
            SourcesAction.ViewCurrentWallpaper -> viewCurrentWallpaper()
            is SourcesAction.NavigateToFolderDetail -> navigateToFolderDetail(action.folderId, action.folderName)
            SourcesAction.NavigateToFolderSelection -> navigateToFolderSelection()
            is SourcesAction.UpdateImageCycle -> preferences.setImageCycleSettings(action.settings)
        }
    }

    fun handleEffectsAction(action: EffectsAction) {
        when (action) {
            is EffectsAction.SetBlurAmount -> preferences.setBlurAmount(action.amount)
            is EffectsAction.SetDimAmount -> preferences.setDimAmount(action.amount)
            is EffectsAction.SetEffectTransitionDuration -> preferences.setEffectTransitionDurationMillis(action.durationMillis)
            is EffectsAction.UpdateDuotone -> preferences.setDuotoneSettings(action.settings)
            is EffectsAction.UpdateGrain -> preferences.setGrainSettings(action.settings)
            is EffectsAction.UpdateChromaticAberration -> preferences.setChromaticAberrationSettings(action.settings)
            is EffectsAction.SetBlurOnScreenLock -> preferences.setBlurOnScreenLock(action.enabled)
            is EffectsAction.SetBlurTimeoutEnabled -> preferences.setBlurTimeoutEnabled(action.enabled)
            is EffectsAction.SetBlurTimeoutMillis -> preferences.setBlurTimeoutMillis(action.millis)
        }
    }

    fun handleGesturesAction(action: GesturesAction) {
        when (action) {
            is GesturesAction.SetGestureAction -> preferences.setGestureAction(action.event, action.action)
        }
    }

    override fun onCleared() {
        super.onCleared()
        preferences.unregisterListener(prefsListener)
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
                        isScanning = meta.isScanning,
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
                WallpaperPreferences.KEY_IMAGE_CYCLE_SETTINGS -> current.copy(imageCycleSettings = preferences.getImageCycleSettings())
                WallpaperPreferences.KEY_EFFECT_TRANSITION_DURATION -> current.copy(effectTransitionDurationMillis = preferences.getEffectTransitionDurationMillis())
                WallpaperPreferences.KEY_BLUR_ON_SCREEN_LOCK -> current.copy(blurOnScreenLock = preferences.isBlurOnScreenLockEnabled())
                WallpaperPreferences.KEY_BLUR_TIMEOUT_ENABLED -> current.copy(blurTimeoutEnabled = preferences.isBlurTimeoutEnabled())
                WallpaperPreferences.KEY_BLUR_TIMEOUT_MILLIS -> current.copy(blurTimeoutMillis = preferences.getBlurTimeoutMillis())
                WallpaperPreferences.KEY_GESTURE_TRIPLE_TAP_ACTION,
                WallpaperPreferences.KEY_GESTURE_TWO_FINGER_DOUBLE_TAP_ACTION,
                WallpaperPreferences.KEY_GESTURE_THREE_FINGER_DOUBLE_TAP_ACTION -> current.copy(
                    gestureActions = preferences.getGestureActions()
                )
                WallpaperPreferences.KEY_LAST_SELECTED_TAB -> current.copy(selectedTab = SettingsTab.entries.getOrElse(preferences.getLastSelectedTab()) { SettingsTab.SOURCES })
                else -> current
            }
        }
    }

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
