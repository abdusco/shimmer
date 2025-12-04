package dev.abdus.apps.shimmer

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Utility for checking wallpaper status.
 */
object WallpaperUtil {

    /**
     * Checks if this app is currently set as the active live wallpaper.
     */
    fun isActiveWallpaper(context: Context): Boolean {
        val wallpaperManager = WallpaperManager.getInstance(context)
        val wallpaperInfo = wallpaperManager.wallpaperInfo

        return wallpaperInfo?.component == ComponentName(
            context,
            ShimmerWallpaperService::class.java
        )
    }

    fun openWallpaperPicker(context: Context) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, ShimmerWallpaperService::class.java)
            )
        }
       context.startActivity(intent)
    }
}

