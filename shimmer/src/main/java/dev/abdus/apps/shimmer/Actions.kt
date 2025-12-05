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
        const val EXTRA_BLUR_PERCENT = "blur_percent"

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

        fun registerReceivers(context: Context, shortcutReceiver: BroadcastReceiver) {
            val filter = IntentFilter().apply {
                addAction(ACTION_NEXT_IMAGE)
                addAction(ACTION_NEXT_DUOTONE)
                addAction(ACTION_SET_BLUR_PERCENT)
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

