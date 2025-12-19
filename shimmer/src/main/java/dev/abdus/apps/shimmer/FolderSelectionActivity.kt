package dev.abdus.apps.shimmer

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.abdus.apps.shimmer.ShimmerTheme
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
        setContent {
            ShimmerTheme {
                FolderSelectionScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSelectionScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember { WallpaperPreferences.create(context) }
    var imageFolders by remember { mutableStateOf(preferences.getImageFolders()) }
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { ImageFolderRepository(context) }
    val imageCounts by repository.imageCounts.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val newFolder = ImageFolder(uri = it.toString(), thumbnailUri = null, imageCount = null)
                val nextList = (imageFolders + newFolder).distinctBy { folder -> folder.uri }
                imageFolders = nextList
                preferences.setImageFolders(nextList)
            }
        }
    )

    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WallpaperPreferences.KEY_IMAGE_FOLDERS) {
                imageFolders = preferences.getImageFolders()
            }
        }
        preferences.registerListener(listener)
        onDispose {
            preferences.unregisterListener(listener)
        }
    }

    LaunchedEffect(imageFolders) {
        // Load/validate thumbnails for folders that need them
        val foldersNeedingThumbnails = imageFolders.filter { folder ->
            folder.thumbnailUri == null || !isValidThumbnailUri(context, folder.thumbnailUri)
        }

        val updatedFolders = if (foldersNeedingThumbnails.isNotEmpty()) {
            foldersNeedingThumbnails.map { folder ->
                async(Dispatchers.IO) {
                    val thumbnailUri = getFolderThumbnailUri(context, folder.uri, folder.thumbnailUri)
                    folder.copy(thumbnailUri = thumbnailUri?.toString())
                }
            }.awaitAll().associateBy { it.uri }
        } else {
            emptyMap()
        }

        val finalList = imageFolders.map { folder ->
            updatedFolders[folder.uri] ?: folder
        }

        if (updatedFolders.isNotEmpty()) {
            imageFolders = finalList
            preferences.setImageFolders(finalList)
        }

        // Update repository with current folders
        val initialCounts = imageFolders.mapNotNull { folder ->
            folder.imageCount?.let { folder.uri to it }
        }.toMap()
        repository.updateFolders(imageFolders.map { it.uri }, initialCounts)
    }

    // Update imageFolders with counts from the flow
    LaunchedEffect(imageCounts) {
        val updatedFolders = imageFolders.map { folder ->
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
        topBar = {
            TopAppBar(
                title = { Text("Image Folders") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
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
                        text = "Local image folders",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Select folders containing images to use as wallpaper sources",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            isRefreshing = true
                            imageFolders.forEach { folder ->
                                repository.refreshFolder(folder.uri)
                            }
                            coroutineScope.launch {
                                delay(500)
                                isRefreshing = false
                            }
                        },
                        enabled = !isRefreshing && imageFolders.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = if (isRefreshing) "Refreshing..." else "Refresh")
                    }
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Add")
                    }
                }
            }

            if (imageFolders.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "No folders selected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Add a folder to display your own images",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(imageFolders, key = { it.uri }) { folder ->
                    FolderListItem(
                        folder = folder,
                        onRemove = {
                            val nextList = imageFolders.filter { it.uri != folder.uri }
                            imageFolders = nextList
                            preferences.setImageFolders(nextList)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderListItem(
    folder: ImageFolder,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val folderName = remember(folder.uri) {
        DocumentFile.fromTreeUri(context, folder.uri.toUri())?.name ?: folder.uri
    }
    val folderPath = remember(folder.uri) {
        formatTreeUriPath(folder.uri)
    }
    val imageCount = remember(folder.imageCount) {
        folder.imageCount ?: 0
    }
    val previewUri = remember(folder.thumbnailUri) {
        folder.thumbnailUri?.toUri()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Small thumbnail (64dp)
            Surface(
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                if (previewUri != null) {
                    AsyncImage(
                        modifier = Modifier.fillMaxSize(),
                        model = ImageRequest.Builder(context)
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
            
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = folderPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (imageCount == 1) "1 image" else "$imageCount images",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            TextButton(onClick = onRemove) {
                Text("Remove")
            }
        }
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
                val existingFile = DocumentFile.fromSingleUri(context, existingUri)
                if (existingFile?.exists() == true && existingFile.canRead()) {
                    return@withContext existingUri
                }
            }

            val folder = DocumentFile.fromTreeUri(context, folderUri.toUri()) ?: return@withContext null
            folder.listFiles()
                .asSequence()
                .filter { it.isFile && it.type?.startsWith("image/") == true }
                .map { it.uri }
                .firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }
}

private suspend fun isValidThumbnailUri(context: Context, thumbnailUri: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val uri = thumbnailUri.toUri()
            val file = DocumentFile.fromSingleUri(context, uri)
            file?.exists() == true && file.canRead()
        } catch (_: Exception) {
            false
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
