package dev.abdus.apps.shimmer

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

private const val TAG = "FolderScanner"

data class ScannedImage(
    val uri: Uri,
    val width: Int?,
    val height: Int?
)

interface ImageScanner {
    fun scan(uri: Uri): List<ScannedImage>
}

private class FileScanner : ImageScanner {
    override fun scan(uri: Uri): List<ScannedImage> {
        val dir = File(uri.path ?: return emptyList())
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.isImageFile() }
            ?.map { file ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                ScannedImage(
                    Uri.fromFile(file),
                    options.outWidth.takeIf { it > 0 },
                    options.outHeight.takeIf { it > 0 }
                )
            }
            ?: emptyList()
    }
}

private class DocumentScanner(private val context: Context) : ImageScanner {
    override fun scan(uri: Uri): List<ScannedImage> {
        val doc = DocumentFile.fromTreeUri(context, uri) ?: run {
            Log.e(TAG, "Could not open DocumentFile for $uri")
            return emptyList()
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return doc.listFiles()
            .filter { it.isFile && (it.type?.startsWith("image/") == true || (it.name ?: "").isImageFile()) }
            .filter { it.exists() && it.canRead() }
            .map { file ->
                context.contentResolver.openInputStream(file.uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                ScannedImage(
                    file.uri,
                    options.outWidth.takeIf { it > 0 },
                    options.outHeight.takeIf { it > 0 }
                )
            }
    }
}

private class MediaStoreScanner(private val context: Context) : ImageScanner {
    override fun scan(uri: Uri): List<ScannedImage> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
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
            val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val width = cursor.getInt(widthIdx).takeIf { it > 0 }
                    val height = cursor.getInt(heightIdx).takeIf { it > 0 }
                    add(
                        ScannedImage(
                            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                            width,
                            height
                        )
                    )
                }
            }
        } ?: emptyList()
    }
}

class FolderScanner(context: Context) {
    private val fileScanner = FileScanner()
    private val documentScanner = DocumentScanner(context)
    private val mediaStoreScanner = MediaStoreScanner(context)

    fun scan(uri: Uri): List<ScannedImage> {
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
