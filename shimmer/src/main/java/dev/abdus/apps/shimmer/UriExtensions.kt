package dev.abdus.apps.shimmer

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
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

