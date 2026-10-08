package dev.abdus.apps.shimmer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FavoriteSaveResult(val uri: Uri, val displayName: String)

class FavoritesRepository(
    private val context: Context,
    private val preferences: WallpaperPreferences,
    private val folderRepository: ImageFolderRepository,
) {
    private val resolver get() = context.contentResolver

    suspend fun saveFavorite(sourceUri: Uri): Result<FavoriteSaveResult> = saveMutex.withLock {
        runCatching {
            val customFolderUri = preferences.getFavoritesFolderUri()
            val favoritesUri = customFolderUri ?: FavoritesFolderResolver.getDefaultFavoritesUri()
            val folderId = folderRepository.getFolderId(favoritesUri)
                ?: run {
                    folderRepository.registerSpecialFolders()
                    folderRepository.getFolderId(favoritesUri)
                }
                ?: throw IOException("Favorites folder is not registered")
            val originalImage = folderRepository.getImageByUri(sourceUri)
            val metadata = extractMetadata(sourceUri)

            val existing = findExistingFavorite(sourceUri, metadata, folderId, originalImage?.folderId)
            val result = existing ?: run {
                val saved = customFolderUri
                    ?.let { saveToFolder(it, sourceUri, metadata) }
                    ?: saveToMediaStore(sourceUri, metadata)

                try {
                    folderRepository.addSingleImageToFolder(
                        folderUri = favoritesUri,
                        imageUri = saved.uri,
                        width = originalImage?.width,
                        height = originalImage?.height,
                        fileSize = metadata.size,
                        sourceUri = sourceUri,
                    )
                } catch (e: Exception) {
                    runCatching {
                        if (customFolderUri == null) {
                            resolver.delete(saved.uri, null, null)
                        } else {
                            DocumentFile.fromSingleUri(context, saved.uri)?.delete()
                        }
                    }
                    throw e
                }
                saved
            }

            folderRepository.incrementFavoriteRank(sourceUri)
            result
        }
    }

    private suspend fun findExistingFavorite(
        sourceUri: Uri,
        metadata: FileMetadata,
        folderId: Long,
        sourceFolderId: Long?,
    ): FavoriteSaveResult? {
        if (sourceFolderId == folderId) {
            return FavoriteSaveResult(sourceUri, metadata.name)
        }

        folderRepository.getImageBySource(folderId, sourceUri)?.let { image ->
            if (canRead(image.uri)) {
                return FavoriteSaveResult(image.uri, getDisplayName(image.uri))
            }
            folderRepository.markImageAsInvalid(image.uri)
        }

        // Older favorites have no sourceUri. Match their contents once, then record the
        // source so later requests use the indexed lookup above.
        val candidates = folderRepository.getImageUrisAndSizesForFolder(folderId)
            .filter { metadata.size == null || it.fileSize == null || it.fileSize == metadata.size }
        if (candidates.isEmpty()) return null
        val sourceDigest = digest(sourceUri)
        for (candidate in candidates) {
            val candidateDigest = runCatching { digest(candidate.uri) }.getOrNull() ?: continue
            if (sourceDigest.contentEquals(candidateDigest)) {
                folderRepository.linkImageToSource(candidate.uri, sourceUri)
                return FavoriteSaveResult(candidate.uri, getDisplayName(candidate.uri))
            }
        }
        return null
    }

    private fun canRead(uri: Uri): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    private fun getDisplayName(uri: Uri): String = resolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "favorite"

    private fun digest(uri: Uri): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        } ?: throw IOException("Cannot read image: $uri")
        return md.digest()
    }

    companion object {
        private val saveMutex = Mutex()
    }

    private data class FileMetadata(
        val name: String,
        val size: Long?,
        val mimeType: String,
        val extension: String,
    )

    private fun extractMetadata(uri: Uri): FileMetadata {
        val mimeType = resolver.getType(uri) ?: "image/jpeg"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"

        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val rawName = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "shimmer"
                    val size = cursor.getLong(1).takeIf { it > 0 }
                    FileMetadata(rawName.sanitize().ensureExtension(extension), size, mimeType, extension)
                } else null
            } ?: FileMetadata("shimmer.$extension", null, mimeType, extension)
    }

    private fun saveToFolder(folderUri: Uri, sourceUri: Uri, metadata: FileMetadata): FavoriteSaveResult {
        val tree = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw IOException("Invalid folder URI")

        if (!tree.canWrite()) throw IOException("Cannot write to folder")

        // DocumentFile.createFile typically appends (1) if it exists on standard providers.
        val target = tree.createFile(metadata.mimeType, metadata.name) ?: throw IOException("Failed to create file")

        try {
            copyFile(sourceUri, target.uri)
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
        // DocumentFile may return a plain document URI, while DocumentScanner indexes
        // children with a tree-qualified URI. Store the scanner's URI form so Room's
        // unique URI index recognizes this file during the next scan.
        val documentId = DocumentsContract.getDocumentId(target.uri)
        val indexedUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, documentId)
        return FavoriteSaveResult(indexedUri, target.name ?: metadata.name)
    }

    private fun saveToMediaStore(sourceUri: Uri, metadata: FileMetadata): FavoriteSaveResult {
        val targetUri = insertPendingFile(metadata.name, metadata.mimeType)

        return try {
            copyFile(sourceUri, targetUri)
            markComplete(targetUri)
            
            val finalName = resolver.query(targetUri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else metadata.name
            } ?: metadata.name

            FavoriteSaveResult(targetUri, finalName)
        } catch (e: Exception) {
            resolver.delete(targetUri, null, null)
            throw e
        }
    }

    private fun insertPendingFile(name: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, FavoritesFolderResolver.getDefaultRelativePath())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")
    }

    private fun markComplete(uri: Uri) {
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null)
    }

    private fun copyFile(source: Uri, target: Uri) {
        resolver.openInputStream(source)?.use { input ->
            resolver.openOutputStream(target, "w")?.use { output ->
                input.copyTo(output)
            } ?: throw IOException("Cannot open output stream")
        } ?: throw IOException("Cannot open input stream")
    }

    private fun String.sanitize() = map { c ->
        if (c.isLetterOrDigit() || c in "._-@# ") c else '_'
    }.joinToString("")

    private fun String.ensureExtension(ext: String) =
        if (contains('.')) this else "$this.$ext"
}
