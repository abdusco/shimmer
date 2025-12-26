package dev.abdus.apps.shimmer

import android.net.Uri
import androidx.core.net.toUri

object FavoritesFolderResolver {
    private const val DEFAULT_RELATIVE_PATH = "Pictures/Shimmer/"
    private const val DEFAULT_DISPLAY_PATH = "Pictures/Shimmer"
    private const val DEFAULT_FAVORITES_URI = "shimmer-favorites://pictures/shimmer"

    fun getDefaultFavoritesUri(): Uri {
        return DEFAULT_FAVORITES_URI.toUri()
    }

    fun getEffectiveFavoritesUri(preferences: WallpaperPreferences): Uri {
        return preferences.getFavoritesFolderUri() ?: getDefaultFavoritesUri()
    }

    fun getDefaultRelativePath(): String = DEFAULT_RELATIVE_PATH

    fun getDefaultDisplayPath(): String = DEFAULT_DISPLAY_PATH

    fun isDefaultFavoritesUri(uri: Uri): Boolean {
        return uri.toString() == DEFAULT_FAVORITES_URI
    }
}
