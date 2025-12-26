package dev.abdus.apps.shimmer.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.abdus.apps.shimmer.WallpaperPreferences
import kotlin.math.roundToInt

@Composable
fun SourcesTab(
    modifier: Modifier = Modifier,
    context: Context,
    preferences: WallpaperPreferences,
    currentWallpaperUri: Uri?,
    imageFolders: List<ImageFolderUiModel>,
    transitionEnabled: Boolean,
    transitionIntervalMillis: Long,
    changeImageOnUnlock: Boolean,
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
            CurrentWallpaperCard(
                wallpaperUri = currentWallpaperUri,
                onViewImage = { uri ->
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try { context.startActivity(viewIntent) } catch (_: Exception) {}
                }
            )
        }

        item {
            FolderThumbnailSlider(
                imageFolders = imageFolders,
                onOpenFolderSelection = {
                    context.startActivity(Intent(context, FolderSelectionActivity::class.java))
                }
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
private fun CurrentWallpaperCard(
    wallpaperUri: Uri?,
    onViewImage: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val fileName = remember(wallpaperUri) {
        wallpaperUri?.let { uri ->
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "Unknown"
        } ?: "No wallpaper"
    }

    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clickable(enabled = wallpaperUri != null) {
                    wallpaperUri?.let { onViewImage(it) }
                }
        ) {
            AnimatedContent(
                targetState = wallpaperUri,
                transitionSpec = {
                    fadeIn(animationSpec = tween(750)) togetherWith
                            fadeOut(animationSpec = tween(750))
                },
                label = "wallpaper_crossfade"
            ) { currentUri ->
                if (currentUri != null) {
                    AsyncImage(
                        modifier = Modifier.fillMaxSize(),
                        model = ImageRequest.Builder(context).data(currentUri).build(),
                        contentDescription = "Current wallpaper",
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Image, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("No wallpaper set", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(16.dp)
            ) {
                Text(fileName, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FolderThumbnailSlider(
    imageFolders: List<ImageFolderUiModel>,
    onOpenFolderSelection: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = PADDING_Y, horizontal = PADDING_X)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Image sources", style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = onOpenFolderSelection) { Text("Manage") }
            }

            if (imageFolders.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("No folders selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    items(imageFolders, key = { it.uri }) { folder ->
                        FolderThumbnailLarge(folder = folder)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderThumbnailLarge(folder: ImageFolderUiModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val filter = remember(folder.enabled) {
        if (folder.enabled) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }

    Surface(modifier = modifier.size(160.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        if (folder.thumbnailUri != null) {
            AsyncImage(
                modifier = Modifier.fillMaxSize(),
                model = ImageRequest.Builder(context).data(folder.thumbnailUri).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = filter
            )
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Icon(Icons.Default.Folder, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
        Column(modifier = Modifier.padding(vertical = PADDING_Y, horizontal = PADDING_X), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SwapHoriz, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Change images automatically", style = MaterialTheme.typography.titleMedium)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Text("Automatically cycle through images from your selected folders at regular intervals", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.animation.AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Change every", style = MaterialTheme.typography.bodyMedium)
                    }
                    Slider(
                        value = sliderIndex.toFloat(),
                        onValueChange = { raw ->
                            val nextIndex = raw.roundToInt().coerceIn(0, options.lastIndex)
                            onDurationChange(options[nextIndex].millis)
                        },
                        valueRange = 0f..options.lastIndex.toFloat(),
                        steps = (options.size - 2).coerceAtLeast(0)
                    )
                    Text("Next change in ${selectedOption.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Change when screen unlocks", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = changeImageOnUnlock, onCheckedChange = onChangeImageOnUnlockChange)
            }
        }
    }
}