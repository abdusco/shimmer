package dev.abdus.apps.shimmer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
    val enabled: Boolean = true,
)

class WallpaperPreferences(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_NAME = "shimmer_wallpaper_prefs"

        const val KEY_BLUR_AMOUNT = "wallpaper_blur_amount"
        const val KEY_DIM_AMOUNT = "wallpaper_dim_amount"
        const val KEY_DUOTONE_SETTINGS = "wallpaper_duotone_settings"
        const val KEY_IMAGE_CYCLE_INTERVAL = "wallpaper_image_cycle_interval"
        const val KEY_IMAGE_CYCLE_ENABLED = "wallpaper_image_cycle_enabled"
        const val KEY_EFFECT_TRANSITION_DURATION = "wallpaper_effect_transition_duration"
        const val KEY_BLUR_ON_SCREEN_LOCK = "wallpaper_blur_on_screen_lock"
        const val KEY_CYCLE_IMAGE_ON_UNLOCK = "wallpaper_image_cycle_on_unlock"
        const val KEY_BLUR_TIMEOUT_ENABLED = "wallpaper_blur_timeout_enabled"
        const val KEY_BLUR_TIMEOUT_MILLIS = "wallpaper_blur_timeout_millis"
        const val KEY_UNBLUR_ON_UNLOCK = "wallpaper_unblur_on_unlock"
        const val KEY_LAST_SELECTED_TAB = "settings_last_selected_tab"
        const val KEY_GRAIN_SETTINGS = "wallpaper_grain_settings"
        const val KEY_CHROMATIC_ABERRATION_SETTINGS = "wallpaper_chromatic_aberration_settings"
        const val KEY_FAVORITES_FOLDER_URI = "favorites_folder_uri"
        const val KEY_SHARED_FOLDER_URI = "shared_folder_uri"
        const val KEY_GESTURE_TRIPLE_TAP_ACTION = "gesture_triple_tap_action"
        const val KEY_GESTURE_TWO_FINGER_DOUBLE_TAP_ACTION = "gesture_two_finger_double_tap_action"
        const val KEY_GESTURE_THREE_FINGER_DOUBLE_TAP_ACTION = "gesture_three_finger_double_tap_action"

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
        val DEFAULT_TRIPLE_TAP_ACTION = GestureAction.TOGGLE_BLUR
        val DEFAULT_TWO_FINGER_DOUBLE_TAP_ACTION = GestureAction.NEXT_IMAGE
        val DEFAULT_THREE_FINGER_DOUBLE_TAP_ACTION = GestureAction.NONE

        fun create(context: Context): WallpaperPreferences {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return WallpaperPreferences(prefs)
        }
    }

    fun getFavoritesFolderUri(): Uri? {
        val value = prefs.getString(KEY_FAVORITES_FOLDER_URI, null) ?: return null
        return value.toUri()
    }

    fun setFavoritesFolderUri(uri: Uri?) {
        prefs.edit {
            if (uri == null) {
                remove(KEY_FAVORITES_FOLDER_URI)
            } else {
                putString(KEY_FAVORITES_FOLDER_URI, uri.toString())
            }
        }
    }

    fun getSharedFolderUri(): Uri? {
        val value = prefs.getString(KEY_SHARED_FOLDER_URI, null) ?: return null
        return value.toUri()
    }

    fun setSharedFolderUri(uri: Uri?) {
        prefs.edit {
            if (uri == null) {
                remove(KEY_SHARED_FOLDER_URI)
            } else {
                putString(KEY_SHARED_FOLDER_URI, uri.toString())
            }
        }
    }

    fun favoritesFolderUriFlow(): Flow<Uri?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_FAVORITES_FOLDER_URI) trySend(getFavoritesFolderUri())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getFavoritesFolderUri())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun sharedFolderUriFlow(): Flow<Uri?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SHARED_FOLDER_URI) trySend(getSharedFolderUri())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getSharedFolderUri())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
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
        scale = DEFAULT_GRAIN_SCALE,
    )

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
        fadeDurationMillis = DEFAULT_CHROMATIC_ABERRATION_FADE_DURATION,
    )

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
        blendMode = DuotoneBlendMode.NORMAL,
    )

    fun getDuotonePresetIndex(): Int =
        getDuotoneSettings().presetIndex

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
                presetIndex = presetIndex,
            ),
        )
    }

    fun getImageCycleIntervalMillis(): Long =
        prefs.getLong(KEY_IMAGE_CYCLE_INTERVAL, DEFAULT_TRANSITION_INTERVAL_MILLIS)

    fun setImageCycleIntervalMillis(durationMillis: Long) {
        prefs.edit {
            putLong(KEY_IMAGE_CYCLE_INTERVAL, durationMillis.coerceAtLeast(0L))
        }
    }

    fun isImageCycleEnabled(): Boolean =
        prefs.getBoolean(KEY_IMAGE_CYCLE_ENABLED, true)

    fun setImageCycleEnabled(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_IMAGE_CYCLE_ENABLED, enabled)
        }
    }

    fun getEffectTransitionDurationMillis(): Long =
        prefs.getLong(KEY_EFFECT_TRANSITION_DURATION, DEFAULT_EFFECT_TRANSITION_DURATION_MILLIS)

    fun setEffectTransitionDurationMillis(durationMillis: Long) {
        prefs.edit {
            putLong(KEY_EFFECT_TRANSITION_DURATION, durationMillis.coerceIn(0L, 3000L))
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

    fun isCycleImageOnUnlockEnabled(): Boolean =
        prefs.getBoolean(KEY_CYCLE_IMAGE_ON_UNLOCK, false)

    fun setCycleImageOnUnlock(enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_CYCLE_IMAGE_ON_UNLOCK, enabled)
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

    fun getGestureAction(event: TapGesture): GestureAction {
        val (key, default) = when (event) {
            TapGesture.TRIPLE_TAP -> KEY_GESTURE_TRIPLE_TAP_ACTION to DEFAULT_TRIPLE_TAP_ACTION
            TapGesture.TWO_FINGER_DOUBLE_TAP -> KEY_GESTURE_TWO_FINGER_DOUBLE_TAP_ACTION to DEFAULT_TWO_FINGER_DOUBLE_TAP_ACTION
            TapGesture.THREE_FINGER_DOUBLE_TAP -> KEY_GESTURE_THREE_FINGER_DOUBLE_TAP_ACTION to DEFAULT_THREE_FINGER_DOUBLE_TAP_ACTION
            TapGesture.NONE -> return GestureAction.NONE
        }
        return getGestureActionByKey(key, default)
    }

    fun setGestureAction(event: TapGesture, action: GestureAction) {
        val key = when (event) {
            TapGesture.TRIPLE_TAP -> KEY_GESTURE_TRIPLE_TAP_ACTION
            TapGesture.TWO_FINGER_DOUBLE_TAP -> KEY_GESTURE_TWO_FINGER_DOUBLE_TAP_ACTION
            TapGesture.THREE_FINGER_DOUBLE_TAP -> KEY_GESTURE_THREE_FINGER_DOUBLE_TAP_ACTION
            TapGesture.NONE -> return
        }
        prefs.edit {
            putString(key, action.name)
        }
    }

    private fun getGestureActionByKey(key: String, default: GestureAction): GestureAction {
        val value = prefs.getString(key, null) ?: return default
        return try {
            GestureAction.valueOf(value)
        } catch (_: IllegalArgumentException) {
            default
        }
    }
}
