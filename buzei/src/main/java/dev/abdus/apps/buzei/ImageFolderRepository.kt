package dev.abdus.apps.buzei

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlin.random.Random

/**
 * Simplified image folder repository.
 * Scans folders, caches contents, and returns random images in round-robin fashion.
 */
class ImageFolderRepository(private val context: Context) {

    private var folderUris: List<String> = emptyList()
    private val folderContents = mutableMapOf<String, List<Uri>>()
    private var lastDisplayedUri: Uri? = null
    private var currentFolderIndex = 0

    fun updateFolders(uris: List<String>) {
        folderUris = uris
        folderContents.clear()
        lastDisplayedUri = null
        currentFolderIndex = 0
    }

    fun hasFolders(): Boolean = folderUris.isNotEmpty()

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
        folderContents[folderUri]?.let { return it }

        // Scan and cache
        val scanned = scanFolder(folderUri)
        if (scanned.isNotEmpty()) {
            folderContents[folderUri] = scanned
        }
        return scanned
    }

    private fun scanFolder(folderUri: String): List<Uri> {
        return try {
            val documentFile = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
                ?: return emptyList()

            documentFile.listFiles()
                .filter { it.isFile && it.type?.startsWith("image/") == true }
                .map { it.uri }
        } catch (_: SecurityException) {
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
