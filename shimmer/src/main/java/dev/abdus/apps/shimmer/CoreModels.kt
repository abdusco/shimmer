package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.graphics.Color
import android.view.animation.DecelerateInterpolator
import kotlinx.serialization.Serializable

/**
 * Represents the target state of the wallpaper.
 * All user-configurable properties should be here.
 *
 * @property imageSet The image set with original and blurred versions
 * @property blurPercent Normalized blur amount (0.0 = no blur, 1.0 = full blur)
 * @property dimAmount Dim overlay amount (0.0 = no dimming, 1.0 = full dimming)
 * @property duotone Duotone color effect configuration
 * @property duotoneAlwaysOn Whether duotone is always visible (true) or only when blurred (false)
 * @property parallaxOffset Parallax scroll position (0.0 = left, 0.5 = center, 1.0 = right)
 * @property grain Film grain overlay settings
 */
data class RenderState(
    val imageSet: ImageSet,
    val blurPercent: Float,
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
@Serializable
data class GrainSettings(
    val enabled: Boolean = false,
    val amount: Float = 0.18f,
    val scale: Float = 0.5f,
)


data class TouchPoint(
    val id: Int,
    var x: Float,
    var y: Float,
    var radius: Float = 0f,
    var intensity: Float = 1f,
    var isReleased: Boolean = false,
    val radiusAnimator: TickingFloatAnimator = TickingFloatAnimator(4000, DecelerateInterpolator()),
    val fadeAnimator: TickingFloatAnimator = TickingFloatAnimator(500, DecelerateInterpolator())
)


/**
 * Represents a processed image and its blur keyframes.
 */
data class ImageSet(
    val original: Bitmap,
    val blurred: List<Bitmap> = emptyList(),
    val blurRadii: List<Float> = emptyList(),
    val id: String = "",
    val width: Int = original.width,
    val height: Int = original.height,
)

enum class DuotoneBlendMode {
    NORMAL, SOFT_LIGHT, SCREEN
}

data class Duotone(
    val lightColor: Int = Color.WHITE,
    val darkColor: Int = Color.BLACK,
    val opacity: Float = 0f,
    val blendMode: DuotoneBlendMode = DuotoneBlendMode.NORMAL,
)

enum class TouchAction {
    DOWN, MOVE, UP
}

data class TouchData(
    val id: Int,
    val x: Float,
    val y: Float,
    val action: TouchAction,
)


data class PictureHandles(
    val program: Int,
    val attribPosition: Int,
    val attribTexCoords: Int,
    val uniformMvpMatrix: Int,
    val uniformTexture: Int,
    val uniformAlpha: Int,
    val uniformDuotoneLight: Int,
    val uniformDuotoneDark: Int,
    val uniformDuotoneOpacity: Int,
    val uniformDuotoneBlendMode: Int,
    val uniformDimAmount: Int,
    val uniformGrainAmount: Int,
    val uniformGrainCount: Int,
    val uniformTouchPointCount: Int,
    val uniformTouchPoints: Int,
    val uniformTouchIntensities: Int,
    val uniformScreenSize: Int,
)
