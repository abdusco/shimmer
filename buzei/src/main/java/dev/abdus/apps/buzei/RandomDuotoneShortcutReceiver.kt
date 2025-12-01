package dev.abdus.apps.buzei

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that cycles through duotone presets in round-robin fashion.
 *
 * To trigger from another app or command line:
 * - Via adb: adb shell am broadcast -a dev.abdus.apps.buzei.action.RANDOM_DUOTONE
 * - Via code: context.sendBroadcast(Intent("dev.abdus.apps.buzei.action.RANDOM_DUOTONE"))
 */
class RandomDuotoneShortcutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d(TAG, "Received request to handle broadcast")
        if (intent?.action == ACTION_RANDOM_DUOTONE) {
            Log.d(TAG, "Received request for next duotone preset")
            BuzeiWallpaperService.requestNextDuotonePreset()
        }
    }

    companion object {
        private const val TAG = "RandomDuotoneReceiver"
        const val ACTION_RANDOM_DUOTONE = "dev.abdus.apps.buzei.action.RANDOM_DUOTONE"
    }
}

