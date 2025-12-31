package dev.abdus.apps.shimmer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import dev.abdus.apps.shimmer.database.FolderEntity
import dev.abdus.apps.shimmer.database.ImageEntity
import dev.abdus.apps.shimmer.database.ImageEntry
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ImageFolderRepository(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val prefs = WallpaperPreferences.create(appContext)
    private val db = ShimmerDatabase.getInstance(appContext)
    private val dao = db.imageDao()
    private val scanner = FolderScanner(appContext)

    private val _activeScans = MutableStateFlow<Set<Long>>(emptySet())
    val activeScans: StateFlow<Set<Long>> = _activeScans.asStateFlow()

    companion object {
        private const val TAG = "ImageFolderRepository"

        data class FolderMetadata(
            val folderId: Long,
            val imageCount: Int,
            val thumbnailUri: Uri?,
            val isEnabled: Boolean,
            val isLocal: Boolean,
            val isScanning: Boolean,
        )

        private fun isoNow(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }
    }

    init {
        Log.d(TAG, "Initializing Repository")
        // Handle initial sync
        scope.launch { registerSpecialFolders() }

        // Automatically sync when special folder prefs change
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WallpaperPreferences.KEY_FAVORITES_FOLDER_URI || key == WallpaperPreferences.KEY_SHARED_FOLDER_URI) {
                Log.d(TAG, "Special folder configuration changed ($key), syncing")
                scope.launch { registerSpecialFolders() }
            }
        }
        prefs.registerListener(listener)
    }

    /**
     * Observes library changes and notifies if the current image is no longer valid.
     */
    fun observeImageInvalidation(
        uriProvider: () -> Uri?,
        onInvalidated: () -> Unit,
    ) {
        scope.launch {
            dao.getEnabledFoldersCountFlow()
                .distinctUntilChanged()
                .collect {
                    val currentUri = uriProvider() ?: return@collect
                    if (!isImageManagedAndEnabled(currentUri)) {
                        Log.d(TAG, "Current image $currentUri invalidated by library change")
                        withContext(Dispatchers.Main) {
                            onInvalidated()
                        }
                    }
                }
        }
    }

    /**
     * Flow of folder metadata (URI, count, thumbnail) for the UI.
     */
    val foldersMetadataFlow: Flow<Map<Uri, FolderMetadata>> = combine(
        dao.getFoldersMetadataFlow(),
        activeScans,
    ) { list, scanning ->
        list.associate {
            it.folderUri to FolderMetadata(
                folderId = it.folderId,
                imageCount = it.imageCount,
                thumbnailUri = it.thumbnailUri,
                isEnabled = it.isEnabled,
                isLocal = it.folderUri.isLocalFolder(),
                isScanning = scanning.contains(it.folderId),
            )
        }
    }

    suspend fun registerSpecialFolders() = withContext(Dispatchers.IO) {
        runCatching {
            val favoritesUri = FavoritesFolderResolver.getEffectiveFavoritesUri(prefs)
            val sharedUri = SharedFolderResolver.getEffectiveSharedUri(prefs)
            val timestamp = isoNow()

            db.withTransaction {
                // Cleanup stale default folders if custom ones are active
                val defaultFavUri = FavoritesFolderResolver.getDefaultFavoritesUri()
                if (favoritesUri != defaultFavUri) {
                    dao.deleteFolderByUri(defaultFavUri)
                }

                val defaultSharedUri = SharedFolderResolver.getDefaultSharedUri()
                if (sharedUri != defaultSharedUri) {
                    dao.deleteFolderByUri(defaultSharedUri)
                }

                // Ensure folders exist. OnConflictStrategy.IGNORE handles existing ones.
                dao.insertFolder(FolderEntity(uri = favoritesUri, isEnabled = true, lastScannedAt = null, createdAt = timestamp))
                dao.insertFolder(FolderEntity(uri = sharedUri, isEnabled = true, lastScannedAt = null, createdAt = timestamp))
            }
        }.onFailure {
            Log.e(TAG, "Failed to sync special folders", it)
        }
    }

    suspend fun addFolder(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = isoNow()
            val folderId = db.withTransaction {
                val insertedId = dao.insertFolder(FolderEntity(uri = uri, isEnabled = true, lastScannedAt = timestamp, createdAt = timestamp))
                if (insertedId != -1L) insertedId else dao.getFolderId(uri)
            }
            if (folderId != null) {
                scanAndIndex(folderId, uri)
            }
        }.onFailure {
            Log.e(TAG, "Failed to add folder", it)
        }
    }

    suspend fun refreshFolder(uri: Uri) = withContext(Dispatchers.IO) {
        val folderId = dao.getFolderId(uri)
        if (folderId != null) {
            scanAndIndex(folderId, uri)
        }
    }

    suspend fun removeFolder(uri: Uri) = withContext(Dispatchers.IO) {
        dao.deleteFolderByUri(uri)
    }

    suspend fun toggleFolderEnabled(uri: Uri, enabled: Boolean) = withContext(Dispatchers.IO) {
        val folderId = dao.getFolderId(uri)
        if (folderId != null) {
            dao.updateFolderEnabled(folderId, enabled)
        }
    }

    suspend fun scanAndIndex(folderId: Long, folderUri: Uri) = withContext(Dispatchers.IO) {
        _activeScans.update { it + folderId }
        try {
            val existingFiles = dao.getImageUrisAndSizesForFolder(folderId)
                .associate { it.uri to it.fileSize }
            
            val images = scanner.scan(folderUri, existingFiles)
            val timestamp = isoNow()
            val entities = images.map {
                ImageEntity(
                    folderId = folderId,
                    uri = it.uri,
                    createdAt = timestamp,
                    width = it.width,
                    height = it.height,
                    fileSize = it.fileSize,
                )
            }

            db.withTransaction {
                dao.insertImages(entities)
                dao.deleteInvalidImages(folderId, images.map { it.uri })
                dao.updateFolderLastScanned(folderId, timestamp)
            }
        } finally {
            _activeScans.update { it - folderId }
        }
    }


    suspend fun nextImageUri(): Uri? = withContext(Dispatchers.IO) {
        // Fast, side-effect free query (except for updating timestamps)
        val image = dao.findNextCycleImage() ?: return@withContext null
        
        val now = isoNow()
        db.withTransaction {
            dao.updateFolderLastPicked(image.folderId, now)
            dao.updateLastShown(image.id, now)
        }
        
        image.uri
    }

    suspend fun markImageAsInvalid(uri: Uri) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Marking image as invalid (deleting): $uri")
        dao.deleteImageByUri(uri)
    }

    suspend fun incrementFavoriteRank(uri: Uri) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Incrementing favorite rank for $uri")
        dao.incrementFavoriteRank(uri)
    }

    suspend fun getImageByUri(uri: Uri): ImageEntity? = withContext(Dispatchers.IO) {
        dao.getImageByUri(uri)
    }

    suspend fun addSingleImageToFolder(folderUri: Uri, imageUri: Uri, width: Int? = null, height: Int? = null, fileSize: Long? = null) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Adding single image to folder: $folderUri -> $imageUri")
        val folderId = dao.getFolderId(folderUri)

        if (folderId == null) {
            Log.e(TAG, "Folder $folderUri not found in DB")
            return@withContext
        }

        val timestamp = isoNow()
        val entity = ImageEntity(
            folderId = folderId,
            uri = imageUri,
            createdAt = timestamp,
            width = width,
            height = height,
            fileSize = fileSize,
        )

        runCatching {
            db.withTransaction {
                dao.insertImages(listOf(entity))
                dao.updateFolderLastScanned(folderId, timestamp)
            }
        }.onFailure {
            Log.e(TAG, "Failed to add single image to folder $folderUri", it)
        }
    }

    suspend fun getCurrentImageUri(): Uri? = withContext(Dispatchers.IO) {
        dao.getLatestShownImage()?.uri
    }

    val currentImageUriFlow: Flow<Uri?> = dao.getLatestShownImageFlow()
        .map { it?.uri }

    suspend fun getFolderId(uri: Uri): Long? = withContext(Dispatchers.IO) {
        dao.getFolderId(uri)
    }

    fun getEnabledFoldersCountFlow(): Flow<Int> =
        dao.getEnabledFoldersCountFlow()

    fun getImagesForFolderFlow(folderId: Long): Flow<List<ImageEntry>> =
        dao.getImagesForFolderFlow(folderId)

    suspend fun updateImageLastShown(uri: Uri) = withContext(Dispatchers.IO) {
        dao.updateLastShownByUri(uri, isoNow())
    }

    suspend fun getCurrentImageName(uri: Uri?): String? = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext null
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    suspend fun refreshEnabledFolders() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Refreshing all enabled folders")
        val allFolders = dao.getAllFoldersFlow().first()
        allFolders
            .filter { it.isEnabled }
            .forEach { folder ->
                scanAndIndex(folder.id, folder.uri)
            }
    }

    fun getFolderDisplayName(uri: Uri): String {
        if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
            return "Favorites"
        }
        if (SharedFolderResolver.isDefaultSharedUri(uri)) {
            return "Shared"
        }

        return runCatching {
            DocumentFile.fromTreeUri(appContext, uri)?.name
        }.getOrNull() ?: uri.toString()
    }

    fun formatTreeUriPath(uri: Uri): String {
        return try {
            if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
                return FavoritesFolderResolver.getDefaultDisplayPath()
            }
            if (SharedFolderResolver.isDefaultSharedUri(uri)) {
                return SharedFolderResolver.getDefaultDisplayPath()
            }
            if (uri.scheme == "file") {
                return uri.path ?: uri.toString()
            }

            // For custom schemes or invalid tree URIs, getTreeDocumentId might throw
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: return uri.toString()

            val colonIndex = documentId.indexOf(':')
            val storage = if (colonIndex < 0) documentId else documentId.substring(0, colonIndex)
            val rawPath = if (colonIndex < 0) "" else documentId.substring(colonIndex + 1)
            val storageLabel = when (storage.lowercase()) {
                "primary" -> "sdcard"
                else -> storage
            }
            if (rawPath.isBlank()) storageLabel else {
                val decodedPath = Uri.decode(rawPath).trimStart('/')
                "$storageLabel/$decodedPath"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to format path for ${uri}", e)
            uri.toString()
        }
    }

    fun isImageUriValid(uri: Uri): Boolean = uri.isValidImage(appContext)

    suspend fun isImageManagedAndEnabled(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        dao.isImageManagedAndEnabled(uri)
    }
}
