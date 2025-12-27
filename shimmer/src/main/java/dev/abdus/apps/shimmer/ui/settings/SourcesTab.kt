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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
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

data class SourcesState(
    val currentWallpaperUri: Uri?,
    val currentWallpaperName: String?,
    val imageFolders: List<ImageFolderUiModel>,
    val imageCycleEnabled: Boolean,
    val imageCycleIntervalMillis: Long,
    val cycleImageOnUnlock: Boolean,
)

sealed interface SourcesAction {
    data object ViewCurrentWallpaper : SourcesAction
    data class NavigateToFolderDetail(val folderId: Long, val folderName: String) : SourcesAction
    data object NavigateToFolderSelection : SourcesAction
    data class SetImageCycleEnabled(val enabled: Boolean) : SourcesAction
    data class SetImageCycleInterval(val intervalMillis: Long) : SourcesAction
    data class SetCycleImageOnUnlock(val enabled: Boolean) : SourcesAction
}

@Composable
fun SourcesTab(
    modifier: Modifier = Modifier,
    state: SourcesState,
    onAction: (SourcesAction) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = 24.dp,
            end = 24.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            CurrentWallpaperCard(
                wallpaperUri = state.currentWallpaperUri,
                wallpaperName = state.currentWallpaperName,
                onViewImage = { onAction(SourcesAction.ViewCurrentWallpaper) },
            )
        }

        item {
            ImageSourcesSection(
                imageFolders = state.imageFolders,
                onFolderClick = { id, name -> onAction(SourcesAction.NavigateToFolderDetail(id, name)) },
                onOpenFolderSelection = { onAction(SourcesAction.NavigateToFolderSelection) },
            )
        }

        item {
            ImageCycleSettingsSection(
                enabled = state.imageCycleEnabled,
                intervalMillis = state.imageCycleIntervalMillis,
                cycleImageOnUnlock = state.cycleImageOnUnlock,
                onImageCycleEnabledChange = { onAction(SourcesAction.SetImageCycleEnabled(it)) },
                onImageCycleIntervalChange = { onAction(SourcesAction.SetImageCycleInterval(it)) },
                onCycleImageOnUnlockChange = { onAction(SourcesAction.SetCycleImageOnUnlock(it)) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageSourcesSection(
    imageFolders: List<ImageFolderUiModel>,
    onFolderClick: (Long, String) -> Unit,
    onOpenFolderSelection: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Text("Image sources", style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = onOpenFolderSelection) { Text("Manage") }
            }

            if (imageFolders.isEmpty()) {
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(200.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("No folders selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val carouselState = rememberCarouselState { imageFolders.size }
                HorizontalMultiBrowseCarousel(
                    state = carouselState,
                    preferredItemWidth = 160.dp,
                    itemSpacing = 8.dp,
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) { index ->
                    val folder = imageFolders[index]
                    FolderThumbnailLarge(
                        folder = folder,
                        onClick = { onFolderClick(folder.id, folder.displayName) },
                        modifier = Modifier.maskClip(MaterialTheme.shapes.large)
                    )
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
            .clickable { onClick() },
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
