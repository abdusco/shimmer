package dev.abdus.apps.shimmer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val PADDING_X = 24.dp
val PADDING_Y = 24.dp

enum class SettingsTab {
    SOURCES,
    EFFECTS
}

/**
 * Debounces value changes - only executes the callback after [delayMillis] of inactivity.
 * Automatically cancels pending execution when value changes, implementing true debounce behavior.
 * Useful for expensive operations like image reprocessing.
 */
@Composable
fun <T> DebouncedEffect(
    value: T,
    delayMillis: Long = 500,
    onDebounced: suspend (T) -> Unit,
) {
    LaunchedEffect(value) {
        delay(delayMillis)
        onDebounced(value)
    }
}

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContent {
            ShimmerSettingsScreen()
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
    var duotone by remember { mutableStateOf(preferences.getDuotoneSettings()) }
    var folderUris by remember { mutableStateOf(preferences.getImageFolderUris()) }
    var folderPreviews by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
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

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val nextList = (folderUris + it.toString()).distinct()
                folderUris = nextList
                preferences.setImageFolderUris(nextList)
            }
        }
    )

    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                WallpaperPreferences.KEY_BLUR_AMOUNT -> blurAmount = preferences.getBlurAmount()

                WallpaperPreferences.KEY_DIM_AMOUNT -> dimAmount = preferences.getDimAmount()

                WallpaperPreferences.KEY_DUOTONE_SETTINGS -> {
                    duotone = preferences.getDuotoneSettings()
                }

                WallpaperPreferences.KEY_IMAGE_FOLDER_URIS -> {
                    folderUris = preferences.getImageFolderUris()
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
            }
        }
        preferences.registerListener(listener)
        onDispose {
            preferences.unregisterListener(listener)
        }
    }

    LaunchedEffect(folderUris) {
        // Keep existing previews for folders still in the list
        val updated = folderPreviews.filterKeys { it in folderUris }.toMutableMap()

        // Load missing thumbnails IN PARALLEL
        val missingUris = folderUris.filter { !updated.containsKey(it) }
        val newPreviews = missingUris.map { folderUri ->
            async(Dispatchers.IO) {
                folderUri to getFolderThumbnailUri(context, folderUri)
            }
        }.awaitAll()

        // Add successfully loaded previews
        newPreviews.forEach { (uri, preview) ->
            if (preview != null) {
                updated[uri] = preview
            }
        }

        folderPreviews = updated
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
                    Column {
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
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier.fillMaxSize(),
        ) {
            when (selectedTab) {
                SettingsTab.SOURCES -> SourcesTab(
                    modifier = Modifier.padding(paddingValues),
                    folderUris = folderUris,
                    folderPreviews = folderPreviews,
                    transitionEnabled = transitionEnabled,
                    transitionIntervalMillis = transitionIntervalMillis,
                    changeImageOnUnlock = changeImageOnUnlock,
                    onPickFolder = { folderPickerLauncher.launch(null) },
                    onRemoveFolder = { uri ->
                        val nextList = folderUris.filter { it != uri }
                        folderUris = nextList
                        preferences.setImageFolderUris(nextList)
                    },
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

                SettingsTab.EFFECTS -> EffectsTab(
                    modifier = Modifier.padding(paddingValues),
                    blurAmount = blurAmount,
                    dimAmount = dimAmount,
                    duotone = duotone,
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
                    }
                )
            }
        }
    }
}

@Composable
private fun SourcesTab(
    modifier: Modifier = Modifier,
    folderUris: List<String>,
    folderPreviews: Map<String, Uri>,
    transitionEnabled: Boolean,
    transitionIntervalMillis: Long,
    changeImageOnUnlock: Boolean,
    onPickFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onTransitionEnabledChange: (Boolean) -> Unit,
    onTransitionDurationChange: (Long) -> Unit,
    onChangeImageOnUnlockChange: (Boolean) -> Unit,
) {
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
            FolderSelection(
                folderUris = folderUris,
                folderPreviews = folderPreviews,
                onPickFolder = onPickFolder,
                onRemoveFolder = onRemoveFolder
            )
        }

        item {
            TransitionDurationSetting(
                enabled = transitionEnabled,
                durationMillis = transitionIntervalMillis,
                changeImageOnUnlock = changeImageOnUnlock,
                onEnabledChange = onTransitionEnabledChange,
                onDurationChange = onTransitionDurationChange,
                onChangeImageOnUnlockChange = onChangeImageOnUnlockChange
            )
        }
    }
}

@Composable
private fun EffectsTab(
    modifier: Modifier = Modifier,
    blurAmount: Float,
    dimAmount: Float,
    duotone: DuotoneSettings,
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
    onDuotonePresetSelected: (DuotonePreset) -> Unit,
    onBlurOnScreenLockChange: (Boolean) -> Unit,
    onBlurTimeoutEnabledChange: (Boolean) -> Unit,
    onBlurTimeoutMillisChange: (Long) -> Unit,
) {
    // Local state for sliders to provide instant UI feedback
    var localBlurAmount by remember { mutableFloatStateOf(blurAmount) }
    var localDimAmount by remember { mutableFloatStateOf(dimAmount) }

    // Sync local state when preferences change externally
    LaunchedEffect(blurAmount) {
        localBlurAmount = blurAmount
    }

    LaunchedEffect(dimAmount) {
        localDimAmount = dimAmount
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
            Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = PADDING_X, vertical = PADDING_Y),
                    verticalArrangement = Arrangement.spacedBy(PADDING_Y)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Image Effects",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    SliderSetting(
                        title = "Blur amount",
                        icon = Icons.Outlined.RemoveRedEye,
                        value = localBlurAmount,
                        onValueChange = { localBlurAmount = it }
                    )
                    SliderSetting(
                        title = "Dim amount",
                        icon = Icons.Outlined.Brightness4,
                        value = localDimAmount,
                        onValueChange = { localDimAmount = it }
                    )
                    EffectTransitionDurationSlider(
                        durationMillis = effectTransitionDurationMillis,
                        onDurationChange = onEffectTransitionDurationChange
                    )
                }
            }
        }

        item {
            DuotoneSettings(
                enabled = duotone.enabled,
                alwaysOn = duotone.alwaysOn,
                lightColorText = colorIntToHex(duotone.lightColor),
                darkColorText = colorIntToHex(duotone.darkColor),
                lightColorPreview = colorIntToComposeColor(duotone.lightColor),
                darkColorPreview = colorIntToComposeColor(duotone.darkColor),
                onEnabledChange = onDuotoneEnabledChange,
                onAlwaysOnChange = onDuotoneAlwaysOnChange,
                onLightColorChange = onDuotoneLightColorChange,
                onDarkColorChange = onDuotoneDarkColorChange,
                onPresetSelected = onDuotonePresetSelected
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

@Composable
private fun FolderSelection(
    folderUris: List<String>,
    folderPreviews: Map<String, Uri>,
    onPickFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = PADDING_Y, horizontal = PADDING_X)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Local image folders",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Button(onClick = onPickFolder) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Add")
                }
            }
            if (folderUris.isEmpty()) {
                Text(
                    text = "No folders selected. Add a folder to display your own images.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    folderUris.forEach { uri ->
                        FolderCard(
                            folderUri = uri,
                            previewUri = folderPreviews[uri],
                            onRemove = onRemoveFolder
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCard(
    folderUri: String,
    previewUri: Uri?,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    val folderName = remember(folderUri) {
        DocumentFile.fromTreeUri(context, folderUri.toUri())?.name ?: folderUri
    }
    val displayPath = remember(folderUri) {
        formatTreeUriPath(folderUri)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FolderThumbnail(previewUri)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = displayPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = { onRemove(folderUri) }) {
                Text(text = "Remove")
            }
        }
    }
}

@Composable
private fun FolderThumbnail(
    previewUri: Uri?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(80.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (previewUri != null) {
            AsyncImage(
                modifier = Modifier.fillMaxSize(),
                model = ImageRequest.Builder(LocalContext.current)
                    .data(previewUri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private suspend fun getFolderThumbnailUri(
    context: Context,
    folderUri: String,
): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val folder =
                DocumentFile.fromTreeUri(context, folderUri.toUri()) ?: return@withContext null
            folder.listFiles().firstOrNull { it.isFile && (it.type?.startsWith("image/") == true) }
                ?.uri
        } catch (_: SecurityException) {
            null
        }
    }
}

private fun formatTreeUriPath(uriString: String): String {
    return try {
        val uri = uriString.toUri()
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val colonIndex = documentId.indexOf(':')
        val storage = if (colonIndex < 0) {
            documentId
        } else {
            documentId.substring(0, colonIndex)
        }
        val rawPath = if (colonIndex < 0) {
            ""
        } else {
            documentId.substring(colonIndex + 1)
        }
        val storageLabel = when (storage.lowercase()) {
            "primary" -> "sdcard"
            else -> storage
        }
        if (rawPath.isBlank()) {
            storageLabel
        } else {
            val decodedPath = Uri.decode(rawPath).trimStart('/')
            "$storageLabel/$decodedPath"
        }
    } catch (_: Exception) {
        uriString
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "$title: ${formatPercent(value)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            steps = 19,
            valueRange = 0f..1f
        )
    }
}

private fun formatPercent(value: Float): String = "${(value * 100).roundToInt()}%"

@Composable
private fun EffectTransitionDurationSlider(
    durationMillis: Long,
    onDurationChange: (Long) -> Unit,
) {
    // Range: 0ms to 3000ms in 250ms steps = 13 steps (0, 250, 500, ..., 3000)
    val steps = 12 // 13 values means 12 steps between them
    val stepSize = 250L
    val maxValue = 3000L

    val sliderValue = (durationMillis / stepSize).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Speed,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Effect transition duration: ${formatDurationMs(durationMillis)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                val newDuration = (newValue.roundToInt() * stepSize).coerceIn(0L, maxValue)
                onDurationChange(newDuration)
            },
            steps = steps - 1,
            valueRange = 0f..(maxValue / stepSize).toFloat()
        )
    }
}

private fun formatDurationMs(millis: Long): String {
    return if (millis == 0L) {
        "0s"
    } else if (millis < 1000L) {
        "${millis}ms"
    } else {
        val seconds = millis / 1000.0
        if (seconds == seconds.toInt().toDouble()) {
            "${seconds.toInt()}s"
        } else {
            "${seconds}s"
        }
    }
}

@Composable
private fun TransitionDurationSetting(
    enabled: Boolean,
    durationMillis: Long,
    changeImageOnUnlock: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDurationChange: (Long) -> Unit,
    onChangeImageOnUnlockChange: (Boolean) -> Unit,
) {
    val options = TRANSITION_DURATION_OPTIONS
    val sliderIndex = options.indexOfFirst { it.millis == durationMillis }.takeIf { it >= 0 } ?: 0
    val selectedOption = options.getOrElse(sliderIndex) { options.first() }
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(vertical = PADDING_Y, horizontal = PADDING_X),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Change images automatically",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Change every",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Slider(
                    value = sliderIndex.toFloat(),
                    onValueChange = { rawValue ->
                        val nextIndex = rawValue.roundToInt().coerceIn(0, options.lastIndex)
                        val nextDuration = options[nextIndex].millis
                        if (nextDuration != durationMillis) {
                            onDurationChange(nextDuration)
                        }
                    },
                    valueRange = 0f..options.lastIndex.toFloat(),
                    steps = (options.size - 2).coerceAtLeast(0),
                    enabled = enabled
                )
                Text(
                    text = if (enabled) "Next change in ${selectedOption.label}" else "Auto change disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            // Change image on screen unlock toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Change when screen unlocks",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = changeImageOnUnlock,
                    onCheckedChange = onChangeImageOnUnlockChange
                )
            }
        }
    }
}

private data class TransitionDurationOption(val millis: Long, val label: String)

private val TRANSITION_DURATION_OPTIONS = listOf(
    TransitionDurationOption(5_000L, "5s"),
    TransitionDurationOption(30_000L, "30s"),
    TransitionDurationOption(60_000L, "1min"),
    TransitionDurationOption(5 * 60_000L, "5min"),
    TransitionDurationOption(15 * 60_000L, "15min"),
    TransitionDurationOption(60 * 60_000L, "1h"),
    TransitionDurationOption(3 * 60 * 60_000L, "3h"),
    TransitionDurationOption(6 * 60 * 60_000L, "6h"),
    TransitionDurationOption(24 * 60 * 60_000L, "24h")
)


@Composable
private fun DuotoneSettings(
    enabled: Boolean,
    alwaysOn: Boolean,
    lightColorText: String,
    darkColorText: String,
    lightColorPreview: Color,
    darkColorPreview: Color,
    onEnabledChange: (Boolean) -> Unit,
    onAlwaysOnChange: (Boolean) -> Unit,
    onLightColorChange: (String) -> Unit,
    onDarkColorChange: (String) -> Unit,
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
            if (enabled) {
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
                DuotonePresetDropdown(onPresetSelected = onPresetSelected)
            }
        }
    }
}

@Composable
private fun EventsSettings(
    blurOnScreenLock: Boolean,
    blurTimeoutEnabled: Boolean,
    blurTimeoutMillis: Long,
    onBlurOnScreenLockChange: (Boolean) -> Unit,
    onBlurTimeoutEnabledChange: (Boolean) -> Unit,
    onBlurTimeoutMillisChange: (Long) -> Unit,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Event,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Events",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Blur when screen is locked",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = blurOnScreenLock,
                    onCheckedChange = onBlurOnScreenLockChange
                )
            }

            BlurTimeoutSetting(
                enabled = blurTimeoutEnabled,
                timeoutMillis = blurTimeoutMillis,
                onEnabledChange = onBlurTimeoutEnabledChange,
                onTimeoutChange = onBlurTimeoutMillisChange
            )
        }
    }
}

@Composable
private fun BlurTimeoutSetting(
    enabled: Boolean,
    timeoutMillis: Long,
    onEnabledChange: (Boolean) -> Unit,
    onTimeoutChange: (Long) -> Unit,
) {
    val min = WallpaperPreferences.MIN_BLUR_TIMEOUT_MILLIS
    val max = WallpaperPreferences.MAX_BLUR_TIMEOUT_MILLIS
    val step = 5_000L
    val steps = ((max - min) / step).toInt()
    val sliderValue = ((timeoutMillis - min) / step).coerceIn(0, steps.toLong()).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    Text(
                        text = "Blur after timeout",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (enabled) "After ${timeoutMillis / 1000}s" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { raw ->
                val nextIndex = raw.roundToInt().coerceIn(0, steps)
                val nextTimeout = min + (nextIndex * step)
                if (nextTimeout != timeoutMillis) {
                    onTimeoutChange(nextTimeout)
                }
            },
            valueRange = 0f..steps.toFloat(),
            steps = (steps - 1).coerceAtLeast(0),
            enabled = enabled
        )
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

@Composable
private fun ColorHexField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    previewColor: Color,
) {
    val normalized = value.uppercase()
    val isError = normalized.isNotEmpty() && !isValidHexColor(normalized)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = normalized,
            onValueChange = { onValueChange(it.uppercase()) },
            label = { Text(label) },
            singleLine = true,
            isError = isError
        )
        Spacer(modifier = Modifier.width(12.dp))
        ColorSwatch(previewColor, RoundedCornerShape(8.dp))
    }
}

@Composable
private fun ColorSwatch(color: Color, shape: Shape) {
    Surface(
        modifier = Modifier.size(40.dp),
        color = color,
        shape = shape,
        shadowElevation = 2.dp
    ) {}
}

private fun colorIntToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

private fun isValidHexColor(value: String): Boolean =
    HEX_COLOR_REGEX.matches(if (value.startsWith("#")) value else "#$value")

private fun parseColorHex(value: String): Int? {
    if (value.isBlank()) {
        return null
    }
    val candidate = if (value.startsWith("#")) value else "#$value"
    return try {
        candidate.toColorInt()
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val HEX_COLOR_REGEX = "^#[0-9A-Fa-f]{6}$".toRegex()

private fun colorIntToComposeColor(colorInt: Int): Color =
    Color(colorInt.toLong() and 0xFFFFFFFFL)

