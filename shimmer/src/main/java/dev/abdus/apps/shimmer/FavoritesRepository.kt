package dev.abdus.apps.shimmer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FavoriteSaveResult(val uri: Uri, val displayName: String)

class FavoritesRepository(
    private val context: Context,
    private val preferences: WallpaperPreferences,
    private val folderRepository: ImageFolderRepository,
) {
    private val resolver get() = context.contentResolver

    suspend fun saveFavorite(sourceUri: Uri): Result<FavoriteSaveResult> = runCatching {
        val metadata = extractMetadata(sourceUri)
        val result = preferences.getFavoritesFolderUri()
            ?.let { saveToFolder(it, sourceUri, metadata) }
            ?: saveToMediaStore(sourceUri, metadata)
        
        // Boost the original image's rank in our library
        folderRepository.incrementFavoriteRank(sourceUri)
        
        // Refresh the favorites folder in the DB to pick up the new file
        val favoritesUri = FavoritesFolderResolver.getEffectiveFavoritesUri(preferences).toString()
        folderRepository.refreshFolder(favoritesUri)
        
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

        // Reuse existing file with same name and size
        tree.findFile(metadata.name)?.let { existing ->
            if (metadata.size != null && existing.length() == metadata.size) {
                return FavoriteSaveResult(existing.uri, existing.name ?: metadata.name)
            }
        }

        val finalName = tree.findFile(metadata.name)?.let { metadata.name.withTimestamp() } ?: metadata.name
        val target = tree.createFile(metadata.mimeType, finalName) ?: throw IOException("Failed to create file")

        copyFile(sourceUri, target.uri)
        return FavoriteSaveResult(target.uri, finalName)
    }

    private fun saveToMediaStore(sourceUri: Uri, metadata: FileMetadata): FavoriteSaveResult {
        metadata.size?.let { size ->
            findExistingFile(metadata.name, size)?.let { return it }
        }

        val finalName = if (fileExists(metadata.name)) metadata.name.withTimestamp() else metadata.name
        val targetUri = insertPendingFile(finalName, metadata.mimeType)

        return try {
            copyFile(sourceUri, targetUri)
            markComplete(targetUri)
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

    private fun findExistingFile(fileName: String, size: Long): FavoriteSaveResult? {
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                       "${MediaStore.Images.Media.RELATIVE_PATH}=? AND " +
                       "${MediaStore.Images.Media.SIZE}=?"
        val args = arrayOf(fileName, FavoritesFolderResolver.getDefaultRelativePath(), size.toString())

        return resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            selection,
            args,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                FavoriteSaveResult(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)),
                    cursor.getString(1)
                )
            } else null
        }
    }

    private fun fileExists(fileName: String): Boolean {
        return resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(fileName, FavoritesFolderResolver.getDefaultRelativePath()),
            null
        )?.use { it.moveToFirst() } ?: false
    }

    private fun String.sanitize() = map { c ->
        if (c.isLetterOrDigit() || c in "._-@# ") c else '_'
    }.joinToString("")

    private fun String.ensureExtension(ext: String) =
        if (contains('.')) this else "$this.$ext"

    private fun String.withTimestamp(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val (base, ext) = substringBeforeLast('.') to substringAfterLast('.', "")
        return if (ext.isNotBlank()) "${base}_$timestamp.$ext" else "${base}_$timestamp"
    }
}