package dev.abdus.apps.shimmer.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import dev.abdus.apps.shimmer.Actions
import dev.abdus.apps.shimmer.WallpaperUtil

class AddToFavoritesShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Shortcut requested add to favorites")

        if (WallpaperUtil.isActiveWallpaper(this)) {
            Actions.requestAddToFavorites(this)
        } else {
            val intent = Intent(this, SplashActivity::class.java)
            startActivity(intent)
        }
        finish()
    }

    companion object {
        private const val TAG = "AddFavShortcutAct"
    }
}
