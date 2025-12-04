package dev.abdus.apps.shimmer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

class RandomDuotoneShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Shortcut requested next duotone preset")

        if (WallpaperUtil.isActiveWallpaper(this)) {
            BuzeiWallpaperService.requestNextDuotonePreset(this)
        } else {
            // Not active wallpaper, show splash screen
            val intent = Intent(this, SplashActivity::class.java)
            startActivity(intent)
        }
        finish()
    }


    companion object {
        private const val TAG = "RandomDuotoneShortcut"
    }
}

