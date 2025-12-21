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
    private val folderImagePositions = mutableMapOf<String, Int>()
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
        synchronized(folderImagePositions) {
            removedUris.forEach { folderImagePositions.remove(it) }
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
        synchronized(folderImagePositions) {
            folderImagePositions.remove(folderUri)
        }
        // Trigger a scan asynchronously
        repositoryScope.launch {
            getFolderImages(folderUri)
        }
    }

    /**
     * Refresh all folders by clearing their cache and scanning them again.
     * This runs asynchronously in the background.
     */
    fun refreshAllFolders() {
        Log.d(TAG, "Refreshing all folders")
        val urisToRefresh = folderUris.toList() // Make a copy to avoid concurrent modification
        synchronized(folderContents) {
            folderContents.clear()
        }
        synchronized(folderImagePositions) {
            folderImagePositions.clear()
        }
        // Reset state to ensure fresh start after refresh
        lastDisplayedUri = null
        currentFolderIndex = 0
        // Trigger scans asynchronously for all folders
        urisToRefresh.forEach { uri ->
            repositoryScope.launch {
                getFolderImages(uri)
            }
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
     * Returns next image from folders in round-robin order.
     * Uses shuffle-based selection to ensure uniform distribution:
     * each image appears exactly once before any image repeats.
     */
    fun nextImageUri(): Uri? {
        if (folderUris.isEmpty()) return null

        // Try each folder once in round-robin order
        repeat(folderUris.size) {
            val folderUri = folderUris[currentFolderIndex]
            val images = getFolderImages(folderUri)

            if (images.isNotEmpty()) {
                val candidate = selectNextImage(folderUri, images)

                // Validate URI as safety net (should be rare since scanFolder validates)
                if (isImageUriValid(candidate)) {
                    currentFolderIndex = (currentFolderIndex + 1) % folderUris.size
                    lastDisplayedUri = candidate
                    return candidate
                }

                // Invalid URI detected, clear cache and rescan
                Log.w(TAG, "nextImageUri: Invalid URI detected: $candidate, clearing cache for folder")
                synchronized(folderContents) {
                    folderContents.remove(folderUri)
                }
                synchronized(folderImagePositions) {
                    folderImagePositions.remove(folderUri)
                }
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
        val shuffled = shuffleImages(scanned.toMutableList())
        val count = shuffled.size
        synchronized(folderContents) {
            folderContents[folderUri] = shuffled
        }
        synchronized(folderImagePositions) {
            folderImagePositions[folderUri] = 0
        }

        // Update image counts flow
        val updatedCounts = _imageCounts.value.toMutableMap()
        updatedCounts[folderUri] = count
        _imageCounts.value = updatedCounts

        return shuffled
    }

    private fun scanFolder(folderUri: String): List<Uri> {
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                ?: return emptyList()

            val uris = documentFile.listFiles()
                .filter { it.isFile && it.type?.startsWith("image/") == true }
                .filter { file ->
                    // Validate file exists and is readable before caching its URI
                    // This prevents caching stale URIs for deleted files
                    val exists = file.exists()
                    val canRead = file.canRead()
                    if (!exists || !canRead) {
                        Log.w(TAG, "scanFolder: Skipping invalid file (exists=$exists, canRead=$canRead): ${file.uri}")
                    }
                    exists && canRead
                }
                .map { it.uri }

            Log.d(TAG, "scanFolder: Scanned $folderUri, found ${uris.size} images")
            if (uris.isNotEmpty()) {
                Log.d(TAG, "scanFolder: First URI: ${uris.first()}")
            }

            uris
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan folder $folderUri", e)
            emptyList()
        }
    }

    /**
     * Selects the next image from the shuffled list for a folder.
     * Returns images sequentially from the shuffled list, ensuring uniform distribution.
     * When all images have been shown, reshuffles and starts over.
     */
    private fun selectNextImage(folderUri: String, images: List<Uri>): Uri {
        if (images.isEmpty()) {
            throw IllegalArgumentException("Cannot select from empty image list")
        }

        if (images.size == 1) {
            return images[0]
        }

        // Get current position for this folder
        val currentPosition = synchronized(folderImagePositions) {
            folderImagePositions.getOrDefault(folderUri, 0)
        }

        // If we've reached the end, rescan folder, reshuffle, and reset
        if (currentPosition >= images.size) {
            val rescanned = scanFolder(folderUri)
            val reshuffled = shuffleImages(rescanned.toMutableList())
            synchronized(folderContents) {
                folderContents[folderUri] = reshuffled
            }
            synchronized(folderImagePositions) {
                folderImagePositions[folderUri] = 0
            }
            val updatedCounts = _imageCounts.value.toMutableMap()
            updatedCounts[folderUri] = reshuffled.size
            _imageCounts.value = updatedCounts
            return reshuffled[0]
        }

        // Get the image at current position and advance
        val selectedImage = images[currentPosition]
        synchronized(folderImagePositions) {
            folderImagePositions[folderUri] = currentPosition + 1
        }

        return selectedImage
    }

    /**
     * Shuffles a list of URIs using Fisher-Yates algorithm for uniform random distribution.
     */
    private fun shuffleImages(images: MutableList<Uri>): List<Uri> {
        if (images.size <= 1) {
            return images
        }

        // Fisher-Yates shuffle
        for (i in images.size - 1 downTo 1) {
            val j = Random.nextInt(i + 1)
            val temp = images[i]
            images[i] = images[j]
            images[j] = temp
        }

        return images
    }
}
