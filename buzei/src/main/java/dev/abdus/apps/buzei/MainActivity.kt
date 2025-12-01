package dev.abdus.apps.buzei

import android.app.WallpaperManager
import android.content.ComponentName
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContent {
            BuzeiWallpaperPrompt(
                onSetWallpaper = { openWallpaperPicker() }
            )
        }
    }

    private fun openWallpaperPicker() {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@MainActivity, BuzeiWallpaperService::class.java)
            )
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BuzeiWallpaperPrompt(
    modifier: Modifier = Modifier,
    onSetWallpaper: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { WallpaperPreferences.prefs(context) }
    var blurAmount by remember { mutableFloatStateOf(WallpaperPreferences.getBlurAmount(prefs)) }
    var dimAmount by remember { mutableFloatStateOf(WallpaperPreferences.getDimAmount(prefs)) }
    var duotoneEnabled by remember { mutableStateOf(WallpaperPreferences.isDuotoneEnabled(prefs)) }
    var duotoneAlwaysOn by remember { mutableStateOf(WallpaperPreferences.isDuotoneAlwaysOn(prefs)) }
    var lightColor by remember { mutableStateOf(WallpaperPreferences.getDuotoneLightColor(prefs)) }
    var darkColor by remember { mutableStateOf(WallpaperPreferences.getDuotoneDarkColor(prefs)) }
    var lightColorText by remember { mutableStateOf(colorIntToHex(lightColor)) }
    var darkColorText by remember { mutableStateOf(colorIntToHex(darkColor)) }
    var folderUris by remember { mutableStateOf(WallpaperPreferences.getImageFolderUris(prefs)) }
    var folderPreviews by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
    var transitionIntervalMillis by remember {
        mutableLongStateOf(
            WallpaperPreferences.getTransitionIntervalMillis(
                prefs
            )
        )
    }
    var transitionEnabled by remember {
        mutableStateOf(
            WallpaperPreferences.isTransitionEnabled(
                prefs
            )
        )
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
                WallpaperPreferences.setImageFolderUris(prefs, nextList)
            }
        }
    )

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                WallpaperPreferences.KEY_BLUR_AMOUNT -> blurAmount =
                    WallpaperPreferences.getBlurAmount(prefs)

                WallpaperPreferences.KEY_DIM_AMOUNT -> dimAmount =
                    WallpaperPreferences.getDimAmount(prefs)

                WallpaperPreferences.KEY_DUOTONE_ENABLED -> duotoneEnabled =
                    WallpaperPreferences.isDuotoneEnabled(prefs)

                WallpaperPreferences.KEY_DUOTONE_ALWAYS_ON -> duotoneAlwaysOn =
                    WallpaperPreferences.isDuotoneAlwaysOn(prefs)

                WallpaperPreferences.KEY_DUOTONE_LIGHT -> {
                    val stored = WallpaperPreferences.getDuotoneLightColor(prefs)
                    lightColor = stored
                    lightColorText = colorIntToHex(stored)
                }

                WallpaperPreferences.KEY_DUOTONE_DARK -> {
                    val stored = WallpaperPreferences.getDuotoneDarkColor(prefs)
                    darkColor = stored
                    darkColorText = colorIntToHex(stored)
                }

                WallpaperPreferences.KEY_IMAGE_FOLDER_URIS -> {
                    folderUris = WallpaperPreferences.getImageFolderUris(prefs)
                }

                WallpaperPreferences.KEY_TRANSITION_INTERVAL -> {
                    transitionIntervalMillis =
                        WallpaperPreferences.getTransitionIntervalMillis(prefs)
                }

                WallpaperPreferences.KEY_TRANSITION_ENABLED -> {
                    transitionEnabled = WallpaperPreferences.isTransitionEnabled(prefs)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(folderUris) {
        val updated = folderPreviews.filterKeys { it in folderUris }.toMutableMap()
        folderUris.forEach { uri ->
            if (!updated.containsKey(uri)) {
                val preview = loadRandomFolderThumbnail(context, uri)
                if (preview != null) {
                    updated[uri] = preview
                }
            }
        }
        folderPreviews = updated
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Buzei") },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onSetWallpaper) {
                Text(
                    text = "Set wallpaper",
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 24.dp)
                )
            }
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        text = "Buzei Live Wallpaper",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                }
                item {
                    Text(
                        text = "Tap the button below to set Buzei as your live wallpaper.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
                item {
                    FolderSelection(
                        folderUris = folderUris,
                        folderPreviews = folderPreviews,
                        onPickFolder = { folderPickerLauncher.launch(null) },
                        onRemoveFolder = { uri ->
                            val nextList = folderUris.filter { it != uri }
                            folderUris = nextList
                            WallpaperPreferences.setImageFolderUris(prefs, nextList)
                        }
                    )
                }

                item {
                    TransitionDurationSetting(
                        enabled = transitionEnabled,
                        durationMillis = transitionIntervalMillis,
                        onEnabledChange = {
                            transitionEnabled = it
                            WallpaperPreferences.setTransitionEnabled(prefs, it)
                        },
                        onDurationChange = { newDuration ->
                            transitionIntervalMillis = newDuration
                            WallpaperPreferences.setTransitionIntervalMillis(prefs, newDuration)
                        }
                    )
                }

                item {
                    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Image Effects",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            SliderSetting(
                                title = "Blur amount",
                                value = blurAmount,
                                onValueChange = {
                                    blurAmount = it
                                    WallpaperPreferences.setBlurAmount(prefs, it)
                                }
                            )
                            SliderSetting(
                                title = "Dim amount",
                                value = dimAmount,
                                onValueChange = {
                                    dimAmount = it
                                    WallpaperPreferences.setDimAmount(prefs, it)
                                }
                            )
                        }
                    }
                }

                item {
                    DuotoneSettings(
                        enabled = duotoneEnabled,
                        alwaysOn = duotoneAlwaysOn,
                        lightColorText = lightColorText,
                        darkColorText = darkColorText,
                        lightColorPreview = colorIntToComposeColor(lightColor),
                        darkColorPreview = colorIntToComposeColor(darkColor),
                        onEnabledChange = {
                            duotoneEnabled = it
                            WallpaperPreferences.setDuotoneEnabled(prefs, it)
                        },
                        onAlwaysOnChange = {
                            duotoneAlwaysOn = it
                            WallpaperPreferences.setDuotoneAlwaysOn(prefs, it)
                        },
                        onLightColorChange = { input ->
                            lightColorText = input
                            parseColorHex(input)?.let { parsed ->
                                lightColor = parsed
                                WallpaperPreferences.setDuotoneLightColor(prefs, parsed)
                            }
                        },
                        onDarkColorChange = { input ->
                            darkColorText = input
                            parseColorHex(input)?.let { parsed ->
                                darkColor = parsed
                                WallpaperPreferences.setDuotoneDarkColor(prefs, parsed)
                            }
                        },
                        onPresetSelected = { preset ->
                            lightColor = preset.lightColor
                            darkColor = preset.darkColor
                            val nextLightHex = colorIntToHex(preset.lightColor)
                            val nextDarkHex = colorIntToHex(preset.darkColor)
                            lightColorText = nextLightHex
                            darkColorText = nextDarkHex
                            WallpaperPreferences.setDuotoneLightColor(prefs, preset.lightColor)
                            WallpaperPreferences.setDuotoneDarkColor(prefs, preset.darkColor)
                            // Save the preset index for round-robin cycling
                            val presetIndex = DUOTONE_PRESETS.indexOf(preset)
                            if (presetIndex >= 0) {
                                WallpaperPreferences.setDuotonePresetIndex(prefs, presetIndex)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderSelection(
    folderUris: List<String>,
    folderPreviews: Map<String, Uri>,
    onPickFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Local image folders",
                style = MaterialTheme.typography.titleMedium
            )
            Button(onClick = onPickFolder) {
                Text(text = "Add folder")
            }
        }
        if (folderUris.isEmpty()) {
            Text(
                text = "No folders selected",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.DarkGray
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

@Composable
private fun FolderCard(
    folderUri: String,
    previewUri: Uri?,
    onRemove: (String) -> Unit
) {
    val context = LocalContext.current
    val folderName = remember(folderUri) {
        DocumentFile.fromTreeUri(context, Uri.parse(folderUri))?.name ?: folderUri
    }
    val displayPath = remember(folderUri) {
        formatTreeUriPath(folderUri)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
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
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = displayPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
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
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .size(64.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.LightGray
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
        }
    }
}

private suspend fun loadRandomFolderThumbnail(
    context: Context,
    folderUri: String
): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val folder =
                DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return@withContext null
            folder.listFiles()
                .filter { it.isFile && (it.type?.startsWith("image/") == true) }
                .shuffled()
                .firstOrNull()
                ?.uri
        } catch (ignored: SecurityException) {
            null
        }
    }
}

private fun formatTreeUriPath(uriString: String): String {
    return try {
        val uri = Uri.parse(uriString)
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
    } catch (ignored: Exception) {
        uriString
    }
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "$title: ${formatPercent(value)}",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
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
private fun TransitionDurationSetting(
    enabled: Boolean,
    durationMillis: Long,
    onEnabledChange: (Boolean) -> Unit,
    onDurationChange: (Long) -> Unit
) {
    val options = TRANSITION_DURATION_OPTIONS
    val sliderIndex = options.indexOfFirst { it.millis == durationMillis }.takeIf { it >= 0 } ?: 0
    val selectedOption = options.getOrElse(sliderIndex) { options.first() }
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transition between images",
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = "Interval between image swaps",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
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
                text = if (enabled) "Next change in ${selectedOption.label}" else "Transitions disabled",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
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
    onPresetSelected: (DuotonePreset) -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Duotone effect",
                    style = MaterialTheme.typography.titleMedium
                )
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
private fun DuotonePresetDropdown(
    onPresetSelected: (DuotonePreset) -> Unit
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
    previewColor: Color
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
        AndroidColor.parseColor(candidate)
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val HEX_COLOR_REGEX = "^#[0-9A-Fa-f]{6}$".toRegex()

private fun colorIntToComposeColor(colorInt: Int): Color =
    Color(colorInt.toLong() and 0xFFFFFFFFL)

@Preview(showBackground = true)
@Composable
private fun BuzeiWallpaperPromptPreview() {
    BuzeiWallpaperPrompt()
}
