package dev.abdus.apps.buzei

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray

object WallpaperPreferences {

    private const val PREFS_NAME = "buzei_wallpaper_prefs"

    const val KEY_BLUR_AMOUNT = "wallpaper_blur_amount"
    const val KEY_DIM_AMOUNT = "wallpaper_dim_amount"
    const val KEY_DUOTONE_ENABLED = "wallpaper_duotone_enabled"
    const val KEY_DUOTONE_LIGHT = "wallpaper_duotone_light"
    const val KEY_DUOTONE_DARK = "wallpaper_duotone_dark"
    const val KEY_DUOTONE_ALWAYS_ON = "wallpaper_duotone_always_on"
    const val KEY_DUOTONE_PRESET_INDEX = "wallpaper_duotone_preset_index"
    const val KEY_IMAGE_FOLDER_URIS = "wallpaper_image_folder_uris"
    const val KEY_TRANSITION_INTERVAL = "wallpaper_transition_interval"
    const val KEY_TRANSITION_ENABLED = "wallpaper_transition_enabled"

    const val DEFAULT_BLUR_AMOUNT = 1f
    const val DEFAULT_DIM_AMOUNT = 1f
    const val DEFAULT_DUOTONE_ENABLED = false
    const val DEFAULT_DUOTONE_LIGHT = 0xFFFFD000.toInt()
    const val DEFAULT_DUOTONE_DARK = 0xFF696969.toInt()
    const val DEFAULT_DUOTONE_ALWAYS_ON = false
    const val DEFAULT_TRANSITION_INTERVAL_MILLIS = 5_000L

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBlurAmount(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_BLUR_AMOUNT, DEFAULT_BLUR_AMOUNT)

    fun getDimAmount(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_DIM_AMOUNT, DEFAULT_DIM_AMOUNT)

    fun setBlurAmount(prefs: SharedPreferences, amount: Float) {
        prefs.edit {
            putFloat(KEY_BLUR_AMOUNT, amount.coerceIn(0f, 1f))
        }
    }

    fun setDimAmount(prefs: SharedPreferences, amount: Float) {
        prefs.edit {
            putFloat(KEY_DIM_AMOUNT, amount.coerceIn(0f, 1f))
        }
    }

    fun isDuotoneEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_DUOTONE_ENABLED, DEFAULT_DUOTONE_ENABLED)

    fun setDuotoneEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_DUOTONE_ENABLED, enabled)
        }
    }

    fun getDuotoneLightColor(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_DUOTONE_LIGHT, DEFAULT_DUOTONE_LIGHT)

    fun setDuotoneLightColor(prefs: SharedPreferences, color: Int) {
        prefs.edit {
            putInt(KEY_DUOTONE_LIGHT, color)
        }
    }

    fun getDuotoneDarkColor(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_DUOTONE_DARK, DEFAULT_DUOTONE_DARK)

    fun setDuotoneDarkColor(prefs: SharedPreferences, color: Int) {
        prefs.edit {
            putInt(KEY_DUOTONE_DARK, color)
        }
    }

    fun isDuotoneAlwaysOn(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_DUOTONE_ALWAYS_ON, DEFAULT_DUOTONE_ALWAYS_ON)

    fun setDuotoneAlwaysOn(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_DUOTONE_ALWAYS_ON, enabled)
        }
    }

    fun getDuotonePresetIndex(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_DUOTONE_PRESET_INDEX, -1)

    fun setDuotonePresetIndex(prefs: SharedPreferences, index: Int) {
        prefs.edit {
            putInt(KEY_DUOTONE_PRESET_INDEX, index)
        }
    }

    fun getImageFolderUris(prefs: SharedPreferences): List<String> {
        val serialized = prefs.getString(KEY_IMAGE_FOLDER_URIS, null)
        if (serialized.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(serialized)
            (0 until array.length())
                .mapNotNull { array.optString(it).takeIf { it.isNotBlank() } }
        } catch (ignored: Exception) {
            emptyList()
        }
    }

    fun setImageFolderUris(prefs: SharedPreferences, uris: List<String>) {
        val cleaned = uris.mapNotNull { it.takeIf { it.isNotBlank() } }.distinct()
        val array = JSONArray()
        cleaned.forEach { array.put(it) }
        prefs.edit {
            if (cleaned.isNotEmpty()) {
                putString(KEY_IMAGE_FOLDER_URIS, array.toString())
            } else {
                remove(KEY_IMAGE_FOLDER_URIS)
            }
        }
    }

    fun getTransitionIntervalMillis(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_TRANSITION_INTERVAL, DEFAULT_TRANSITION_INTERVAL_MILLIS)

    fun setTransitionIntervalMillis(prefs: SharedPreferences, durationMillis: Long) {
        prefs.edit {
            putLong(KEY_TRANSITION_INTERVAL, durationMillis.coerceAtLeast(0L))
        }
    }

    fun isTransitionEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_TRANSITION_ENABLED, true)

    fun setTransitionEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_TRANSITION_ENABLED, enabled)
        }
    }

}
