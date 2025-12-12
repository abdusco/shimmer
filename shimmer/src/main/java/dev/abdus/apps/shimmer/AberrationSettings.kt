package dev.abdus.apps.shimmer

/**
 * Chromatic aberration effect configuration.
 * @property enabled Whether the aberration effect is enabled.
 * @property maxRadius Max radius of aberration in normalized screen space (0.0 to 1.0).
 * @property fadeDurationMillis How long the aberration lasts in milliseconds.
 */
data class AberrationSettings(
    val enabled: Boolean = true,
    val maxRadius: Float = 0.5f,
    val fadeDurationMillis: Long = 1000L,
)
