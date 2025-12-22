package dev.abdus.apps.shimmer

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val FOLDER_PADDING_X = 24.dp
val FOLDER_PADDING_Y = 24.dp

class FolderSelectionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        setContent { ShimmerTheme { FolderSelectionScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSelectionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember { WallpaperPreferences.create(context) }
    var favoritesFolderUri by remember { mutableStateOf(preferences.getFavoritesFolderUri()) }
    var imageFolders by remember {
        mutableStateOf(
            mergeFavoritesFolder(
                preferences.getImageFolders(),
                FavoritesFolderResolver.getEffectiveFavoritesUri(preferences),
            )
        )
    }
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { ImageFolderRepository(context) }
    val imageCounts by repository.imageCounts.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    val folderPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
            onResult = { uri: Uri? ->
                uri?.let {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val newFolder =
                        ImageFolder(
                            uri = it.toString(),
                            thumbnailUri = null,
                            imageCount = null,
                            enabled = true,
                        )
                    val nextList = (imageFolders + newFolder).distinctBy { folder -> folder.uri }
                    imageFolders = nextList
                    preferences.setImageFolders(nextList)
                }
            },
        )

    val favoritesPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
            onResult = { uri: Uri? ->
                uri?.let {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val effectiveFavoritesUri =
                        FavoritesFolderResolver.getEffectiveFavoritesUri(preferences)
                    val favoritesEntry =
                        imageFolders.find { folder ->
                            folder.uri == effectiveFavoritesUri.toString()
                        }
                    val favoritesEnabled = favoritesEntry?.enabled ?: true
                    val nextList =
                        imageFolders
                            .filter { folder -> folder.uri != effectiveFavoritesUri.toString() }
                            .toMutableList()

                    val newUri = it.toString()
                    val existing = nextList.indexOfFirst { folder -> folder.uri == newUri }
                    if (existing >= 0) {
                        nextList[existing] = nextList[existing].copy(enabled = favoritesEnabled)
                    } else {
                        nextList.add(ImageFolder(uri = newUri, enabled = favoritesEnabled))
                    }
                    favoritesFolderUri = it
                    preferences.setFavoritesFolderUri(it)
                    imageFolders = nextList
                    preferences.setImageFolders(nextList)
                }
            },
        )

    DisposableEffect(preferences) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (
                    key == WallpaperPreferences.KEY_IMAGE_FOLDERS ||
                        key == WallpaperPreferences.KEY_FAVORITES_FOLDER_URI
                ) {
                    favoritesFolderUri = preferences.getFavoritesFolderUri()
                    imageFolders =
                        mergeFavoritesFolder(
                            preferences.getImageFolders(),
                            FavoritesFolderResolver.getEffectiveFavoritesUri(preferences),
                        )
                }
            }
        preferences.registerListener(listener)
        onDispose { preferences.unregisterListener(listener) }
    }

    LaunchedEffect(imageFolders) {
        // Load/validate thumbnails for folders that need them
        val foldersNeedingThumbnails =
            imageFolders.filter { folder ->
                folder.thumbnailUri == null || !isValidThumbnailUri(context, folder.thumbnailUri)
            }

        val updatedFolders =
            if (foldersNeedingThumbnails.isNotEmpty()) {
                foldersNeedingThumbnails
                    .map { folder ->
                        async(Dispatchers.IO) {
                            val thumbnailUri =
                                getFolderThumbnailUri(context, folder.uri, folder.thumbnailUri)
                            folder.copy(thumbnailUri = thumbnailUri?.toString())
                        }
                    }
                    .awaitAll()
                    .associateBy { it.uri }
            } else {
                emptyMap()
            }

        val finalList = imageFolders.map { folder -> updatedFolders[folder.uri] ?: folder }

        if (updatedFolders.isNotEmpty()) {
            imageFolders = finalList
            preferences.setImageFolders(finalList)
        }

        // Update repository with current folders
        val initialCounts =
            imageFolders
                .mapNotNull { folder -> folder.imageCount?.let { folder.uri to it } }
                .toMap()
        repository.updateFolders(imageFolders, initialCounts)
    }

    // Update imageFolders with counts from the flow
    LaunchedEffect(imageCounts) {
        val updatedFolders =
            imageFolders.map { folder ->
                val count = imageCounts[folder.uri]
                if (count != null && folder.imageCount != count) {
                    folder.copy(imageCount = count)
                } else {
                    folder
                }
            }
        if (updatedFolders != imageFolders) {
            imageFolders = updatedFolders
            preferences.setImageFolders(updatedFolders)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Image Folders") }) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding =
                PaddingValues(
                    start = FOLDER_PADDING_X,
                    top = FOLDER_PADDING_Y,
                    end = FOLDER_PADDING_X,
                    bottom = FOLDER_PADDING_Y,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Favorites",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Saved wallpapers and quick picks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                val effectiveFavoritesUri =
                    FavoritesFolderResolver.getEffectiveFavoritesUri(preferences)
                val favoritesFolder =
                    imageFolders.find { folder -> folder.uri == effectiveFavoritesUri.toString() }
                        ?: ImageFolder(uri = effectiveFavoritesUri.toString(), enabled = true)

                FavoritesFolderItem(
                    folder = favoritesFolder,
                    isUsingDefault = favoritesFolderUri == null,
                    onPickFolder = { favoritesPickerLauncher.launch(null) },
                    onToggleEnabled = { enabled ->
                        val nextList =
                            imageFolders.map { existing ->
                                if (existing.uri == favoritesFolder.uri) {
                                    existing.copy(enabled = enabled)
                                } else {
                                    existing
                                }
                            }
                        imageFolders = nextList
                        preferences.setImageFolders(nextList)
                    },
                )
            }

            item { Spacer(modifier = Modifier.width(12.dp)) }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Local image folders",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Select folders containing images to use as wallpaper sources",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            isRefreshing = true
                            imageFolders.forEach { folder -> repository.refreshFolder(folder.uri) }
                            coroutineScope.launch {
                                delay(500)
                                isRefreshing = false
                            }
                        },
                        enabled = !isRefreshing && imageFolders.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (isRefreshing) "Refreshing..." else "Refresh")
                    }
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Add")
                    }
                }
            }

            if (imageFolders.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "No folders selected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Add a folder to display your own images",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                val effectiveFavoritesUri =
                    FavoritesFolderResolver.getEffectiveFavoritesUri(preferences)
                val regularFolders =
                    imageFolders.filter { it.uri != effectiveFavoritesUri.toString() }
                items(regularFolders, key = { it.uri }) { folder ->
                    FolderListItem(
                        folder = folder,
                        onToggleEnabled = { enabled ->
                            val nextList =
                                imageFolders.map { existing ->
                                    if (existing.uri == folder.uri) {
                                        existing.copy(enabled = enabled)
                                    } else {
                                        existing
                                    }
                                }
                            imageFolders = nextList
                            preferences.setImageFolders(nextList)
                        },
                        onRemove = {
                            val nextList = imageFolders.filter { it.uri != folder.uri }
                            imageFolders = nextList
                            preferences.setImageFolders(nextList)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderListItem(
    folder: ImageFolder,
    onToggleEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val folderName =
        remember(folder.uri) {
            DocumentFile.fromTreeUri(context, folder.uri.toUri())?.name ?: folder.uri
        }
    val folderPath = remember(folder.uri) { formatTreeUriPath(folder.uri) }
    val imageCount = remember(folder.imageCount) { folder.imageCount ?: 0 }
    val previewUri = remember(folder.thumbnailUri) { folder.thumbnailUri?.toUri() }

    val containerAlpha = if (folder.enabled) 1f else 0.6f
    var expanded by rememberSaveable(folder.uri) { mutableStateOf(false) }
    val thumbnailFilter =
        remember(folder.enabled) {
            if (folder.enabled) {
                null
            } else {
                val matrix = ColorMatrix().apply { setToSaturation(0f) }
                ColorFilter.colorMatrix(matrix)
            }
        }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Small thumbnail (64dp)
                Surface(
                    modifier = Modifier.size(96.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    if (previewUri != null) {
                        AsyncImage(
                            modifier = Modifier.fillMaxSize(),
                            model =
                                ImageRequest.Builder(context)
                                    .data(previewUri)
                                    .crossfade(true)
                                    .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = thumbnailFilter,
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = containerAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = folderPath,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = containerAlpha),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (imageCount == 1) "1 image" else "$imageCount images",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = containerAlpha),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = folder.enabled, onCheckedChange = onToggleEnabled)
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
                    }
                }
            }
            AnimatedVisibility(visible = expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onRemove,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                    ) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesFolderItem(
    folder: ImageFolder,
    isUsingDefault: Boolean,
    onPickFolder: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val folderPath = remember(folder.uri) { formatTreeUriPath(folder.uri) }
    val imageCount = remember(folder.imageCount) { folder.imageCount ?: 0 }
    val previewUri = remember(folder.thumbnailUri) { folder.thumbnailUri?.toUri() }
    val containerAlpha = if (folder.enabled) 1f else 0.6f
    val thumbnailFilter =
        remember(folder.enabled) {
            if (folder.enabled) {
                null
            } else {
                val matrix = ColorMatrix().apply { setToSaturation(0f) }
                ColorFilter.colorMatrix(matrix)
            }
        }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (previewUri != null) {
                    AsyncImage(
                        modifier = Modifier.fillMaxSize(),
                        model =
                            ImageRequest.Builder(context).data(previewUri).crossfade(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = thumbnailFilter,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Favorites",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = containerAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (isUsingDefault) FavoritesFolderResolver.getDefaultDisplayPath()
                        else folderPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = containerAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (imageCount == 1) "1 image" else "$imageCount images",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = containerAlpha),
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = folder.enabled, onCheckedChange = onToggleEnabled)
                TextButton(onClick = onPickFolder) { Text("Choose") }
            }
        }
    }
}

private fun mergeFavoritesFolder(
    folders: List<ImageFolder>,
    favoritesUri: Uri?,
): List<ImageFolder> {
    if (favoritesUri == null) return folders
    val favoritesString = favoritesUri.toString()
    return if (folders.any { it.uri == favoritesString }) {
        folders
    } else {
        folders + ImageFolder(uri = favoritesString, enabled = true)
    }
}

private suspend fun getFolderThumbnailUri(
    context: Context,
    folderUri: String,
    existingThumbnailUri: String? = null,
): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            if (existingThumbnailUri != null) {
                val existingUri = existingThumbnailUri.toUri()
                if (existingUri.scheme == "file") {
                    val file = java.io.File(existingUri.path ?: "")
                    if (file.exists() && file.canRead()) {
                        return@withContext existingUri
                    }
                } else {
                    val existingFile = DocumentFile.fromSingleUri(context, existingUri)
                    if (existingFile?.exists() == true && existingFile.canRead()) {
                        return@withContext existingUri
                    }
                }
            }

            val uri = folderUri.toUri()
            if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
                return@withContext queryMediaStoreFavoriteThumbnail(context)
            }
            if (uri.scheme == "file") {
                val dir = java.io.File(uri.path ?: return@withContext null)
                dir.listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile && isImageFileName(it.name) }
                    ?.map { Uri.fromFile(it) }
                    ?.firstOrNull()
            } else {
                val folder = DocumentFile.fromTreeUri(context, uri) ?: return@withContext null
                folder
                    .listFiles()
                    .asSequence()
                    .filter {
                        it.isFile &&
                            (it.type?.startsWith("image/") == true ||
                                isImageFileName(it.name ?: ""))
                    }
                    .map { it.uri }
                    .firstOrNull()
            }
        } catch (_: SecurityException) {
            null
        }
    }
}

private suspend fun isValidThumbnailUri(context: Context, thumbnailUri: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val uri = thumbnailUri.toUri()
            if (uri.scheme == "file") {
                val file = java.io.File(uri.path ?: "")
                file.exists() && file.canRead()
            } else {
                val file = DocumentFile.fromSingleUri(context, uri)
                file?.exists() == true && file.canRead()
            }
        } catch (_: Exception) {
            false
        }
    }
}

private fun formatTreeUriPath(uriString: String): String {
    return try {
        val uri = uriString.toUri()
        if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
            return FavoritesFolderResolver.getDefaultDisplayPath()
        }
        if (uri.scheme == "file") {
            return uri.path ?: uriString
        }
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val colonIndex = documentId.indexOf(':')
        val storage =
            if (colonIndex < 0) {
                documentId
            } else {
                documentId.substring(0, colonIndex)
            }
        val rawPath =
            if (colonIndex < 0) {
                ""
            } else {
                documentId.substring(colonIndex + 1)
            }
        val storageLabel =
            when (storage.lowercase()) {
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

private fun queryMediaStoreFavoriteThumbnail(context: Context): Uri? {
    val resolver = context.contentResolver
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val selection = "${MediaStore.Images.Media.RELATIVE_PATH}=?"
    val selectionArgs = arrayOf(FavoritesFolderResolver.getDefaultRelativePath())
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    return resolver
        .query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )
        ?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(idIndex)
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            } else {
                null
            }
        }
}

private fun isImageFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension.isBlank()) return false
    val mimeType =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: return false
    return mimeType.startsWith("image/")
}
