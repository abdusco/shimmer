package dev.abdus.apps.buzei

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

class NextImageShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Shortcut requested next image")
        BuzeiWallpaperService.requestNextImage()
        finish()
    }

    companion object {
        private const val TAG = "NextImageShortcutAct"
    }
}
