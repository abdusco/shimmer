package dev.abdus.apps.shimmer.ui.settings

import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun SourcesTab(
    modifier: Modifier = Modifier,
    state: SourcesUiState,
    actions: SourcesActions,
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
                wallpaperUri = state.currentWallpaperUri,
                wallpaperName = state.currentWallpaperName,
                onViewImage = actions.onViewCurrentWallpaper,
            )
        }

        item {
            FolderThumbnailSlider(
                imageFolders = state.imageFolders,
                onFolderClick = actions.onNavigateToFolderDetail,
                onOpenFolderSelection = actions.onNavigateToFolderSelection,
            )
        }

        item {
            ImageCycleSettingsSection(
                enabled = state.imageCycleEnabled,
                intervalMillis = state.imageCycleIntervalMillis,
                cycleImageOnUnlock = state.cycleImageOnUnlock,
                onImageCycleEnabledChange = actions.onImageCycleEnabledChange,
                onImageCycleIntervalChange = actions.onImageCycleIntervalChange,
                onCycleImageOnUnlockChange = actions.onCycleImageOnUnlockChange,
            )
        }
    }
}

@Composable
private fun CurrentWallpaperCard(
    wallpaperUri: Uri?,
    wallpaperName: String?,
    onViewImage: () -> Unit,
) {
    val displayName = wallpaperName ?: "No wallpaper"

    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clickable(enabled = wallpaperUri != null) {
                    onViewImage()
                },
        ) {
            AnimatedContent(
                targetState = wallpaperUri,
                transitionSpec = {
                    fadeIn(animationSpec = tween(750)) togetherWith
                            fadeOut(animationSpec = tween(750))
                },
                label = "wallpaper_crossfade",
            ) { currentUri ->
                if (currentUri != null) {
                    AsyncImage(
                        modifier = Modifier.fillMaxSize(),
                        model = currentUri,
                        contentDescription = "Current wallpaper",
                        contentScale = ContentScale.Crop,
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
                    .padding(16.dp),
            ) {
                Text(displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FolderThumbnailSlider(
    imageFolders: List<ImageFolderUiModel>,
    onFolderClick: (Long, String) -> Unit,
    onOpenFolderSelection: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = PADDING_Y, horizontal = PADDING_X),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Text("Image sources", style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = onOpenFolderSelection) { Text("Manage") }
            }

            if (imageFolders.isEmpty()) {
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("No folders selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    items(imageFolders, key = { it.uri }) { folder ->
                        FolderThumbnailLarge(
                            folder = folder,
                            onClick = { onFolderClick(folder.id, folder.displayName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderThumbnailLarge(
    folder: ImageFolderUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filter = remember(folder.enabled) {
        if (folder.enabled) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }

    Surface(
        modifier = modifier
            .size(160.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (folder.thumbnailUri != null) {
            AsyncImage(
                modifier = Modifier.fillMaxSize(),
                model = folder.thumbnailUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = filter,
            )
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Icon(Icons.Default.Folder, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
