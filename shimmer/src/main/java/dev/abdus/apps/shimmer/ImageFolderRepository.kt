package dev.abdus.apps.shimmer

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ImageFolderRepository(private val context: Context) {
    companion object {
        private const val TAG = "ShimmerWallpaperService"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = mutableMapOf<String, FolderState>()
    private val _imageCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val imageCounts = _imageCounts.asStateFlow()

    @Volatile private var enabledUris = emptyList<String>()
    @Volatile private var currentFolderIdx = 0

    private data class FolderState(val shuffled: List<Uri>, var position: Int = 0)

    fun updateFolders(folders: List<ImageFolder>, initialCounts: Map<String, Int> = emptyMap()) {
        val allUris = folders.map { it.uri }.distinct()
        enabledUris = folders.filter { it.enabled }.map { it.uri }.distinct()

        synchronized(cache) {
            cache.keys.retainAll(allUris.toSet())
        }

        currentFolderIdx = 0
        _imageCounts.value = initialCounts.filterKeys { it in allUris }.toMutableMap()

        allUris.forEach { uri ->
            if (!synchronized(cache) { cache.containsKey(uri) }) {
                scope.launch { scanAndCache(uri) }
            }
        }
    }

    fun refreshFolder(folderUri: String) {
        synchronized(cache) { cache.remove(folderUri) }
        scope.launch { scanAndCache(folderUri) }
    }

    fun refreshAllFolders() {
        synchronized(cache) { cache.clear() }
        currentFolderIdx = 0
        enabledUris.forEach { scope.launch { scanAndCache(it) } }
    }

    fun hasFolders() = enabledUris.isNotEmpty()

    fun getImageCount(folderUri: String): Int {
        return synchronized(cache) { cache[folderUri]?.shuffled?.size } ?: run {
            scanAndCache(folderUri)
            synchronized(cache) { cache[folderUri]?.shuffled?.size ?: 0 }
        }
    }

    fun isImageUriValid(uri: Uri): Boolean {
        if (enabledUris.isEmpty()) return false

        // File URIs
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return false)
            if (!file.exists() || !file.canRead()) return false
            val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return false

            return enabledUris.any { folderUriStr ->
                val folderUri = Uri.parse(folderUriStr)
                if (folderUri.scheme == "file") {
                    val folderPath = runCatching {
                        File(folderUri.path ?: return@runCatching null).canonicalPath
                    }.getOrNull() ?: return@any false
                    filePath.startsWith("$folderPath/") || filePath == folderPath
                } else false
            }
        }

        // MediaStore favorites
        if (uri.scheme == "content") {
            if (enabledUris.any { FavoritesFolderResolver.isDefaultFavoritesUri(Uri.parse(it)) }
                && isMediaStoreFavorite(uri)) {
                return true
            }
        }

        // Document URIs
        val file = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        if (file == null || !file.exists() || !file.canRead()) return false

        val imageDocId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return false

        return enabledUris.any { folderUriStr ->
            runCatching {
                val folderDocId = DocumentsContract.getTreeDocumentId(Uri.parse(folderUriStr))
                imageDocId == folderDocId || imageDocId.startsWith("$folderDocId/")
            }.getOrDefault(false)
        }
    }

    fun nextImageUri(): Uri? {
        if (enabledUris.isEmpty()) return null

        repeat(enabledUris.size) {
            val uri = enabledUris[currentFolderIdx]
            val state = getOrCreateState(uri)

            if (state.shuffled.isNotEmpty()) {
                if (state.position >= state.shuffled.size) {
                    rescanAndReshuffle(uri)
                    state.position = 0
                }

                val candidate = state.shuffled[state.position++]
                if (isImageUriValid(candidate)) {
                    currentFolderIdx = (currentFolderIdx + 1) % enabledUris.size
                    return candidate
                }

                // Invalid URI, clear cache and retry
                synchronized(cache) { cache.remove(uri) }
            }

            currentFolderIdx = (currentFolderIdx + 1) % enabledUris.size
        }

        return null
    }

    private fun getOrCreateState(uri: String): FolderState {
        return synchronized(cache) {
            cache.getOrPut(uri) {
                val images = scanFolder(uri)
                updateCount(uri, images.size)
                FolderState(shuffle(images))
            }
        }
    }

    private fun scanAndCache(uri: String) {
        val images = scanFolder(uri)
        synchronized(cache) {
            cache[uri] = FolderState(shuffle(images))
        }
        updateCount(uri, images.size)
    }

    private fun rescanAndReshuffle(uri: String) {
        val images = scanFolder(uri)
        synchronized(cache) {
            cache[uri] = FolderState(shuffle(images))
        }
        updateCount(uri, images.size)
    }

    private fun scanFolder(folderUri: String): List<Uri> {
        val uri = Uri.parse(folderUri)

        return runCatching {
            when {
                FavoritesFolderResolver.isDefaultFavoritesUri(uri) -> scanMediaStoreFavorites()
                uri.scheme == "file" -> scanFileFolder(uri)
                else -> scanDocumentFolder(uri)
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

    private fun scanDocumentFolder(uri: Uri): List<Uri> {
        val doc = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()

        return doc.listFiles()
            .filter { it.isFile && (it.type?.startsWith("image/") == true || isImageFile(it.name ?: "")) }
            .filter { it.exists() && it.canRead() }
            .map { it.uri }
    }

    private fun scanMediaStoreFavorites(): List<Uri> {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(FavoritesFolderResolver.getDefaultRelativePath())

        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            buildList {
                while (cursor.moveToNext()) {
                    add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idIdx)))
                }
            }
        } ?: emptyList()
    }

    private fun isMediaStoreFavorite(uri: Uri): Boolean {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return false
        val projection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH)
        val selection = "${MediaStore.Images.Media._ID}=?"

        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(id.toString()),
            null
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == FavoritesFolderResolver.getDefaultRelativePath()
        } ?: false
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return false
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.startsWith("image/") == true
    }

    private fun shuffle(images: List<Uri>): List<Uri> {
        if (images.size <= 1) return images
        return images.toMutableList().apply {
            for (i in size - 1 downTo 1) {
                val j = Random.nextInt(i + 1)
                this[i] = this[j].also { this[j] = this[i] }
            }
        }
    }

    private fun updateCount(uri: String, count: Int) {
        _imageCounts.value = _imageCounts.value.toMutableMap().apply { this[uri] = count }
    }
}
