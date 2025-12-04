package dev.abdus.apps.shimmer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

class NextImageShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Shortcut requested next image")

        if (WallpaperUtil.isActiveWallpaper(this)) {
            BuzeiWallpaperService.requestNextImage(this)
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
