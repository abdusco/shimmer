package dev.abdus.apps.shimmer.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.Actions
import dev.abdus.apps.shimmer.DUOTONE_PRESETS
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.R
import dev.abdus.apps.shimmer.TapEvent
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.ui.ShimmerTheme

private const val TAG = "SettingsActivity"

enum class SettingsTab {
    SOURCES,
    EFFECTS,
    GESTURES
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContent {
            ShimmerTheme {
                ShimmerSettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShimmerSettingsScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember { WallpaperPreferences.create(context) }
    val repository = remember { ImageFolderRepository(context) }

    var blurAmount by remember { mutableFloatStateOf(preferences.getBlurAmount()) }
    var dimAmount by remember { mutableFloatStateOf(preferences.getDimAmount()) }
    var grainSettings by remember { mutableStateOf(preferences.getGrainSettings()) }
    var duotone by remember { mutableStateOf(preferences.getDuotoneSettings()) }
    var chromaticAberration by remember { mutableStateOf(preferences.getChromaticAberrationSettings()) }

    val foldersMetadata by repository.foldersMetadataFlow.collectAsState(initial = emptyMap())

    var transitionIntervalMillis by remember { mutableLongStateOf(preferences.getTransitionIntervalMillis()) }
    var transitionEnabled by remember { mutableStateOf(preferences.isTransitionEnabled()) }
    var effectTransitionDurationMillis by remember { mutableLongStateOf(preferences.getEffectTransitionDurationMillis()) }
    var blurOnScreenLock by remember { mutableStateOf(preferences.isBlurOnScreenLockEnabled()) }
    var blurTimeoutEnabled by remember { mutableStateOf(preferences.isBlurTimeoutEnabled()) }
    var blurTimeoutMillis by remember { mutableLongStateOf(preferences.getBlurTimeoutMillis()) }
    var changeImageOnUnlock by remember { mutableStateOf(preferences.isChangeImageOnUnlockEnabled()) }

    var currentWallpaperName by remember { mutableStateOf<String?>(null) }
    val currentWallpaperUri by repository.currentImageUriFlow.collectAsState(initial = null)
    
    LaunchedEffect(currentWallpaperUri) {
        currentWallpaperName = repository.getCurrentImageName(currentWallpaperUri)
    }

    var tripleTapAction by remember { mutableStateOf(preferences.getGestureAction(TapEvent.TRIPLE_TAP)) }
    var twoFingerDoubleTapAction by remember { mutableStateOf(preferences.getGestureAction(TapEvent.TWO_FINGER_DOUBLE_TAP)) }
    var threeFingerDoubleTapAction by remember { mutableStateOf(preferences.getGestureAction(TapEvent.THREE_FINGER_DOUBLE_TAP)) }

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            Log.d(TAG, "Preference changed: $key")
            runCatching {
                when (key) {
                    WallpaperPreferences.KEY_BLUR_AMOUNT -> blurAmount = preferences.getBlurAmount()
                    WallpaperPreferences.KEY_DIM_AMOUNT -> dimAmount = preferences.getDimAmount()
                    WallpaperPreferences.KEY_GRAIN_SETTINGS -> grainSettings = preferences.getGrainSettings()
                    WallpaperPreferences.KEY_DUOTONE_SETTINGS -> duotone = preferences.getDuotoneSettings()
                    WallpaperPreferences.KEY_CHROMATIC_ABERRATION_SETTINGS -> chromaticAberration = preferences.getChromaticAberrationSettings()
                    WallpaperPreferences.KEY_TRANSITION_INTERVAL -> transitionIntervalMillis = preferences.getTransitionIntervalMillis()
                    WallpaperPreferences.KEY_TRANSITION_ENABLED -> transitionEnabled = preferences.isTransitionEnabled()
                    WallpaperPreferences.KEY_EFFECT_TRANSITION_DURATION -> effectTransitionDurationMillis = preferences.getEffectTransitionDurationMillis()
                    WallpaperPreferences.KEY_BLUR_ON_SCREEN_LOCK -> blurOnScreenLock = preferences.isBlurOnScreenLockEnabled()
                    WallpaperPreferences.KEY_CHANGE_IMAGE_ON_UNLOCK -> changeImageOnUnlock = preferences.isChangeImageOnUnlockEnabled()
                    WallpaperPreferences.KEY_BLUR_TIMEOUT_ENABLED -> blurTimeoutEnabled = preferences.isBlurTimeoutEnabled()
                    WallpaperPreferences.KEY_BLUR_TIMEOUT_MILLIS -> blurTimeoutMillis = preferences.getBlurTimeoutMillis()
                    WallpaperPreferences.KEY_GESTURE_TRIPLE_TAP_ACTION -> tripleTapAction = preferences.getGestureAction(TapEvent.TRIPLE_TAP)
                    WallpaperPreferences.KEY_GESTURE_TWO_FINGER_DOUBLE_TAP_ACTION -> twoFingerDoubleTapAction = preferences.getGestureAction(TapEvent.TWO_FINGER_DOUBLE_TAP)
                    WallpaperPreferences.KEY_GESTURE_THREE_FINGER_DOUBLE_TAP_ACTION -> threeFingerDoubleTapAction = preferences.getGestureAction(TapEvent.THREE_FINGER_DOUBLE_TAP)
                }
            }.onFailure { e ->
                Log.e(TAG, "Error updating UI for preference: $key", e)
            }
        }
        preferences.registerListener(listener)
        onDispose { preferences.unregisterListener(listener) }
    }

    var selectedTab by remember {
        mutableStateOf(SettingsTab.entries.getOrElse(preferences.getLastSelectedTab()) { SettingsTab.SOURCES })
    }

    LaunchedEffect(selectedTab) {
        preferences.setLastSelectedTab(selectedTab.ordinal)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(R.drawable.shimmer), null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Shimmer", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == SettingsTab.SOURCES,
                    onClick = { selectedTab = SettingsTab.SOURCES },
                    label = { Text("Sources") },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                )
                NavigationBarItem(
                    selected = selectedTab == SettingsTab.EFFECTS,
                    onClick = { selectedTab = SettingsTab.EFFECTS },
                    label = { Text("Effects") },
                    icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                )
                NavigationBarItem(
                    selected = selectedTab == SettingsTab.GESTURES,
                    onClick = { selectedTab = SettingsTab.GESTURES },
                    label = { Text("Gestures") },
                    icon = { Icon(Icons.Default.TouchApp, contentDescription = null) },
                )
            }
        },
    ) { paddingValues ->
        Surface(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                SettingsTab.SOURCES -> {
                    val imageFolders = foldersMetadata.map { (uri, meta) ->
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
                    SourcesTab(
                        modifier = Modifier.padding(paddingValues),
                        currentWallpaperUri = currentWallpaperUri,
                        currentWallpaperName = currentWallpaperName,
                        imageFolders = imageFolders,
                        transitionEnabled = transitionEnabled,
                        transitionIntervalMillis = transitionIntervalMillis,
                        changeImageOnUnlock = changeImageOnUnlock,
                        onViewCurrentWallpaper = {
                            currentWallpaperUri?.let { uri -> Actions.viewImage(context, uri) }
                        },
                        onTransitionEnabledChange = { preferences.setTransitionEnabled(it) },
                        onTransitionDurationChange = { preferences.setTransitionIntervalMillis(it) },
                        onChangeImageOnUnlockChange = { preferences.setChangeImageOnUnlock(it) },
                    )
                }

                SettingsTab.EFFECTS -> EffectsTab(
                    modifier = Modifier.padding(paddingValues),
                    blurAmount = blurAmount,
                    dimAmount = dimAmount,
                    grainSettings = grainSettings,
                    duotone = duotone,
                    chromaticAberration = chromaticAberration,
                    effectTransitionDurationMillis = effectTransitionDurationMillis,
                    blurOnScreenLock = blurOnScreenLock,
                    blurTimeoutEnabled = blurTimeoutEnabled,
                    blurTimeoutMillis = blurTimeoutMillis,
                    onBlurAmountChange = { preferences.setBlurAmount(it) },
                    onDimAmountChange = { preferences.setDimAmount(it) },
                    onEffectTransitionDurationChange = { preferences.setEffectTransitionDurationMillis(it) },
                    onDuotoneEnabledChange = { preferences.setDuotoneEnabled(it) },
                    onDuotoneAlwaysOnChange = { preferences.setDuotoneAlwaysOn(it) },
                    onDuotoneLightColorChange = { input -> parseColorHex(input)?.let { preferences.setDuotoneLightColor(it) } },
                    onDuotoneDarkColorChange = { input -> parseColorHex(input)?.let { preferences.setDuotoneDarkColor(it) } },
                    onDuotoneBlendModeChange = { preferences.setDuotoneBlendMode(it) },
                    onGrainEnabledChange = { preferences.setGrainEnabled(it) },
                    onGrainAmountChange = { preferences.setGrainAmount(it) },
                    onGrainScaleChange = { preferences.setGrainScale(it) },
                    onDuotonePresetSelected = { preset ->
                        preferences.setDuotoneLightColor(preset.lightColor)
                        preferences.setDuotoneDarkColor(preset.darkColor)
                        val index = DUOTONE_PRESETS.indexOf(preset)
                        if (index >= 0) preferences.setDuotonePresetIndex(index)
                    },
                    onBlurOnScreenLockChange = { preferences.setBlurOnScreenLock(it) },
                    onBlurTimeoutEnabledChange = { preferences.setBlurTimeoutEnabled(it) },
                    onBlurTimeoutMillisChange = { preferences.setBlurTimeoutMillis(it) },
                    onChromaticAberrationEnabledChange = { preferences.setChromaticAberrationEnabled(it) },
                    onChromaticAberrationIntensityChange = { preferences.setChromaticAberrationIntensity(it) },
                    onChromaticAberrationFadeDurationChange = { preferences.setChromaticAberrationFadeDuration(it) },
                )

                SettingsTab.GESTURES -> GesturesTab(
                    modifier = Modifier.padding(paddingValues),
                    tripleTapAction = tripleTapAction,
                    twoFingerDoubleTapAction = twoFingerDoubleTapAction,
                    threeFingerDoubleTapAction = threeFingerDoubleTapAction,
                    onTripleTapActionChange = { preferences.setGestureAction(TapEvent.TRIPLE_TAP, it) },
                    onTwoFingerDoubleTapActionChange = { preferences.setGestureAction(TapEvent.TWO_FINGER_DOUBLE_TAP, it) },
                    onThreeFingerDoubleTapActionChange = { preferences.setGestureAction(TapEvent.THREE_FINGER_DOUBLE_TAP, it) },
                )
            }
        }
    }
}