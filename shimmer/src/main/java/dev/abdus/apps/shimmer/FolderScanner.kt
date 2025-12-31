package dev.abdus.apps.shimmer

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File

private const val TAG = "FolderScanner"

data class ScannedImage(
    val uri: Uri,
    val width: Int?,
    val height: Int?,
    val fileSize: Long?
)

interface ImageScanner {
    fun scan(uri: Uri, existingFiles: Map<Uri, Long?>? = null): List<ScannedImage>
}

private class FileScanner : ImageScanner {
    override fun scan(uri: Uri, existingFiles: Map<Uri, Long?>?): List<ScannedImage> {
        val dir = File(uri.path ?: return emptyList())
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.isImageFile() }
            ?.mapNotNull { file ->
                val fileUri = Uri.fromFile(file)
                val fileSize = file.length().takeIf { it > 0 }
                
                // Check if file is already indexed (URI + size match)
                val isExisting = existingFiles?.get(fileUri) == fileSize
                
                // If existing and size matches, skip bitmap decoding (just verify file exists)
                if (isExisting && file.exists()) {
                    ScannedImage(fileUri, null, null, fileSize)
                } else {
                    // New or changed file - decode bitmap
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    ScannedImage(
                        fileUri,
                        options.outWidth.takeIf { it > 0 },
                        options.outHeight.takeIf { it > 0 },
                        fileSize
                    )
                }
            }
            ?: emptyList()
    }
}

private class DocumentScanner(private val context: Context) : ImageScanner {
    override fun scan(uri: Uri, existingFiles: Map<Uri, Long?>?): List<ScannedImage> {
        val treeId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get tree document ID for $uri", e)
            return emptyList()
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        val results = mutableListOf<ScannedImage>()

        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(mimeIdx)
                    val name = cursor.getString(nameIdx) ?: ""

                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue

                    if (mimeType?.startsWith("image/") == true || name.isImageFile()) {
                        val docId = cursor.getString(idIdx)
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                        val fileSize = cursor.getLong(sizeIdx).takeIf { it > 0 }

                        // Check if file is already indexed (URI + size match)
                        val isExisting = existingFiles?.get(fileUri) == fileSize

                        if (isExisting) {
                            results.add(ScannedImage(fileUri, null, null, fileSize))
                        } else {
                            // New or changed file - decode bitmap
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            try {
                                context.contentResolver.openInputStream(fileUri)?.use {
                                    BitmapFactory.decodeStream(it, null, options)
                                }
                                results.add(ScannedImage(
                                    fileUri,
                                    options.outWidth.takeIf { it > 0 },
                                    options.outHeight.takeIf { it > 0 },
                                    fileSize
                                ))
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to decode bounds for $fileUri", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query children for $uri", e)
        }
        return results
    }
}

private class MediaStoreScanner(private val context: Context) : ImageScanner {
    override fun scan(uri: Uri, existingFiles: Map<Uri, Long?>?): List<ScannedImage> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE
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
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val fileUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val size = cursor.getLong(sizeIdx).takeIf { it > 0 }
                    
                    // Check if file is already indexed (URI + size match)
                    val isExisting = existingFiles?.get(fileUri) == size
                    
                    // If existing and size matches, skip width/height lookup (MediaStore already has them)
                    if (isExisting) {
                        val width = cursor.getInt(widthIdx).takeIf { it > 0 }
                        val height = cursor.getInt(heightIdx).takeIf { it > 0 }
                        add(ScannedImage(fileUri, width, height, size))
                    } else {
                        // New or changed file - include dimensions
                        val width = cursor.getInt(widthIdx).takeIf { it > 0 }
                        val height = cursor.getInt(heightIdx).takeIf { it > 0 }
                        add(ScannedImage(fileUri, width, height, size))
                    }
                }
            }
        } ?: emptyList()
    }
}

class FolderScanner(context: Context) {
    private val fileScanner = FileScanner()
    private val documentScanner = DocumentScanner(context)
    private val mediaStoreScanner = MediaStoreScanner(context)

    fun scan(uri: Uri, existingFiles: Map<Uri, Long?>? = null): List<ScannedImage> {
        return runCatching {
            val scanner = when {
                FavoritesFolderResolver.isDefaultFavoritesUri(uri) -> mediaStoreScanner
                uri.scheme == "file" -> fileScanner
                else -> documentScanner
            }
            scanner.scan(uri, existingFiles)
        }.getOrElse {
            Log.e(TAG, "Failed to scan $uri", it)
            emptyList()
        }
    }
}
