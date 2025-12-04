package dev.abdus.apps.buzei

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Utility for checking wallpaper status.
 */
object WallpaperUtil {

    /**
     * Checks if Buzei is currently set as the active live wallpaper.
     */
    fun isBuzeiActiveWallpaper(context: Context): Boolean {
        val wallpaperManager = WallpaperManager.getInstance(context)
        val wallpaperInfo = wallpaperManager.wallpaperInfo

        return wallpaperInfo?.component == ComponentName(
            context,
            BuzeiWallpaperService::class.java
        )
    }

    fun openWallpaperPicker(context: Context) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, BuzeiWallpaperService::class.java)
            )
        }
       context.startActivity(intent)
    }
}

