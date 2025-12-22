package dev.abdus.apps.shimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter


class Actions {
    companion object {
        const val ACTION_NEXT_IMAGE = "dev.abdus.apps.shimmer.action.NEXT_IMAGE"
        const val ACTION_NEXT_DUOTONE = "dev.abdus.apps.shimmer.action.RANDOM_DUOTONE"
        const val ACTION_SET_BLUR_PERCENT = "dev.abdus.apps.shimmer.action.SET_BLUR_PERCENT"
        const val ACTION_ENABLE_BLUR = "dev.abdus.apps.shimmer.action.ENABLE_BLUR"
        const val ACTION_REFRESH_FOLDERS = "dev.abdus.apps.shimmer.action.REFRESH_FOLDERS"
        const val ACTION_ADD_TO_FAVORITES = "dev.abdus.apps.shimmer.action.ADD_TO_FAVORITES"
        const val ACTION_FAVORITE_ADDED = "dev.abdus.apps.shimmer.action.FAVORITE_ADDED"

        const val EXTRA_FAVORITE_URI = "favorite_uri"
        const val EXTRA_FAVORITE_DISPLAY_NAME = "favorite_display_name"

        /**
         * Send a broadcast to request next image.
         * Can be called from other apps using:
         * context.sendBroadcast(Intent("dev.abdus.apps.shimmer.action.NEXT_IMAGE"))
         *
         * Or via adb:
         * adb shell am broadcast -a dev.abdus.apps.shimmer.action.NEXT_IMAGE
         */
        fun requestNextImage(context: Context) {
            context.sendBroadcast(Intent(ACTION_NEXT_IMAGE))
        }

        /**
         * Send a broadcast to request next duotone preset.
         * Can be called from other apps using:
         * context.sendBroadcast(Intent("dev.abdus.apps.shimmer.action.RANDOM_DUOTONE"))
         *
         * Or via adb:
         * adb shell am broadcast -a dev.abdus.apps.shimmer.action.RANDOM_DUOTONE
         */
        fun requestNextDuotonePreset(context: Context) {
            context.sendBroadcast(Intent(ACTION_NEXT_DUOTONE))
        }

        /**
         * Send a broadcast to trigger folder refresh/scan.
         * Can be called from other apps using:
         * context.sendBroadcast(Intent("dev.abdus.apps.shimmer.action.REFRESH_FOLDERS"))
         *
         * Or via adb:
         * adb shell am broadcast -a dev.abdus.apps.shimmer.action.REFRESH_FOLDERS
         */
        fun requestRefreshFolders(context: Context) {
            context.sendBroadcast(Intent(ACTION_REFRESH_FOLDERS))
        }

        fun requestAddToFavorites(context: Context) {
            context.sendBroadcast(Intent(ACTION_ADD_TO_FAVORITES))
        }

        fun broadcastFavoriteAdded(
            context: Context,
            result: FavoriteSaveResult,
        ) {
            val intent = Intent(ACTION_FAVORITE_ADDED).apply {
                putExtra(EXTRA_FAVORITE_URI, result.uri)
                putExtra(EXTRA_FAVORITE_DISPLAY_NAME, result.displayName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(
                    context.contentResolver,
                    result.displayName,
                    result.uri
                )
            }
            context.sendBroadcast(intent)
        }

        fun registerReceivers(context: Context, shortcutReceiver: BroadcastReceiver) {
            val filter = IntentFilter().apply {
                addAction(ACTION_NEXT_IMAGE)
                addAction(ACTION_NEXT_DUOTONE)
                addAction(ACTION_SET_BLUR_PERCENT)
                addAction(ACTION_ENABLE_BLUR)
                addAction(ACTION_REFRESH_FOLDERS)
                addAction(ACTION_ADD_TO_FAVORITES)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                shortcutReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    data class BlurPercentAction(val percent: Float) {
        companion object {
            const val EXTRA_BLUR_PERCENT = "blur_percent"
            fun fromIntent(intent: Intent): BlurPercentAction? {
                if (intent.action != ACTION_SET_BLUR_PERCENT) {
                    return null
                }

                val percent = intent.getFloatExtra(EXTRA_BLUR_PERCENT, 0f)
                return BlurPercentAction(percent.coerceIn(0f, 1f))
            }
        }
    }
}
