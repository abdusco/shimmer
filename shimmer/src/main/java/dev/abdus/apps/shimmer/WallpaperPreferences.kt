package dev.abdus.apps.shimmer

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

class WallpaperPreferences(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_NAME = "shimmer_wallpaper_prefs"

        const val KEY_BLUR_AMOUNT = "wallpaper_blur_amount"
        const val KEY_DIM_AMOUNT = "wallpaper_dim_amount"
        const val KEY_DUOTONE_SETTINGS = "wallpaper_duotone_settings"
        const val KEY_IMAGE_FOLDER_URIS = "wallpaper_image_folder_uris"
        const val KEY_TRANSITION_INTERVAL = "wallpaper_transition_interval"
        const val KEY_TRANSITION_ENABLED = "wallpaper_transition_enabled"
        const val KEY_EFFECT_TRANSITION_DURATION = "wallpaper_effect_transition_duration"
        const val KEY_LAST_IMAGE_URI = "wallpaper_last_image_uri"

        const val DEFAULT_BLUR_AMOUNT = 0.5f
        const val DEFAULT_DIM_AMOUNT = 0.1f
        const val DEFAULT_DUOTONE_ENABLED = false
        const val DEFAULT_DUOTONE_LIGHT = 0xFFFFD000.toInt()
        const val DEFAULT_DUOTONE_DARK = 0xFF696969.toInt()
        const val DEFAULT_DUOTONE_ALWAYS_ON = false
        const val DEFAULT_TRANSITION_INTERVAL_MILLIS = 30_000L
        const val DEFAULT_EFFECT_TRANSITION_DURATION_MILLIS = 1500L

        fun create(context: Context): WallpaperPreferences {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return WallpaperPreferences(prefs)
        }
    }

    fun getBlurAmount(): Float =
        prefs.getFloat(KEY_BLUR_AMOUNT, DEFAULT_BLUR_AMOUNT)

    fun getDimAmount(): Float =
        prefs.getFloat(KEY_DIM_AMOUNT, DEFAULT_DIM_AMOUNT)

    fun setBlurAmount(amount: Float) {
        prefs.edit {
            putFloat(KEY_BLUR_AMOUNT, amount.coerceIn(0f, 1f))
        }
    }

    fun setDimAmount(amount: Float) {
        prefs.edit {
            putFloat(KEY_DIM_AMOUNT, amount.coerceIn(0f, 1f))
        }
    }

    /**
     * Get duotone settings as a single object.
     */
    fun getDuotoneSettings(): DuotoneSettings {
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
    fun setDuotoneSettings(settings: DuotoneSettings) {
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

    fun setDuotoneEnabled(enabled: Boolean) {
        val current = getDuotoneSettings()
        setDuotoneSettings(current.copy(enabled = enabled))
    }

    fun setDuotoneAlwaysOn(alwaysOn: Boolean) {
        val current = getDuotoneSettings()
        setDuotoneSettings(current.copy(alwaysOn = alwaysOn))
    }

    fun setDuotoneLightColor(color: Int) {
        val current = getDuotoneSettings()
        setDuotoneSettings(current.copy(lightColor = color))
    }

    fun setDuotoneDarkColor(color: Int) {
        val current = getDuotoneSettings()
        setDuotoneSettings(current.copy(darkColor = color))
    }

    fun getDuotonePresetIndex(): Int =
        getDuotoneSettings().presetIndex

    fun setDuotonePresetIndex(index: Int) {
        val current = getDuotoneSettings()
        setDuotoneSettings(current.copy(presetIndex = index))
    }

    fun applyDuotonePreset(
        lightColor: Int,
        darkColor: Int,
        enabled: Boolean = true,
        presetIndex: Int = -1
    ) {
        val current = getDuotoneSettings()
        setDuotoneSettings(
            current.copy(
                enabled = enabled,
                lightColor = lightColor,
                darkColor = darkColor,
                presetIndex = presetIndex
            )
        )
    }

    fun getImageFolderUris(): List<String> {
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

    fun setImageFolderUris(uris: List<String>) {
        val cleaned = uris.filter { it.isNotBlank() }.distinct()
        prefs.edit {
            if (cleaned.isNotEmpty()) {
                putString(KEY_IMAGE_FOLDER_URIS, Json.encodeToString(cleaned))
            } else {
                remove(KEY_IMAGE_FOLDER_URIS)
            }
        }
    }

    fun getTransitionIntervalMillis(): Long =
        prefs.getLong(KEY_TRANSITION_INTERVAL, DEFAULT_TRANSITION_INTERVAL_MILLIS)

    fun setTransitionIntervalMillis(durationMillis: Long) {
        prefs.edit {
            putLong(KEY_TRANSITION_INTERVAL, durationMillis.coerceAtLeast(0L))
        }
    }

    fun isTransitionEnabled(): Boolean =
        prefs.getBoolean(KEY_TRANSITION_ENABLED, true)

    fun setTransitionEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_TRANSITION_ENABLED, enabled)
        }
    }

    fun getEffectTransitionDurationMillis(): Long =
        prefs.getLong(KEY_EFFECT_TRANSITION_DURATION, DEFAULT_EFFECT_TRANSITION_DURATION_MILLIS)

    fun setEffectTransitionDurationMillis(durationMillis: Long) {
        prefs.edit {
            putLong(KEY_EFFECT_TRANSITION_DURATION, durationMillis.coerceIn(0L, 3000L))
        }
    }

    fun getLastImageUri(): String? =
        prefs.getString(KEY_LAST_IMAGE_URI, null)

    fun setLastImageUri(uri: String?) {
        prefs.edit {
            if (uri != null) {
                putString(KEY_LAST_IMAGE_URI, uri)
            } else {
                remove(KEY_LAST_IMAGE_URI)
            }
        }
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
