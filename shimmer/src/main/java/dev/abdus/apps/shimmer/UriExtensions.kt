package dev.abdus.apps.shimmer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

private const val TAG = "UriExtensions"

fun Uri.isLocalFolder(): Boolean {
    return scheme == "file" ||
            (authority == "com.android.externalstorage.documents")
}

fun String.isImageFile(): Boolean {
    val ext = substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.startsWith("image/") == true
}

fun Uri.isValidImage(context: Context): Boolean {
    return runCatching {
        val file = DocumentFile.fromSingleUri(context, this)
        file?.exists() == true && file.canRead()
    }.getOrDefault(false)
}

fun String.getFolderDisplayName(context: Context): String {
    val uri = toUri()
    if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
        return "Favorites"
    }
    if (SharedFolderResolver.isDefaultSharedUri(uri)) {
        return "Shared"
    }

    return runCatching {
        DocumentFile.fromTreeUri(context, uri)?.name
    }.getOrNull() ?: this
}

fun String.formatTreeUriPath(): String {
    return try {
        val uri = toUri()
        if (FavoritesFolderResolver.isDefaultFavoritesUri(uri)) {
            return FavoritesFolderResolver.getDefaultDisplayPath()
        }
        if (SharedFolderResolver.isDefaultSharedUri(uri)) {
            return SharedFolderResolver.getDefaultDisplayPath()
        }
        if (uri.scheme == "file") {
            return uri.path ?: this
        }

        // For custom schemes or invalid tree URIs, getTreeDocumentId might throw
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return this

        val colonIndex = documentId.indexOf(':')
        val storage = if (colonIndex < 0) documentId else documentId.substring(0, colonIndex)
        val rawPath = if (colonIndex < 0) "" else documentId.substring(colonIndex + 1)
        val storageLabel = when (storage.lowercase()) {
            "primary" -> "sdcard"
            else -> storage
        }
        if (rawPath.isBlank()) storageLabel else {
            val decodedPath = Uri.decode(rawPath).trimStart('/')
            "$storageLabel/$decodedPath"
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to format path for $this", e)
        this
    }
}