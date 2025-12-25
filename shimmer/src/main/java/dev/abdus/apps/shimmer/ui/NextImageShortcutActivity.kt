package dev.abdus.apps.shimmer.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import dev.abdus.apps.shimmer.Actions
import dev.abdus.apps.shimmer.WallpaperUtil

class NextImageShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Shortcut requested next image")

        if (WallpaperUtil.isActiveWallpaper(this)) {
            Actions.Companion.requestNextImage(this)
        } else {
            // Not active wallpaper, show splash screen
            val intent = Intent(this, SplashActivity::class.java)
            startActivity(intent)
        }

        finish()
    }

    companion object {
        private const val TAG = "NextImageShortcutAct"
    }
}
