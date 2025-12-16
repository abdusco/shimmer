package dev.abdus.apps.shimmer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

object DuotoneBlendModeSerializer : KSerializer<DuotoneBlendMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DuotoneBlendMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DuotoneBlendMode) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): DuotoneBlendMode {
        val string = decoder.decodeString()
        return try {
            DuotoneBlendMode.valueOf(string)
        } catch (e: IllegalArgumentException) {
            DuotoneBlendMode.NORMAL
        }
    }
}

@Serializable
data class DuotoneSettings(
    val enabled: Boolean,
    val alwaysOn: Boolean,
    val lightColor: Int,
    val darkColor: Int,
    val presetIndex: Int,
    @Serializable(with = DuotoneBlendModeSerializer::class)
    val blendMode: DuotoneBlendMode = DuotoneBlendMode.NORMAL,
)

@Serializable
data class ChromaticAberrationSettings(
    val enabled: Boolean,
    val intensity: Float,
    val fadeDurationMillis: Long,
)

@Serializable
data class ImageFolder(
    val uri: String,
    val thumbnailUri: String? = null,
    val imageCount: Int? = null,
)

class WallpaperPreferences(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_NAME = "shimmer_wallpaper_prefs"

        const val KEY_BLUR_AMOUNT = "wallpaper_blur_amount"
        const val KEY_DIM_AMOUNT = "wallpaper_dim_amount"
        const val KEY_DUOTONE_SETTINGS = "wallpaper_duotone_settings"
        const val KEY_IMAGE_FOLDERS = "wallpaper_image_folders"
        const val KEY_TRANSITION_INTERVAL = "wallpaper_transition_interval"
        const val KEY_TRANSITION_ENABLED = "wallpaper_transition_enabled"
        const val KEY_EFFECT_TRANSITION_DURATION = "wallpaper_effect_transition_duration"
        const val KEY_LAST_IMAGE_URI = "wallpaper_last_image_uri"
        const val KEY_BLUR_ON_SCREEN_LOCK = "wallpaper_blur_on_screen_lock"
        const val KEY_CHANGE_IMAGE_ON_UNLOCK = "wallpaper_change_image_on_unlock"
        const val KEY_BLUR_TIMEOUT_ENABLED = "wallpaper_blur_timeout_enabled"
        const val KEY_BLUR_TIMEOUT_MILLIS = "wallpaper_blur_timeout_millis"
        const val KEY_UNBLUR_ON_UNLOCK = "wallpaper_unblur_on_unlock"
        const val KEY_LAST_SELECTED_TAB = "settings_last_selected_tab"
        const val KEY_GRAIN_SETTINGS = "wallpaper_grain_settings"
        const val KEY_CHROMATIC_ABERRATION_SETTINGS = "wallpaper_chromatic_aberration_settings"

        const val DEFAULT_BLUR_AMOUNT = 0.5f
        const val DEFAULT_DIM_AMOUNT = 0.1f
        const val DEFAULT_DUOTONE_ENABLED = false
        const val DEFAULT_DUOTONE_LIGHT = 0xFFFFD000.toInt()
        const val DEFAULT_DUOTONE_DARK = 0xFF696969.toInt()
        const val DEFAULT_DUOTONE_ALWAYS_ON = false
        const val DEFAULT_TRANSITION_INTERVAL_MILLIS = 30_000L
        const val DEFAULT_EFFECT_TRANSITION_DURATION_MILLIS = 1500L
        const val DEFAULT_BLUR_TIMEOUT_MILLIS = 30_000L
        const val MIN_BLUR_TIMEOUT_MILLIS = 5_000L
        const val MAX_BLUR_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_GRAIN_ENABLED = false
        const val DEFAULT_GRAIN_AMOUNT = 0.18f
        const val DEFAULT_GRAIN_SCALE = 0.5f
        const val DEFAULT_CHROMATIC_ABERRATION_ENABLED = true
        const val DEFAULT_CHROMATIC_ABERRATION_INTENSITY = 0.5f
        const val DEFAULT_CHROMATIC_ABERRATION_FADE_DURATION = 500L

        fun create(context: Context): WallpaperPreferences {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return WallpaperPreferences(prefs)
        }
    }

    fun getBlurAmount(): Float =
        prefs.getFloat(KEY_BLUR_AMOUNT, DEFAULT_BLUR_AMOUNT)

    fun getDimAmount(): Float =
        prefs.getFloat(KEY_DIM_AMOUNT, DEFAULT_DIM_AMOUNT)

    /**
     * Get grain settings as a single object.
     */
    fun getGrainSettings(): GrainSettings {
        val json = prefs.getString(KEY_GRAIN_SETTINGS, null)
        return if (json != null) {
            try {
                Json.decodeFromString<GrainSettings>(json)
            } catch (e: Exception) {
                getDefaultGrainSettings()
            }
        } else {
            getDefaultGrainSettings()
        }
    }

    /**
     * Set grain settings as a single atomic operation.
     * This triggers only ONE SharedPreferences change notification.
     */
    fun setGrainSettings(settings: GrainSettings) {
        val json = Json.encodeToString(settings)
        prefs.edit {
            putString(KEY_GRAIN_SETTINGS, json)
        }
    }

    private fun getDefaultGrainSettings() = GrainSettings(
        enabled = DEFAULT_GRAIN_ENABLED,
        amount = DEFAULT_GRAIN_AMOUNT,
        scale = DEFAULT_GRAIN_SCALE
    )

    fun setGrainEnabled(enabled: Boolean) {
        val current = getGrainSettings()
        setGrainSettings(current.copy(enabled = enabled))
    }

    fun setGrainAmount(amount: Float) {
        val current = getGrainSettings()
        setGrainSettings(current.copy(amount = amount.coerceIn(0f, 1f)))
    }

    fun setGrainScale(scale: Float) {
        val current = getGrainSettings()
        setGrainSettings(current.copy(scale = scale.coerceIn(0f, 1f)))
    }

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
     * Get chromatic aberration settings as a single object.
     */
    fun getChromaticAberrationSettings(): ChromaticAberrationSettings {
        val json = prefs.getString(KEY_CHROMATIC_ABERRATION_SETTINGS, null)
        return if (json != null) {
            try {
                Json.decodeFromString<ChromaticAberrationSettings>(json)
            } catch (e: Exception) {
                getDefaultChromaticAberrationSettings()
            }
        } else {
            getDefaultChromaticAberrationSettings()
        }
    }

    /**
     * Set chromatic aberration settings as a single atomic operation.
     * This triggers only ONE SharedPreferences change notification.
     */
    fun setChromaticAberrationSettings(settings: ChromaticAberrationSettings) {
        val json = Json.encodeToString(settings)
        prefs.edit {
            putString(KEY_CHROMATIC_ABERRATION_SETTINGS, json)
        }
    }

    private fun getDefaultChromaticAberrationSettings() = ChromaticAberrationSettings(
        enabled = DEFAULT_CHROMATIC_ABERRATION_ENABLED,
        intensity = DEFAULT_CHROMATIC_ABERRATION_INTENSITY,
        fadeDurationMillis = DEFAULT_CHROMATIC_ABERRATION_FADE_DURATION
    )

    fun setChromaticAberrationEnabled(enabled: Boolean) {
        val current = getChromaticAberrationSettings()
        setChromaticAberrationSettings(current.copy(enabled = enabled))
    }

    fun setChromaticAberrationIntensity(intensity: Float) {
        val current = getChromaticAberrationSettings()
        setChromaticAberrationSettings(current.copy(intensity = intensity.coerceIn(0f, 1f)))
    }

    fun setChromaticAberrationFadeDuration(durationMillis: Long) {
        val current = getChromaticAberrationSettings()
        setChromaticAberrationSettings(current.copy(fadeDurationMillis = durationMillis.coerceAtLeast(0)))
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
        presetIndex = -1,
        blendMode = DuotoneBlendMode.NORMAL
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

    fun setDuotoneBlendMode(blendMode: DuotoneBlendMode) {
        val current = getDuotoneSettings()
        setDuotoneSettings(current.copy(blendMode = blendMode))
    }

    fun applyDuotonePreset(
        lightColor: Int,
        darkColor: Int,
        enabled: Boolean = true,
        presetIndex: Int = -1,
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

    fun getImageFolders(): List<ImageFolder> {
        val serialized = prefs.getString(KEY_IMAGE_FOLDERS, null)
        if (serialized.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            Json.decodeFromString<List<ImageFolder>>(serialized)
                .filter { it.uri.isNotBlank() }
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    fun setImageFolders(folders: List<ImageFolder>) {
        val cleaned = folders
            .filter { it.uri.isNotBlank() }
            .distinctBy { it.uri }
        prefs.edit {
            if (cleaned.isNotEmpty()) {
                putString(KEY_IMAGE_FOLDERS, Json.encodeToString(cleaned))
            } else {
                remove(KEY_IMAGE_FOLDERS)
                // Only clear last image URI when all folders are removed
                setLastImageUri(null)
            }
            // Don't clear lastImageUri when folders are updated - let the wallpaper service
            // validate if the current image is still valid in the new folder set
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

    fun getLastImageUri(): Uri? =
        prefs.getString(KEY_LAST_IMAGE_URI, null)?.toUri()

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

    fun isBlurOnScreenLockEnabled(): Boolean =
        prefs.getBoolean(KEY_BLUR_ON_SCREEN_LOCK, false)

    fun setBlurOnScreenLock(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_BLUR_ON_SCREEN_LOCK, enabled)
        }
    }

    fun isChangeImageOnUnlockEnabled(): Boolean =
        prefs.getBoolean(KEY_CHANGE_IMAGE_ON_UNLOCK, false)

    fun setChangeImageOnUnlock(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_CHANGE_IMAGE_ON_UNLOCK, enabled)
        }
    }

    fun isBlurTimeoutEnabled(): Boolean =
        prefs.getBoolean(KEY_BLUR_TIMEOUT_ENABLED, false)

    fun setBlurTimeoutEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_BLUR_TIMEOUT_ENABLED, enabled)
        }
    }

    fun isUnblurOnUnlockEnabled(): Boolean =
        prefs.getBoolean(KEY_UNBLUR_ON_UNLOCK, false)

    fun setUnblurOnUnlockEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_UNBLUR_ON_UNLOCK, enabled)
        }
    }

    fun getBlurTimeoutMillis(): Long =
        prefs.getLong(KEY_BLUR_TIMEOUT_MILLIS, DEFAULT_BLUR_TIMEOUT_MILLIS)
            .coerceIn(MIN_BLUR_TIMEOUT_MILLIS, MAX_BLUR_TIMEOUT_MILLIS)

    fun setBlurTimeoutMillis(durationMillis: Long) {
        val clamped = durationMillis.coerceIn(MIN_BLUR_TIMEOUT_MILLIS, MAX_BLUR_TIMEOUT_MILLIS)
        prefs.edit {
            putLong(KEY_BLUR_TIMEOUT_MILLIS, clamped)
        }
    }

    fun getLastSelectedTab(): Int =
        prefs.getInt(KEY_LAST_SELECTED_TAB, 0)

    fun setLastSelectedTab(tabIndex: Int) {
        prefs.edit {
            putInt(KEY_LAST_SELECTED_TAB, tabIndex)
        }
    }
}

