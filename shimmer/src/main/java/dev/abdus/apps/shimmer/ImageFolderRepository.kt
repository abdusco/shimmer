package dev.abdus.apps.shimmer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simplified image folder repository.
 * Scans folders, caches contents, and returns random images in round-robin fashion.
 */
class ImageFolderRepository(
    private val context: Context
) {
    companion object {
        // Use the same TAG as ShimmerWallpaperService so logs appear in the same filter
        private const val TAG = "ShimmerWallpaperService"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var folderUris: List<String> = emptyList()
    private val folderContents = mutableMapOf<String, List<Uri>>()
    private val _imageCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val imageCounts: StateFlow<Map<String, Int>> = _imageCounts.asStateFlow()
    @Volatile
    private var lastDisplayedUri: Uri? = null
    @Volatile
    private var currentFolderIndex = 0

    fun updateFolders(uris: List<String>, initialCounts: Map<String, Int> = emptyMap()) {
        val previousUris = folderUris.toSet()
        val newUris = uris.filter { it !in previousUris }
        val removedUris = previousUris.filter { it !in uris }
        
        folderUris = uris
        
        // Only remove cache for folders that are no longer in the list
        synchronized(folderContents) {
            removedUris.forEach { folderContents.remove(it) }
        }
        
        lastDisplayedUri = null
        currentFolderIndex = 0
        
        // Initialize counts from preferences (for immediate display)
        val updatedCounts = _imageCounts.value.toMutableMap()
        removedUris.forEach { updatedCounts.remove(it) }
        initialCounts.forEach { (uri, count) ->
            if (uri in uris) {
                updatedCounts[uri] = count
            }
        }
        _imageCounts.value = updatedCounts
        
        // Scan newly added folders asynchronously in the background
        newUris.forEach { uri ->
            repositoryScope.launch {
                getFolderImages(uri)
            }
        }
        
        // Also scan existing folders that don't have cached contents (for refresh scenarios)
        uris.forEach { uri ->
            val hasCache = synchronized(folderContents) {
                folderContents.containsKey(uri)
            }
            if (!hasCache && uri !in newUris) {
                repositoryScope.launch {
                    getFolderImages(uri)
                }
            }
        }
    }

    fun cleanup() {
        // No-op now that polling is removed
    }

    /**
     * Get the number of images in a folder. This will scan the folder if not cached.
     */
    fun getImageCount(folderUri: String): Int {
        return getFolderImages(folderUri).size
    }

    /**
     * Force refresh the cache for a specific folder by scanning it again.
     * This runs asynchronously in the background.
     */
    fun refreshFolder(folderUri: String) {
        Log.d(TAG, "Refreshing folder: $folderUri")
        synchronized(folderContents) {
            folderContents.remove(folderUri)
        }
        // Trigger a scan asynchronously
        repositoryScope.launch {
            getFolderImages(folderUri)
        }
    }

    fun hasFolders(): Boolean {
        return folderUris.isNotEmpty()
    }

    /**
     * Checks if a given image URI is valid and belongs to any of the current folders.
     * Returns true if the URI exists, is readable, and is a descendant of any folder.
     * Uses document ID prefix matching for efficient checking without scanning folders.
     */
    fun isImageUriValid(uri: Uri): Boolean {
        if (folderUris.isEmpty()) return false

        // First verify the file exists and is readable
        val file = try {
            DocumentFile.fromSingleUri(context, uri)
        } catch (e: Exception) {
            Log.w(TAG, "isImageUriValid: Failed to access URI: $uri", e)
            return false
        }

        if (file == null || !file.exists() || !file.canRead()) {
            return false
        }

        // Get the document ID of the image file
        val imageDocumentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            Log.w(TAG, "isImageUriValid: Could not get document ID for $uri", e)
            return false
        }

        // Check if the image's document ID starts with any folder's document ID
        folderUris.forEach { folderUriString ->
            try {
                val folderUri = Uri.parse(folderUriString)
                val folderDocumentId = DocumentsContract.getTreeDocumentId(folderUri)
                
                // Check if image is a descendant of this folder
                // Document IDs use format "storage:path/to/file", so we check if the image
                // document ID starts with the folder document ID followed by "/" or equals it
                if (imageDocumentId == folderDocumentId || 
                    imageDocumentId.startsWith("$folderDocumentId/")) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "isImageUriValid: Failed to process folder URI $folderUriString", e)
                // Skip invalid folder URI
                return@forEach
            }
        }

        return false
    }

    /**
     * Returns next random image from folders in round-robin order.
     * Tries to avoid showing the same image twice in a row.
     */
    fun nextImageUri(): Uri? {
        if (folderUris.isEmpty()) return null

        // Try each folder once in round-robin order
        repeat(folderUris.size) {
            val folderUri = folderUris[currentFolderIndex]
            val images = getFolderImages(folderUri)

            if (images.isNotEmpty()) {
                val candidate = selectRandomImage(images)
                currentFolderIndex = (currentFolderIndex + 1) % folderUris.size
                lastDisplayedUri = candidate
                return candidate
            }

            // No images in this folder, try next
            currentFolderIndex = (currentFolderIndex + 1) % folderUris.size
        }

        return null
    }

    private fun getFolderImages(folderUri: String): List<Uri> {
        // Return cached if available
        synchronized(folderContents) {
            folderContents[folderUri]?.let { return it }
        }

        // Scan and cache
        val scanned = scanFolder(folderUri)
        val count = scanned.size
        synchronized(folderContents) {
            folderContents[folderUri] = scanned
        }
        
        // Update image counts flow
        val updatedCounts = _imageCounts.value.toMutableMap()
        updatedCounts[folderUri] = count
        _imageCounts.value = updatedCounts
        
        return scanned
    }

    private fun scanFolder(folderUri: String): List<Uri> {
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                ?: return emptyList()

            documentFile.listFiles()
                .filter { it.isFile && it.type?.startsWith("image/") == true }
                .map { it.uri }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan folder $folderUri", e)
            emptyList()
        }
    }

    private fun selectRandomImage(images: List<Uri>): Uri {
        // If only one image or no last displayed, just pick random
        if (images.size == 1 || lastDisplayedUri == null) {
            return images[Random.nextInt(images.size)]
        }

        // Try to avoid showing same image twice
        val candidates = images.filter { it != lastDisplayedUri }
        return if (candidates.isNotEmpty()) {
            candidates[Random.nextInt(candidates.size)]
        } else {
            // All images are the last one (shouldn't happen), just return any
            images[Random.nextInt(images.size)]
        }
    }
}
