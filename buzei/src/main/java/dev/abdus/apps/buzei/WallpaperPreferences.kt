package dev.abdus.apps.buzei

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DuotoneSettings(
    val enabled: Boolean,
    val alwaysOn: Boolean,
    val lightColor: Int,
    val darkColor: Int,
    val presetIndex: Int
)

object WallpaperPreferences {

    private const val PREFS_NAME = "buzei_wallpaper_prefs"

    const val KEY_BLUR_AMOUNT = "wallpaper_blur_amount"
    const val KEY_DIM_AMOUNT = "wallpaper_dim_amount"
    const val KEY_DUOTONE_SETTINGS = "wallpaper_duotone_settings"
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

    /**
     * Get duotone settings as a single object.
     */
    fun getDuotoneSettings(prefs: SharedPreferences): DuotoneSettings {
        val json = prefs.getString(KEY_DUOTONE_SETTINGS, null)
        return if (json != null) {
            try {
                Json.decodeFromString<DuotoneSettings>(json)
            } catch (e: Exception) {
                getDefaultDuotoneSettings()
            }
        } else {
            getDefaultDuotoneSettings()
        }
    }

    /**
     * Set duotone settings as a single atomic operation.
     * This triggers only ONE SharedPreferences change notification.
     */
    fun setDuotoneSettings(prefs: SharedPreferences, settings: DuotoneSettings) {
        val json = Json.encodeToString(settings)
        prefs.edit {
            putString(KEY_DUOTONE_SETTINGS, json)
        }
    }

    private fun getDefaultDuotoneSettings() = DuotoneSettings(
        enabled = DEFAULT_DUOTONE_ENABLED,
        alwaysOn = DEFAULT_DUOTONE_ALWAYS_ON,
        lightColor = DEFAULT_DUOTONE_LIGHT,
        darkColor = DEFAULT_DUOTONE_DARK,
        presetIndex = -1
    )

    // Convenience methods for backward compatibility
    fun isDuotoneEnabled(prefs: SharedPreferences): Boolean =
        getDuotoneSettings(prefs).enabled

    fun setDuotoneEnabled(prefs: SharedPreferences, enabled: Boolean) {
        val current = getDuotoneSettings(prefs)
        setDuotoneSettings(prefs, current.copy(enabled = enabled))
    }

    fun isDuotoneAlwaysOn(prefs: SharedPreferences): Boolean =
        getDuotoneSettings(prefs).alwaysOn

    fun setDuotoneAlwaysOn(prefs: SharedPreferences, alwaysOn: Boolean) {
        val current = getDuotoneSettings(prefs)
        setDuotoneSettings(prefs, current.copy(alwaysOn = alwaysOn))
    }

    fun getDuotoneLightColor(prefs: SharedPreferences): Int =
        getDuotoneSettings(prefs).lightColor

    fun setDuotoneLightColor(prefs: SharedPreferences, color: Int) {
        val current = getDuotoneSettings(prefs)
        setDuotoneSettings(prefs, current.copy(lightColor = color))
    }

    fun getDuotoneDarkColor(prefs: SharedPreferences): Int =
        getDuotoneSettings(prefs).darkColor

    fun setDuotoneDarkColor(prefs: SharedPreferences, color: Int) {
        val current = getDuotoneSettings(prefs)
        setDuotoneSettings(prefs, current.copy(darkColor = color))
    }

    fun getDuotonePresetIndex(prefs: SharedPreferences): Int =
        getDuotoneSettings(prefs).presetIndex

    fun setDuotonePresetIndex(prefs: SharedPreferences, index: Int) {
        val current = getDuotoneSettings(prefs)
        setDuotoneSettings(prefs, current.copy(presetIndex = index))
    }

    /**
     * Atomically apply a complete duotone preset.
     */
    fun applyDuotonePreset(
        prefs: SharedPreferences,
        lightColor: Int,
        darkColor: Int,
        enabled: Boolean = true,
        presetIndex: Int = -1
    ) {
        val current = getDuotoneSettings(prefs)
        setDuotoneSettings(
            prefs,
            current.copy(
                enabled = enabled,
                lightColor = lightColor,
                darkColor = darkColor,
                presetIndex = presetIndex
            )
        )
    }

    fun getImageFolderUris(prefs: SharedPreferences): List<String> {
        val serialized = prefs.getString(KEY_IMAGE_FOLDER_URIS, null)
        if (serialized.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            Json.decodeFromString<List<String>>(serialized)
                .filter { it.isNotBlank() }
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    fun setImageFolderUris(prefs: SharedPreferences, uris: List<String>) {
        val cleaned = uris.filter { it.isNotBlank() }.distinct()
        prefs.edit {
            if (cleaned.isNotEmpty()) {
                putString(KEY_IMAGE_FOLDER_URIS, Json.encodeToString(cleaned))
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
