package dev.abdus.apps.shimmer.settings

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.ChromaticAberrationSettings
import dev.abdus.apps.shimmer.DuotoneBlendMode
import dev.abdus.apps.shimmer.DuotonePreset
import dev.abdus.apps.shimmer.DuotoneSettings
import dev.abdus.apps.shimmer.GrainSettings
import dev.abdus.apps.shimmer.ImageFolder
import dev.abdus.apps.shimmer.TapEvent
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.DUOTONE_PRESETS
import dev.abdus.apps.shimmer.ShimmerTheme
import dev.abdus.apps.shimmer.settings.parseColorHex

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
    var blurAmount by remember { mutableFloatStateOf(preferences.getBlurAmount()) }
    var dimAmount by remember { mutableFloatStateOf(preferences.getDimAmount()) }
    var grainSettings by remember { mutableStateOf(preferences.getGrainSettings()) }
    var duotone by remember { mutableStateOf(preferences.getDuotoneSettings()) }
    var chromaticAberration by remember { mutableStateOf(preferences.getChromaticAberrationSettings()) }
    var imageFolders by remember { mutableStateOf(preferences.getImageFolders()) }
    var transitionIntervalMillis by remember {
        mutableLongStateOf(preferences.getTransitionIntervalMillis())
    }
    var transitionEnabled by remember {
        mutableStateOf(preferences.isTransitionEnabled())
    }
    var effectTransitionDurationMillis by remember {
        mutableLongStateOf(preferences.getEffectTransitionDurationMillis())
    }
    var blurOnScreenLock by remember {
        mutableStateOf(preferences.isBlurOnScreenLockEnabled())
    }
    var blurTimeoutEnabled by remember {
        mutableStateOf(preferences.isBlurTimeoutEnabled())
    }
    var blurTimeoutMillis by remember {
        mutableLongStateOf(preferences.getBlurTimeoutMillis())
    }
    var changeImageOnUnlock by remember {
        mutableStateOf(preferences.isChangeImageOnUnlockEnabled())
    }
    var currentWallpaperUri by remember {
        mutableStateOf(preferences.getLastImageUri())
    }
    var tripleTapAction by remember {
        mutableStateOf(preferences.getGestureAction(TapEvent.TRIPLE_TAP))
    }
    var twoFingerDoubleTapAction by remember {
        mutableStateOf(preferences.getGestureAction(TapEvent.TWO_FINGER_DOUBLE_TAP))
    }
    var threeFingerDoubleTapAction by remember {
        mutableStateOf(preferences.getGestureAction(TapEvent.THREE_FINGER_DOUBLE_TAP))
    }

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                WallpaperPreferences.KEY_BLUR_AMOUNT -> blurAmount = preferences.getBlurAmount()

                WallpaperPreferences.KEY_DIM_AMOUNT -> dimAmount = preferences.getDimAmount()

                WallpaperPreferences.KEY_GRAIN_SETTINGS -> {
                    grainSettings = preferences.getGrainSettings()
                }

                WallpaperPreferences.KEY_DUOTONE_SETTINGS -> {
                    duotone = preferences.getDuotoneSettings()
                }

                WallpaperPreferences.KEY_CHROMATIC_ABERRATION_SETTINGS -> {
                    chromaticAberration = preferences.getChromaticAberrationSettings()
                }

                WallpaperPreferences.KEY_IMAGE_FOLDERS -> {
                    imageFolders = preferences.getImageFolders()
                }

                WallpaperPreferences.KEY_TRANSITION_INTERVAL -> {
                    transitionIntervalMillis = preferences.getTransitionIntervalMillis()
                }

                WallpaperPreferences.KEY_TRANSITION_ENABLED -> {
                    transitionEnabled = preferences.isTransitionEnabled()
                }

                WallpaperPreferences.KEY_EFFECT_TRANSITION_DURATION -> {
                    effectTransitionDurationMillis = preferences.getEffectTransitionDurationMillis()
                }

                WallpaperPreferences.KEY_BLUR_ON_SCREEN_LOCK -> {
                    blurOnScreenLock = preferences.isBlurOnScreenLockEnabled()
                }

                WallpaperPreferences.KEY_CHANGE_IMAGE_ON_UNLOCK -> {
                    changeImageOnUnlock = preferences.isChangeImageOnUnlockEnabled()
                }

                WallpaperPreferences.KEY_BLUR_TIMEOUT_ENABLED -> {
                    blurTimeoutEnabled = preferences.isBlurTimeoutEnabled()
                }

                WallpaperPreferences.KEY_BLUR_TIMEOUT_MILLIS -> {
                    blurTimeoutMillis = preferences.getBlurTimeoutMillis()
                }

                WallpaperPreferences.KEY_LAST_IMAGE_URI -> {
                    currentWallpaperUri = preferences.getLastImageUri()
                }

                WallpaperPreferences.KEY_GESTURE_TRIPLE_TAP_ACTION -> {
                    tripleTapAction = preferences.getGestureAction(TapEvent.TRIPLE_TAP)
                }

                WallpaperPreferences.KEY_GESTURE_TWO_FINGER_DOUBLE_TAP_ACTION -> {
                    twoFingerDoubleTapAction = preferences.getGestureAction(TapEvent.TWO_FINGER_DOUBLE_TAP)
                }

                WallpaperPreferences.KEY_GESTURE_THREE_FINGER_DOUBLE_TAP_ACTION -> {
                    threeFingerDoubleTapAction = preferences.getGestureAction(TapEvent.THREE_FINGER_DOUBLE_TAP)
                }
            }
        }
        preferences.registerListener(listener)
        onDispose {
            preferences.unregisterListener(listener)
        }
    }

    var selectedTab by remember {
        mutableStateOf(
            SettingsTab.entries.getOrElse(preferences.getLastSelectedTab()) { SettingsTab.SOURCES }
        )
    }

    // Save selected tab when it changes
    LaunchedEffect(selectedTab) {
        preferences.setLastSelectedTab(selectedTab.ordinal)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            text = "Shimmer",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Text(
                            text = "Customize your wallpaper",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            )
        },
        bottomBar = {
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Tab(
                    selected = selectedTab == SettingsTab.SOURCES,
                    onClick = { selectedTab = SettingsTab.SOURCES },
                    text = { Text("Sources") },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == SettingsTab.EFFECTS,
                    onClick = { selectedTab = SettingsTab.EFFECTS },
                    text = { Text("Effects") },
                    icon = { Icon(Icons.Default.Palette, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == SettingsTab.GESTURES,
                    onClick = { selectedTab = SettingsTab.GESTURES },
                    text = { Text("Gestures") },
                    icon = { Icon(Icons.Default.TouchApp, contentDescription = null) }
                )
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier.fillMaxSize(),
        ) {
            when (selectedTab) {
                SettingsTab.SOURCES -> {
                    SourcesTab(
                        modifier = Modifier.padding(paddingValues),
                        context = context,
                        preferences = preferences,
                        currentWallpaperUri = currentWallpaperUri,
                        imageFolders = imageFolders,
                        transitionEnabled = transitionEnabled,
                        transitionIntervalMillis = transitionIntervalMillis,
                        changeImageOnUnlock = changeImageOnUnlock,
                        onTransitionEnabledChange = {
                            preferences.setTransitionEnabled(it)
                        },
                        onTransitionDurationChange = { newDuration ->
                            preferences.setTransitionIntervalMillis(newDuration)
                        },
                        onChangeImageOnUnlockChange = {
                            preferences.setChangeImageOnUnlock(it)
                        }
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
                    onBlurAmountChange = {
                        preferences.setBlurAmount(it)
                    },
                    onDimAmountChange = {
                        preferences.setDimAmount(it)
                    },
                    onEffectTransitionDurationChange = {
                        preferences.setEffectTransitionDurationMillis(it)
                    },
                    onDuotoneEnabledChange = {
                        preferences.setDuotoneEnabled(it)
                    },
                    onDuotoneAlwaysOnChange = {
                        preferences.setDuotoneAlwaysOn(it)
                    },
                    onDuotoneLightColorChange = { input ->
                        parseColorHex(input)?.let { parsed ->
                            preferences.setDuotoneLightColor(parsed)
                        }
                    },
                    onDuotoneDarkColorChange = { input ->
                        parseColorHex(input)?.let { parsed ->
                            preferences.setDuotoneDarkColor(parsed)
                        }
                    },
                    onDuotoneBlendModeChange = {
                        preferences.setDuotoneBlendMode(it)
                    },
                    onGrainEnabledChange = {
                        preferences.setGrainEnabled(it)
                    },
                    onGrainAmountChange = {
                        preferences.setGrainAmount(it)
                    },
                    onGrainScaleChange = {
                        preferences.setGrainScale(it)
                    },
                    onDuotonePresetSelected = { preset ->
                        preferences.setDuotoneLightColor(preset.lightColor)
                        preferences.setDuotoneDarkColor(preset.darkColor)
                        val presetIndex = DUOTONE_PRESETS.indexOf(preset)
                        if (presetIndex >= 0) {
                            preferences.setDuotonePresetIndex(presetIndex)
                        }
                    },
                    onBlurOnScreenLockChange = {
                        preferences.setBlurOnScreenLock(it)
                    },
                    onBlurTimeoutEnabledChange = {
                        preferences.setBlurTimeoutEnabled(it)
                    },
                    onBlurTimeoutMillisChange = {
                        preferences.setBlurTimeoutMillis(it)
                    },
                    onChromaticAberrationEnabledChange = {
                        preferences.setChromaticAberrationEnabled(it)
                    },
                    onChromaticAberrationIntensityChange = {
                        preferences.setChromaticAberrationIntensity(it)
                    },
                    onChromaticAberrationFadeDurationChange = {
                        preferences.setChromaticAberrationFadeDuration(it)
                    }
                )

                SettingsTab.GESTURES -> GesturesTab(
                    modifier = Modifier.padding(paddingValues),
                    tripleTapAction = tripleTapAction,
                    twoFingerDoubleTapAction = twoFingerDoubleTapAction,
                    threeFingerDoubleTapAction = threeFingerDoubleTapAction,
                    onTripleTapActionChange = {
                        preferences.setGestureAction(TapEvent.TRIPLE_TAP, it)
                    },
                    onTwoFingerDoubleTapActionChange = {
                        preferences.setGestureAction(TapEvent.TWO_FINGER_DOUBLE_TAP, it)
                    },
                    onThreeFingerDoubleTapActionChange = {
                        preferences.setGestureAction(TapEvent.THREE_FINGER_DOUBLE_TAP, it)
                    }
                )
            }
        }
    }
}
