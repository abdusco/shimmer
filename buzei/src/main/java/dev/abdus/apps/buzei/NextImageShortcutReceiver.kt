package dev.abdus.apps.buzei

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NextImageShortcutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("NextImageShortcutReceiver", "Received request to handle broadcast")
        if (intent?.action == ACTION_NEXT_IMAGE) {
            Log.d("NextImageShortcutReceiver", "Received request for next image")
            BuzeiWallpaperService.requestNextImage()
        }
    }

    companion object {
        const val ACTION_NEXT_IMAGE = "dev.abdus.apps.buzei.action.NEXT_IMAGE"
    }
}
