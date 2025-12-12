package dev.abdus.apps.shimmer

/**
 * Represents the target state of the wallpaper.
 * All user-configurable properties should be here.
 *
 * @property imageSet The image set with original and blurred versions
 * @property blurAmount Normalized blur amount (0.0 = no blur, 1.0 = full blur)
 * @property dimAmount Dim overlay amount (0.0 = no dimming, 1.0 = full dimming)
 * @property duotone Duotone color effect configuration
 * @property duotoneAlwaysOn Whether duotone is always visible (true) or only when blurred (false)
 * @property parallaxOffset Parallax scroll position (0.0 = left, 0.5 = center, 1.0 = right)
 * @property grain Film grain overlay settings
 */
data class RenderState(
    val imageSet: ImageSet,
    val blurAmount: Float,
    val dimAmount: Float,
    val duotone: Duotone,
    val duotoneAlwaysOn: Boolean,
    val parallaxOffset: Float,
    val grain: GrainSettings,
)

/**
 * Film grain overlay settings.
 * @property enabled Whether grain is applied
 * @property amount Strength of the grain (0.0 = off, 1.0 = strong)
 * @property scale Normalized grain size slider (0.0 = fine, 1.0 = coarse)
 */
data class GrainSettings(
    val enabled: Boolean = false,
    val amount: Float = 0.18f,
    val scale: Float = 0.5f,
)
