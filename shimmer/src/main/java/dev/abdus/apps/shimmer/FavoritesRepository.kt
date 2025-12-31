package dev.abdus.apps.shimmer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

data class FavoriteSaveResult(val uri: Uri, val displayName: String)

class FavoritesRepository(
    private val context: Context,
    private val preferences: WallpaperPreferences,
    private val folderRepository: ImageFolderRepository,
) {
    private val resolver get() = context.contentResolver

    suspend fun saveFavorite(sourceUri: Uri): Result<FavoriteSaveResult> = runCatching {
        val originalImage = folderRepository.getImageByUri(sourceUri)
        val metadata = extractMetadata(sourceUri)
        val result = preferences.getFavoritesFolderUri()
            ?.let { saveToFolder(it, sourceUri, metadata) }
            ?: saveToMediaStore(sourceUri, metadata)
        
        // Boost the original image's rank in our library
        folderRepository.incrementFavoriteRank(sourceUri)
        
        // Register the new favorite file in the DB immediately without a full folder scan
        val favoritesUri = FavoritesFolderResolver.getEffectiveFavoritesUri(preferences)
        folderRepository.addSingleImageToFolder(
            folderUri = favoritesUri,
            imageUri = result.uri,
            width = originalImage?.width,
            height = originalImage?.height,
            fileSize = metadata.size
        )
        
        result
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

        copyFile(sourceUri, target.uri)
        return FavoriteSaveResult(target.uri, target.name ?: metadata.name)
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