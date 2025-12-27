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

class SharedImagesRepository(
    private val context: Context,
    private val folderRepository: ImageFolderRepository,
    private val preferences: WallpaperPreferences
) {
    private val resolver get() = context.contentResolver

    suspend fun saveSharedImages(uris: List<Uri>): Result<Int> = runCatching {
        val targetFolderUri = SharedFolderResolver.getEffectiveSharedUri(preferences)
        var count = 0
        
        uris.forEach { uri ->
            if (SharedFolderResolver.isDefaultSharedUri(targetFolderUri)) {
                saveToMediaStore(uri)
            } else {
                saveToFolder(targetFolderUri, uri)
            }
            count++
        }
        
        // Refresh the shared folder in the DB
        folderRepository.refreshFolder(targetFolderUri)
        
        count
    }

    private fun saveToFolder(folderUri: Uri, sourceUri: Uri) {
        val tree = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw IOException("Invalid folder URI")

        if (!tree.canWrite()) throw IOException("Cannot write to folder")

        val metadata = extractMetadata(sourceUri)
        
        // Check for exact match (name + size)
        tree.findFile(metadata.name)?.let { existing ->
            if (metadata.size != null && existing.length() == metadata.size) {
                return // Exact same file already exists
            }
        }

        // If name exists but size is different, add timestamp
        val finalName = if (tree.findFile(metadata.name) != null) {
            metadata.name.withTimestamp()
        } else {
            metadata.name
        }

        val target = tree.createFile(metadata.mimeType, finalName) ?: throw IOException("Failed to create file")
        copyFile(sourceUri, target.uri)
    }

    private fun saveToMediaStore(sourceUri: Uri) {
        val metadata = extractMetadata(sourceUri)
        
        // Check for exact match in MediaStore
        metadata.size?.let { size ->
            findExistingMediaStoreFile(metadata.name, size)?.let { return }
        }

        // If name exists but size different, add timestamp
        val finalName = if (mediaStoreFileExists(metadata.name)) {
            metadata.name.withTimestamp()
        } else {
            metadata.name
        }

        val targetUri = insertPendingFile(finalName, metadata.mimeType)

        try {
            copyFile(sourceUri, targetUri)
            markComplete(targetUri)
        } catch (e: Exception) {
            resolver.delete(targetUri, null, null)
            throw e
        }
    }

    private fun copyFile(source: Uri, target: Uri) {
        resolver.openInputStream(source)?.use { input ->
            resolver.openOutputStream(target, "w")?.use { output ->
                input.copyTo(output)
            } ?: throw IOException("Cannot open output stream")
        } ?: throw IOException("Cannot open input stream")
    }

    private data class FileMetadata(val name: String, val size: Long?, val mimeType: String)

    private fun extractMetadata(uri: Uri): FileMetadata {
        val mimeType = resolver.getType(uri) ?: "image/jpeg"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"

        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val rawName = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "shared"
                    val size = cursor.getLong(1).takeIf { it > 0 }
                    FileMetadata(rawName.sanitize().ensureExtension(extension), size, mimeType)
                } else null
            } ?: FileMetadata("shared.$extension", null, mimeType)
    }

    private fun insertPendingFile(name: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, SharedFolderResolver.getDefaultRelativePath())
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

    private fun findExistingMediaStoreFile(fileName: String, size: Long): Uri? {
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                       "${MediaStore.Images.Media.RELATIVE_PATH}=? AND " +
                       "${MediaStore.Images.Media.SIZE}=?"
        val args = arrayOf(fileName, SharedFolderResolver.getDefaultRelativePath(), size.toString())

        return resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            selection,
            args,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            } else null
        }
    }

    private fun mediaStoreFileExists(fileName: String): Boolean {
        return resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(fileName, SharedFolderResolver.getDefaultRelativePath()),
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
