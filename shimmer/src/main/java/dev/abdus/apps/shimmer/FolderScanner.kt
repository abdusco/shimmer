package dev.abdus.apps.shimmer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

private const val TAG = "FolderScanner"

interface ImageScanner {
    fun scan(uri: Uri): List<Uri>
}

private class FileScanner : ImageScanner {
    override fun scan(uri: Uri): List<Uri> {
        val dir = File(uri.path ?: return emptyList())
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.isImageFile() }
            ?.map { Uri.fromFile(it) }
            ?: emptyList()
    }
}

private class DocumentScanner(private val context: Context) : ImageScanner {
    override fun scan(uri: Uri): List<Uri> {
        val doc = DocumentFile.fromTreeUri(context, uri) ?: run {
            Log.e(TAG, "Could not open DocumentFile for $uri")
            return emptyList()
        }
        return doc.listFiles()
            .filter { it.isFile && (it.type?.startsWith("image/") == true || (it.name ?: "").isImageFile()) }
            .filter { it.exists() && it.canRead() }
            .map { it.uri }
    }
}

private class MediaStoreScanner(private val context: Context) : ImageScanner {
    override fun scan(uri: Uri): List<Uri> {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(FavoritesFolderResolver.getDefaultRelativePath())

        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            buildList {
                while (cursor.moveToNext()) {
                    add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idIdx)))
                }
            }
        } ?: emptyList()
    }
}

class FolderScanner(context: Context) {
    private val fileScanner = FileScanner()
    private val documentScanner = DocumentScanner(context)
    private val mediaStoreScanner = MediaStoreScanner(context)

    fun scan(uri: Uri): List<Uri> {
        return runCatching {
            val scanner = when {
                FavoritesFolderResolver.isDefaultFavoritesUri(uri) -> mediaStoreScanner
                uri.scheme == "file" -> fileScanner
                else -> documentScanner
            }
            scanner.scan(uri)
        }.getOrElse {
            Log.e(TAG, "Failed to scan $uri", it)
            emptyList()
        }
    }
}
