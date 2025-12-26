package dev.abdus.apps.shimmer.ui.settings

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import dev.abdus.apps.shimmer.FavoritesFolderResolver
import dev.abdus.apps.shimmer.ImageFolderRepository
import dev.abdus.apps.shimmer.SharedFolderResolver
import dev.abdus.apps.shimmer.WallpaperPreferences
import dev.abdus.apps.shimmer.ui.ShimmerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderSelectionUiState(
    val folders: List<ImageFolderUiModel> = emptyList(),
    val favoritesFolder: ImageFolderUiModel? = null,
    val sharedFolder: ImageFolderUiModel? = null,
    val isUsingDefaultFavorites: Boolean = true,
    val isUsingDefaultShared: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = true,
)

data class ImageFolderUiModel(
    val id: Long,
    val uri: String,
    val displayName: String,
    val displayPath: String,
    val thumbnailUri: Uri?,
    val imageCount: Int,
    val enabled: Boolean,
)

class FolderSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = WallpaperPreferences.create(this)
        val repo = ImageFolderRepository(this)
        val viewModel = FolderSelectionViewModel(repo, prefs)

        setContent {
            ShimmerTheme {
                val uiState by viewModel.uiState.collectAsState()
                val scope = rememberCoroutineScope()
                val folderPicker = rememberFolderPicker()

                FolderSelectionScreen(
                    state = uiState,
                    onBackClick = { finish() },
                    onFolderClick = { id, name ->
                        startActivity(FolderDetailActivity.createIntent(this, id, name))
                    },
                    onAddFolderClick = {
                        scope.launch { folderPicker.pick()?.let { uri -> viewModel.addFolder(uri) } }
                    },
                    onPickFavoritesClick = {
                        scope.launch { folderPicker.pick()?.let { uri -> viewModel.updateFavoritesUri(uri) } }
                    },
                    onPickSharedClick = {
                        scope.launch { folderPicker.pick()?.let { uri -> viewModel.updateSharedUri(uri) } }
                    },
                    onToggleFolder = viewModel::toggleFolderEnabled,
                    onRemoveFolder = viewModel::removeFolder,
                    onRefreshFolder = viewModel::refreshFolder,
                    onRefreshAll = viewModel::refreshAll,
                )
            }
        }
    }
}

class FolderSelectionViewModel(
    private val repository: ImageFolderRepository,
    private val preferences: WallpaperPreferences,
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<FolderSelectionUiState> = combine(
        preferences.favoritesFolderUriFlow(),
        preferences.sharedFolderUriFlow(),
        repository.foldersMetadataFlow,
        _isRefreshing,
    ) { favoritesUri, sharedUri, metadata, refreshing ->
        mapToUiState(favoritesUri, sharedUri, metadata, refreshing)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FolderSelectionUiState(),
    )

    private suspend fun mapToUiState(
        favoritesUri: Uri?,
        sharedUri: Uri?,
        metadata: Map<String, ImageFolderRepository.Companion.FolderMetadata>,
        refreshing: Boolean,
    ): FolderSelectionUiState = withContext(Dispatchers.Default) {
        val effectiveFavUri = favoritesUri ?: FavoritesFolderResolver.getDefaultFavoritesUri()
        val favUriStr = effectiveFavUri.toString()
        
        val effectiveSharedUri = sharedUri ?: SharedFolderResolver.getDefaultSharedUri()
        val sharedUriStr = effectiveSharedUri.toString()

        val allUiModels = metadata.map { (uri, meta) ->
            val displayName = when (uri) {
                favUriStr -> "Favorites"
                sharedUriStr -> "Shared"
                else -> repository.getFolderDisplayName(uri)
            }
            ImageFolderUiModel(
                id = meta.folderId,
                uri = uri,
                displayName = displayName,
                displayPath = repository.formatTreeUriPath(uri),
                thumbnailUri = meta.thumbnailUri,
                imageCount = meta.imageCount,
                enabled = meta.isEnabled,
            )
        }

        FolderSelectionUiState(
            folders = allUiModels.filter { it.uri != favUriStr && it.uri != sharedUriStr },
            favoritesFolder = allUiModels.find { it.uri == favUriStr },
            sharedFolder = allUiModels.find { it.uri == sharedUriStr },
            isUsingDefaultFavorites = favoritesUri == null,
            isUsingDefaultShared = sharedUri == null,
            isRefreshing = refreshing,
            isLoading = false,
        )
    }

    fun toggleFolderEnabled(uri: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleFolderEnabled(uri, enabled)
        }
    }

    fun removeFolder(uri: String) {
        viewModelScope.launch {
            repository.removeFolder(uri)
        }
    }

    fun refreshFolder(uri: String) {
        viewModelScope.launch {
            repository.refreshFolder(uri)
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            repository.refreshAllFolders()
            delay(500)
            _isRefreshing.value = false
        }
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            repository.addFolder(uri)
        }
    }

    fun updateFavoritesUri(uri: Uri) {
        preferences.setFavoritesFolderUri(uri)
    }

    fun updateSharedUri(uri: Uri) {
        preferences.setSharedFolderUri(uri)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSelectionScreen(
    state: FolderSelectionUiState,
    onBackClick: () -> Unit,
    onFolderClick: (Long, String) -> Unit,
    onAddFolderClick: () -> Unit,
    onPickFavoritesClick: () -> Unit,
    onPickSharedClick: () -> Unit,
    onToggleFolder: (String, Boolean) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onRefreshFolder: (String) -> Unit,
    onRefreshAll: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Image Folders") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader("Special folders", "System managed image collections") }
            
            state.favoritesFolder?.let { fav ->
                item {
                    FolderCard(
                        folder = fav,
                        onCardClick = { onFolderClick(fav.id, fav.displayName) },
                        onToggleEnabled = { onToggleFolder(fav.uri, it) },
                        menuContent = { closeMenu ->
                            DropdownMenuItem(
                                text = { Text("Change location") },
                                onClick = { onPickFavoritesClick(); closeMenu() },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Force rescan") },
                                onClick = { onRefreshFolder(fav.uri); closeMenu() },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) }
                            )
                        }
                    )
                }
            }

            state.sharedFolder?.let { shared ->
                item {
                    FolderCard(
                        folder = shared,
                        onCardClick = { onFolderClick(shared.id, shared.displayName) },
                        onToggleEnabled = { onToggleFolder(shared.uri, it) },
                        menuContent = { closeMenu ->
                            DropdownMenuItem(
                                text = { Text("Change location") },
                                onClick = { onPickSharedClick(); closeMenu() },
                                leadingIcon = { Icon(Icons.Default.Edit, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Force rescan") },
                                onClick = { onRefreshFolder(shared.uri); closeMenu() },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) }
                            )
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
            item { SectionHeader("Local image folders", "Select folders containing images") }
            item {
                ActionButtons(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefreshAll,
                    onAdd = onAddFolderClick,
                    hasFolders = state.folders.isNotEmpty(),
                )
            }

            if (state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.folders.isEmpty()) {
                item { EmptyStateCard() }
            } else {
                items(state.folders, key = { it.uri }) { folder ->
                    FolderCard(
                        folder = folder,
                        onCardClick = { onFolderClick(folder.id, folder.displayName) },
                        onToggleEnabled = { onToggleFolder(folder.uri, it) },
                        menuContent = { closeMenu ->
                            DropdownMenuItem(
                                text = { Text("Force rescan") },
                                onClick = { onRefreshFolder(folder.uri); closeMenu() },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                onClick = { onRemoveFolder(folder.uri); closeMenu() },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionButtons(isRefreshing: Boolean, onRefresh: () -> Unit, onAdd: () -> Unit, hasFolders: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh, enabled = !isRefreshing && hasFolders, modifier = Modifier.weight(1f)) {
            if (isRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isRefreshing) "Refreshing..." else "Refresh")
        }
        Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add")
        }
    }
}

@Composable
private fun EmptyStateCard() {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Folder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("No folders selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Add a folder to display your own images",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FolderCard(
    folder: ImageFolderUiModel,
    onCardClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    menuContent: @Composable ColumnScope.(() -> Unit) -> Unit,
) {
    val alpha = if (folder.enabled) 1f else 0.6f
    val filter = remember(folder.enabled) {
        if (folder.enabled) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth().clickable { onCardClick() }, 
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(96.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
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
                        Icon(Icons.Default.Folder, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    folder.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    folder.displayPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (folder.imageCount == 1) "1 image" else "${folder.imageCount} images",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = folder.enabled, onCheckedChange = onToggleEnabled)
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "Options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.padding(horizontal = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        menuContent { menuExpanded = false }
                    }
                }
            }
        }
    }
}