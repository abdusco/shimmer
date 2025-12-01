package dev.abdus.apps.buzei

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Handles scanning image folders, caching their contents, and selecting the
 * next candidate URI to render. Keeps the expensive DocumentFile operations
 * out of the wallpaper engine to simplify its responsibilities.
 */
class ImageFolderRepository(private val context: Context) {

    private var folderUris: List<String> = emptyList()
    private val folderContents = mutableMapOf<String, List<Uri>>()
    private val lastDisplayedUriByFolder = mutableMapOf<String, Uri?>()
    private var lastDisplayedUri: Uri? = null
    private var currentFolderIndex = 0

    fun updateFolders(uris: List<String>) {
        folderUris = uris
        folderContents.clear()
        lastDisplayedUriByFolder.clear()
        lastDisplayedUri = null
        currentFolderIndex = 0
    }

    fun hasFolders(): Boolean = folderUris.isNotEmpty()

    fun nextImageUri(): Uri? {
        val folders = folderUris
        if (folders.isEmpty()) {
            return null
        }
        val folderCount = folders.size
        repeat(folderCount) { attempt ->
            val index = (currentFolderIndex + attempt) % folderCount
            val folderUri = folders[index]
            val candidate = selectCandidateFromFolder(folderUri)
            if (candidate != null) {
                currentFolderIndex = (index + 1) % folderCount
                lastDisplayedUri = candidate
                lastDisplayedUriByFolder[folderUri] = candidate
                return candidate
            }
        }
        return null
    }

    private fun selectCandidateFromFolder(folderUri: String): Uri? {
        val images = ensureFolderContents(folderUri) ?: return null
        val candidate = selectNextUriForFolder(images, folderUri)
        if (candidate != null) {
            return candidate
        }
        val refreshed = ensureFolderContents(folderUri, forceRefresh = true) ?: return null
        return selectNextUriForFolder(refreshed, folderUri)
    }

    private fun ensureFolderContents(
        folderUri: String,
        forceRefresh: Boolean = false
    ): List<Uri>? {
        if (!forceRefresh) {
            folderContents[folderUri]?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val scanned = scanFolder(folderUri)
        return if (scanned.isNotEmpty()) {
            folderContents[folderUri] = scanned
            scanned
        } else {
            folderContents.remove(folderUri)
            null
        }
    }

    private fun scanFolder(folderUri: String): List<Uri> {
        return try {
            val documentFile =
                DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return emptyList()
            documentFile.listFiles()
                .filter { it.isFile && it.type?.startsWith("image/") == true }
                .map { it.uri }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun selectNextUriForFolder(images: List<Uri>, folderUri: String): Uri? {
        if (images.isEmpty()) {
            return null
        }
        val lastForFolder = lastDisplayedUriByFolder[folderUri]
        val lastGlobal = lastDisplayedUri
        val filtered = images.filter { it != lastForFolder && it != lastGlobal }
        val source = if (filtered.isNotEmpty()) filtered else images
        return source.shuffled().firstOrNull()
    }
}
