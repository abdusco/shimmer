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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ImageFolderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = ShimmerDatabase.getInstance(appContext)
    private val dao = db.imageDao()
    private val scanner = FolderScanner(appContext)

    private val _activeScans = MutableStateFlow<Set<Long>>(emptySet())
    private val activeScans: StateFlow<Set<Long>> = _activeScans.asStateFlow()

    companion object {
        private const val TAG = "ImageFolderRepository"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /**
     * Flow of folder metadata (URI, count, thumbnail) for the UI.
     */
    val foldersMetadataFlow: Flow<Map<Uri, FolderMetadata>> = combine(
        dao.getFoldersMetadataFlow(),
        activeScans,
    ) { list, scanning ->
        Log.d(TAG, "Metadata flow updated: ${list.size} folders")
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

    fun setOnCurrentImageInvalidatedListener(
        uriProvider: () -> Uri?,
        onInvalidated: () -> Unit,
    ) {
        scope.launch {
            dao.getEnabledFoldersCountFlow().collect {
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

    init {
        Log.d(TAG, "Initializing Repository")
        val prefs = WallpaperPreferences.create(appContext)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WallpaperPreferences.KEY_FAVORITES_FOLDER_URI || key == WallpaperPreferences.KEY_SHARED_FOLDER_URI) {
                Log.d(TAG, "Special folder configuration changed ($key), syncing")
                scope.launch { syncSpecialFolders() }
            }
        }
        prefs.registerListener(listener)
    }

    suspend fun syncSpecialFolders() = withContext(Dispatchers.IO) {
        val preferences = WallpaperPreferences.create(appContext)
        runCatching {
            val favoritesUri = FavoritesFolderResolver.getEffectiveFavoritesUri(preferences)
            val defaultFavUri = FavoritesFolderResolver.getDefaultFavoritesUri()

            val sharedUri = SharedFolderResolver.getEffectiveSharedUri(preferences)
            val defaultSharedUri = SharedFolderResolver.getDefaultSharedUri()

            val timestamp = isoNow()

            db.withTransaction {
                // 1. Cleanup stale default favorites if custom one is active
                if (favoritesUri != defaultFavUri) {
                    Log.d(TAG, "Custom favorites active, removing default internal favorites from DB")
                    dao.deleteFolderByUri(defaultFavUri)
                }

                // 2. Cleanup stale default shared if custom one is active
                if (sharedUri != defaultSharedUri) {
                    Log.d(TAG, "Custom shared active, removing default internal shared from DB")
                    dao.deleteFolderByUri(defaultSharedUri)
                }

                // 3. Ensure Favorites folder entry (lastScannedAt = null means it needs initial scan)
                dao.insertFolder(FolderEntity(uri = favoritesUri, isEnabled = true, lastScannedAt = null, createdAt = timestamp))

                // 4. Ensure Shared folder entry
                dao.insertFolder(FolderEntity(uri = sharedUri, isEnabled = true, lastScannedAt = null, createdAt = timestamp))
            }
            Log.d(TAG, "Special folders registered in DB")
        }.onFailure {
            Log.e(TAG, "Failed to sync special folders", it)
        }
    }

    suspend fun addFolder(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Adding folder: $uri")
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
        Log.d(TAG, "Refreshing folder: $uri")
        val folderId = dao.getFolderId(uri)
        if (folderId != null) {
            scanAndIndex(folderId, uri)
        }
    }

    suspend fun removeFolder(uri: Uri) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Removing folder: $uri")
        dao.deleteFolderByUri(uri)
    }

    suspend fun toggleFolderEnabled(uri: Uri, enabled: Boolean) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Toggling folder $uri enabled=$enabled")
        val folderId = dao.getFolderId(uri)
        if (folderId != null) {
            dao.updateFolderEnabled(folderId, enabled)
        }
    }

    private suspend fun scanAndIndex(folderId: Long, folderUri: Uri) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scanning folder $folderUri (id=$folderId)")
        _activeScans.update { it + folderId }
        try {
            val images = scanner.scan(folderUri)
            Log.d(TAG, "Found ${images.size} images in $folderUri")

            val timestamp = isoNow()
            val entities = images.map {
                ImageEntity(
                    folderId = folderId,
                    uri = it.uri,
                    createdAt = timestamp,
                    width = it.width,
                    height = it.height,
                )
            }

            runCatching {
                db.withTransaction {
                    dao.insertImages(entities)
                    dao.deleteInvalidImages(folderId, images.map { it.uri })
                    dao.updateFolderLastScanned(folderId, timestamp)
                }
                Log.d(TAG, "Index complete for $folderUri")
            }.onFailure {
                Log.e(TAG, "Failed to index images for $folderUri", it)
            }
        } finally {
            _activeScans.update { it - folderId }
        }
    }


    suspend fun nextImageUri(): Uri? = withContext(Dispatchers.IO) {
        // Ensure special folders are registered
        syncSpecialFolders()

        val maxAttempts = dao.getEnabledFoldersCountFlow().first()
        var attempts = 0

        while (attempts < maxAttempts) {
            attempts++

            // 1. Get the next folder in the round-robin cycle
            val folder = dao.getNextRoundRobinFolder() ?: run {
                Log.d(TAG, "No folders available for nextImageUri")
                return@withContext null
            }

            // 2. If it has never been scanned, or if it's empty, try to scan it now
            if (folder.lastScannedAt == null) {
                Log.d(TAG, "Folder ${folder.uri} never scanned, scanning now")
                scanAndIndex(folder.id, folder.uri)
            }

            val image = db.withTransaction {
                val now = isoNow()

                // 3. Get the next image from that specific folder
                val pickedImage = dao.getNextImageFromFolder(folder.id)

                // 4. Update folder lastPickedAt regardless of whether we found an image
                // so we move to the next folder in the cycle next time
                dao.updateFolderLastPicked(folder.id, now)

                if (pickedImage != null) {
                    dao.updateLastShown(pickedImage.id, now)
                    Log.d(TAG, "Next image picked: ${pickedImage.uri} from ${folder.uri}")
                }
                pickedImage
            }

            if (image != null) {
                return@withContext image.uri
            } else {
                Log.d(TAG, "Folder ${folder.uri} is empty after scan, trying next folder")
            }
        }

        Log.d(TAG, "Could not find any images after checking all enabled folders")
        null
    }

    suspend fun incrementFavoriteRank(uri: Uri) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Incrementing favorite rank for $uri")
        dao.incrementFavoriteRank(uri)
    }

    suspend fun getCurrentImageUri(): Uri? = withContext(Dispatchers.IO) {
        dao.getLatestShownImage()?.uri
    }

    val currentImageUriFlow: Flow<Uri?> = dao.getLatestShownImageFlow()
        .map { it?.uri }

    suspend fun getFolderId(uri: Uri): Long? = withContext(Dispatchers.IO) {
        dao.getFolderId(uri)
    }

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

    fun refreshAllFolders() {
        Log.d(TAG, "Refreshing all folders")
        scope.launch {
            val allFolders = dao.getAllFoldersFlow().first()
            allFolders.forEach { folder ->
                scanAndIndex(folder.id, folder.uri)
            }
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
