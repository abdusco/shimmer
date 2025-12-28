package dev.abdus.apps.shimmer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.DocumentsContract


class Actions {
    companion object {
        const val ACTION_NEXT_IMAGE = "dev.abdus.apps.shimmer.action.NEXT_IMAGE"
        const val ACTION_NEXT_DUOTONE = "dev.abdus.apps.shimmer.action.RANDOM_DUOTONE"
        const val ACTION_SET_BLUR_PERCENT = "dev.abdus.apps.shimmer.action.SET_BLUR_PERCENT"
        const val ACTION_ENABLE_BLUR = "dev.abdus.apps.shimmer.action.ENABLE_BLUR"
        const val ACTION_REFRESH_FOLDERS = "dev.abdus.apps.shimmer.action.REFRESH_FOLDERS"
        const val ACTION_ADD_TO_FAVORITES = "dev.abdus.apps.shimmer.action.ADD_TO_FAVORITES"
        const val ACTION_FAVORITE_ADDED = "dev.abdus.apps.shimmer.action.FAVORITE_ADDED"
        const val ACTION_SET_IMAGE = "dev.abdus.apps.shimmer.action.SET_IMAGE"

        const val EXTRA_FAVORITE_URI = "favorite_uri"
        const val EXTRA_FAVORITE_DISPLAY_NAME = "favorite_display_name"
        const val EXTRA_IMAGE_URI = "image_uri"

        /**
         * Send a broadcast to request next image.
         */
        fun requestNextImage(context: Context) {
            context.sendBroadcast(Intent(ACTION_NEXT_IMAGE))
        }

        /**
         * Send a broadcast to request next duotone preset.
         */
        fun requestNextDuotonePreset(context: Context) {
            context.sendBroadcast(Intent(ACTION_NEXT_DUOTONE))
        }

        /**
         * Send a broadcast to trigger folder refresh/scan.
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
                    result.uri,
                )
            }
            context.sendBroadcast(intent)
        }

        fun broadcastSetWallpaper(context: Context, uri: Uri) {
            val intent = Intent(ACTION_SET_IMAGE).apply {
                putExtra(EXTRA_IMAGE_URI, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.sendBroadcast(intent)
        }

        fun openFolderInFileManager(context: Context, uri: Uri) {
            val attempts = listOf(
                { DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)) to "vnd.android.document/directory" },
                { uri to "vnd.android.document/directory" },
                { uri to null }
            )

            attempts.forEach { attempt ->
                runCatching {
                    val (targetUri, type) = attempt()
                    Intent(Intent.ACTION_VIEW).apply {
                        if (type != null) setDataAndType(targetUri, type) else data = targetUri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }.let(context::startActivity)
                }.onSuccess { return }
            }
        }

        fun viewImage(context: Context, uri: Uri) {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(viewIntent)
            } catch (_: Exception) {
            }
        }

        fun registerReceivers(context: Context, shortcutReceiver: BroadcastReceiver) {
            val filter = IntentFilter().apply {
                addAction(ACTION_NEXT_IMAGE)
                addAction(ACTION_NEXT_DUOTONE)
                addAction(ACTION_SET_BLUR_PERCENT)
                addAction(ACTION_ENABLE_BLUR)
                addAction(ACTION_REFRESH_FOLDERS)
                addAction(ACTION_ADD_TO_FAVORITES)
                addAction(ACTION_SET_IMAGE)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                shortcutReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
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