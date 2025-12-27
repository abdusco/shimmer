package dev.abdus.apps.shimmer

import android.net.Uri
import android.webkit.MimeTypeMap

private const val TAG = "UriExtensions"

fun Uri.isLocalFolder(): Boolean {
    return scheme == "file" ||
            (authority == "com.android.externalstorage.documents")
}

fun String.isImageFile(): Boolean {
    val ext = substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.startsWith("image/") == true
}

