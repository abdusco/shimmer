package dev.abdus.apps.shimmer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import dev.abdus.apps.shimmer.database.FolderEntity
import dev.abdus.apps.shimmer.database.ImageEntity
import dev.abdus.apps.shimmer.database.ShimmerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ImageFolderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = ShimmerDatabase.getInstance(appContext)
    private val dao = db.imageDao()

    companion object {
        private const val TAG = "ImageFolderRepository"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        data class FolderMetadata(
            val folderId: Long,
            val imageCount: Int,
            val thumbnailUri: Uri?,
            val isEnabled: Boolean,
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
    val foldersMetadataFlow: Flow<Map<String, Companion.FolderMetadata>> = dao.getFoldersMetadataFlow()
        .map { list ->
            Log.d(TAG, "Metadata flow updated: ${list.size} folders")
            list.associate {
                it.folderUri to Companion.FolderMetadata(
                    it.folderId,
                    it.imageCount,
                    it.firstImageUri?.toUri(),
                    it.isEnabled
                )
            }
        }

    fun setOnCurrentImageInvalidatedListener(
        uriProvider: () -> Uri?,
        onInvalidated: () -> Unit
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
                scope.launch { syncSpecialFolders(prefs) }
            }
        }
        prefs.registerListener(listener)
        scope.launch { syncSpecialFolders(prefs) }
    }

    private suspend fun syncSpecialFolders(preferences: WallpaperPreferences) {
        runCatching {
            val favoritesUri = FavoritesFolderResolver.getEffectiveFavoritesUri(preferences).toString()
            val defaultFavUri = FavoritesFolderResolver.getDefaultFavoritesUri().toString()
            
            val sharedUri = SharedFolderResolver.getEffectiveSharedUri(preferences).toString()
            val defaultSharedUri = SharedFolderResolver.getDefaultSharedUri().toString()

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

                // 3. Ensure Favorites folder entry
                dao.insertFolder(FolderEntity(uri = favoritesUri, isEnabled = true, lastScannedAt = timestamp, createdAt = timestamp))
                
                // 4. Ensure Shared folder entry
                dao.insertFolder(FolderEntity(uri = sharedUri, isEnabled = true, lastScannedAt = timestamp, createdAt = timestamp))
            }

            // 5. Scan both
            dao.getFolderId(favoritesUri)?.let { scanAndIndex(it, favoritesUri) }
            dao.getFolderId(sharedUri)?.let { scanAndIndex(it, sharedUri) }

        }.onFailure {
            Log.e(TAG, "Failed to sync special folders", it)
        }
    }

    suspend fun addFolder(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching {
            val uriStr = uri.toString()
            Log.d(TAG, "Adding folder: $uriStr")
            val timestamp = isoNow()
            val folderId = db.withTransaction {
                val insertedId = dao.insertFolder(FolderEntity(uri = uriStr, isEnabled = true, lastScannedAt = timestamp, createdAt = timestamp))
                if (insertedId != -1L) insertedId else dao.getFolderId(uriStr)
            }
            if (folderId != null) {
                scanAndIndex(folderId, uriStr)
            }
        }.onFailure {
            Log.e(TAG, "Failed to add folder", it)
        }
    }

    suspend fun refreshFolder(uri: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Refreshing folder: $uri")
        val folderId = dao.getFolderId(uri)
        if (folderId != null) {
            scanAndIndex(folderId, uri)
        }
    }

    suspend fun removeFolder(uri: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Removing folder: $uri")
        dao.deleteFolderByUri(uri)
    }

    suspend fun toggleFolderEnabled(uri: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Toggling folder $uri enabled=$enabled")
        val folderId = dao.getFolderId(uri)
        if (folderId != null) {
            dao.updateFolderEnabled(folderId, enabled)
        }
    }

    private suspend fun scanAndIndex(folderId: Long, folderUri: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scanning folder $folderUri (id=$folderId)")
        val images = scanFolder(folderUri)
        Log.d(TAG, "Found ${images.size} images in $folderUri")
        
        val timestamp = isoNow()
        val entities = images.map { ImageEntity(folderId = folderId, uri = it.toString(), createdAt = timestamp) }
        
        runCatching {
            db.withTransaction {
                dao.insertImages(entities)
                dao.deleteInvalidImages(folderId, images.map { it.toString() })
                dao.updateFolderLastScanned(folderId, timestamp)
            }
            Log.d(TAG, "Index complete for $folderUri")
        }.onFailure {
            Log.e(TAG, "Failed to index images for $folderUri", it)
        }
    }

    private fun scanFolder(folderUri: String): List<Uri> {
        val uri = folderUri.toUri()
        return runCatching {
            when {
                FavoritesFolderResolver.isDefaultFavoritesUri(uri) -> scanMediaStoreFavorites()
                uri.scheme == "file" -> scanFileFolder(uri)
                else -> scanDocumentFolder(folderUri, uri)
            }
        }.getOrElse {
            Log.e(TAG, "Failed to scan $folderUri", it)
            emptyList()
        }
    }

    private fun scanFileFolder(uri: Uri): List<Uri> {
        val dir = File(uri.path ?: return emptyList())
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && isImageFile(it.name) }
            ?.map { Uri.fromFile(it) }
            ?: emptyList()
    }

    private fun scanDocumentFolder(folderUri: String, uri: Uri): List<Uri> {
        val doc = DocumentFile.fromTreeUri(appContext, uri) ?: run {
            Log.e(TAG, "Could not open DocumentFile for $folderUri")
            return emptyList()
        }
        return doc.listFiles()
            .filter { it.isFile && (it.type?.startsWith("image/") == true || isImageFile(it.name ?: "")) }
            .filter { it.exists() && it.canRead() }
            .map { it.uri }
    }

    private fun scanMediaStoreFavorites(): List<Uri> {
        val projection = arrayOf(android.provider.MediaStore.Images.Media._ID)
        val selection = "${android.provider.MediaStore.Images.Media.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(FavoritesFolderResolver.getDefaultRelativePath())

        return appContext.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
            buildList {
                while (cursor.moveToNext()) {
                    add(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idIdx)))
                }
            }
        } ?: emptyList()
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.startsWith("image/") == true
    }

    // --- Public API ---

    suspend fun nextImageUri(): Uri? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val now = isoNow()
            
            // 1. Get the next folder in the round-robin cycle
            val folder = dao.getNextRoundRobinFolder() ?: run {
                Log.d(TAG, "No folders available for nextImageUri")
                return@withTransaction null
            }
            
            // 2. Get the next image from that specific folder
            val image = dao.getNextImageFromFolder(folder.id) ?: run {
                Log.d(TAG, "No images found in folder ${folder.uri}")
                return@withTransaction null
            }
            
            // 3. Update timestamps
            dao.updateFolderLastPicked(folder.id, now)
            dao.updateLastShown(image.id, now)
            
            Log.d(TAG, "Next image picked: ${image.uri} from ${folder.uri}")
            image.uri.toUri()
        }
    }

    suspend fun incrementFavoriteRank(uri: Uri) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Incrementing favorite rank for $uri")
        dao.incrementFavoriteRank(uri.toString())
    }

    suspend fun getCurrentImageUri(): Uri? = withContext(Dispatchers.IO) {
        dao.getLatestShownImage()?.uri?.toUri()
    }

    suspend fun getCurrentImageName(): String? = withContext(Dispatchers.IO) {
        val uri = getCurrentImageUri() ?: return@withContext null
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
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

    fun getFolderDisplayName(uriString: String): String {
        val uri = uriString.toUri()
        if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
            return "Favorites"
        }
        if (SharedFolderResolver.isDefaultSharedUri(uri)) {
            return "Shared"
        }

        return runCatching {
            DocumentFile.fromTreeUri(appContext, uri)?.name
        }.getOrNull() ?: uriString
    }

    fun formatTreeUriPath(uriString: String): String {
        return try {
            val uri = uriString.toUri()
            if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
                return FavoritesFolderResolver.getDefaultDisplayPath()
            }
            if (SharedFolderResolver.isDefaultSharedUri(uri)) {
                return SharedFolderResolver.getDefaultDisplayPath()
            }
            if (uri.scheme == "file") {
                return uri.path ?: uriString
            }
            
            // For custom schemes or invalid tree URIs, getTreeDocumentId might throw
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() 
                ?: return uriString

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
            Log.w(TAG, "Failed to format path for $uriString", e)
            uriString
        }
    }

    fun isImageLoadable(uri: Uri): Boolean {
        return runCatching {
            val file = DocumentFile.fromSingleUri(appContext, uri)
            file?.exists() == true && file.canRead()
        }.getOrDefault(false)
    }

    suspend fun isImageManagedAndEnabled(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        dao.isImageManagedAndEnabled(uri.toString())
    }
}
