package dev.abdus.apps.shimmer

import android.net.Uri
import androidx.core.net.toUri

object SharedFolderResolver {
    private const val DEFAULT_RELATIVE_PATH = "Pictures/Shimmer/Shared/"
    private const val DEFAULT_DISPLAY_PATH = "Pictures/Shimmer/Shared"
    private const val DEFAULT_SHARED_URI = "shimmer-shared://pictures/shared"

    fun getDefaultSharedUri(): Uri {
        return DEFAULT_SHARED_URI.toUri()
    }

    fun getEffectiveSharedUri(preferences: WallpaperPreferences): Uri {
        return preferences.getSharedFolderUri() ?: getDefaultSharedUri()
    }

    fun getDefaultRelativePath(): String = DEFAULT_RELATIVE_PATH

    fun getDefaultDisplayPath(): String = DEFAULT_DISPLAY_PATH

    fun isDefaultSharedUri(uri: Uri): Boolean {
        return uri.toString() == DEFAULT_SHARED_URI
    }
}