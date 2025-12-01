package dev.abdus.apps.buzei

import android.view.animation.DecelerateInterpolator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

data class RendererImagePayload(
    val original: Bitmap,
    val blurred: Bitmap? = null,
    val tintedOriginal: Bitmap? = null,
    val settingsSnapshot: RendererImageSettings? = null,
    val sourceUri: Uri? = null
)

class BuzeiRenderer(private val context: Context, private val callbacks: Callbacks) :
    GLSurfaceView.Renderer {

    interface Callbacks {
        fun requestRender()
    }

    companion object {
        private const val BLUR_ANIMATION_DURATION = 1200
        private const val IMAGE_FADE_DURATION = 1200
    }

    private var originalBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var pendingImage: RendererImagePayload? = null
    private var surfaceCreated = false
    private var surfaceAspect = 1f
    private var bitmapAspect = 1f
    private var previousBitmapAspect: Float? = null
    private var normalOffsetX = 0.5f

    private val mvpMatrix = FloatArray(16)
    private val previousMvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val previousProjectionMatrix = FloatArray(16)
    private val tempProjectionMatrix = FloatArray(16)
    private val viewModelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    private var pictureSet: GLPictureSet? = null
    private var previousPictureSet: GLPictureSet? = null
    private lateinit var colorOverlay: GLColorOverlay
    private val blurKeyframes = 1
    private val blurAnimator = TickingFloatAnimator(BLUR_ANIMATION_DURATION, DecelerateInterpolator())
    private val imageFadeAnimator = TickingFloatAnimator(
        IMAGE_FADE_DURATION,
        DecelerateInterpolator()
    ).apply {
        snapTo(1f)
    }
    private var blurRadiusFraction = WallpaperPreferences.DEFAULT_BLUR_AMOUNT
    private var userDimAmount = WallpaperPreferences.DEFAULT_DIM_AMOUNT
    private var duotoneEnabled = WallpaperPreferences.DEFAULT_DUOTONE_ENABLED
    private var duotoneAlwaysOn = WallpaperPreferences.DEFAULT_DUOTONE_ALWAYS_ON
    private var duotoneLightColor = WallpaperPreferences.DEFAULT_DUOTONE_LIGHT
    private var duotoneDarkColor = WallpaperPreferences.DEFAULT_DUOTONE_DARK
    private var tintedOriginalBitmap: Bitmap? = null
    private var isBlurred = false

    fun setImage(bitmap: Bitmap) {
        setImage(RendererImagePayload(bitmap))
    }

    fun setImage(image: RendererImagePayload) {
        if (!surfaceCreated) {
            pendingImage = image
            return
        }
        val bitmap = image.original
        originalBitmap = bitmap
        if (pictureSet != null) {
            previousBitmapAspect = bitmapAspect
        }
        bitmapAspect = if (bitmap.height == 0) 1f else bitmap.width.toFloat() / bitmap.height
        val snapshot = image.settingsSnapshot
        val canReusePrecomputed =
            snapshot?.matches(blurRadiusFraction, duotoneEnabled, duotoneAlwaysOn, duotoneLightColor, duotoneDarkColor) == true
        if (canReusePrecomputed && image.blurred != null) {
            blurredBitmap = image.blurred
            tintedOriginalBitmap = if (duotoneEnabled && duotoneAlwaysOn) {
                image.tintedOriginal
            } else {
                null
            }
        } else {
            rebuildBlurredBitmap()
        }
        updatePictureSet()
        recomputeProjectionMatrix()
    }

    fun setBlurRadiusFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (blurRadiusFraction == clamped) {
            return
        }
        blurRadiusFraction = clamped
        rebuildBlurredBitmap()
        updatePictureSet(preserveAspect = true)
        if (isBlurred) {
            blurAnimator.snapTo(blurKeyframes.toFloat())
        }
        callbacks.requestRender()
    }

    fun toggleBlur() {
        isBlurred = !isBlurred
        val target = if (isBlurred) blurKeyframes.toFloat() else 0f
        blurAnimator.start(startValue = blurAnimator.currentValue, endValue = target)
        callbacks.requestRender()
    }

    fun setUserDimAmount(amount: Float) {
        userDimAmount = amount.coerceIn(0f, 1f)
        callbacks.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        surfaceCreated = true
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 1f, 0f)

        GLColorOverlay.initGl()
        GLPicture.initGl()
        colorOverlay = GLColorOverlay()
        recomputeProjectionMatrix()

        val hadPendingImage = pendingImage != null
        pendingImage?.let {
            setImage(it)
            pendingImage = null
        }
        if (!hadPendingImage && originalBitmap != null) {
            updatePictureSet()
        }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceAspect = if (height == 0) 1f else width.toFloat() / height
        recomputeProjectionMatrix()
    }

    override fun onDrawFrame(gl: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val currentPictureSet = pictureSet ?: return
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(previousMvpMatrix, 0, previousProjectionMatrix, 0, viewModelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewModelMatrix, 0)

        val stillAnimating = blurAnimator.tick()
        val imageStillAnimating = imageFadeAnimator.tick()
        val imageAlpha = imageFadeAnimator.currentValue.coerceIn(0f, 1f)
        previousPictureSet?.drawFrame(previousMvpMatrix, blurAnimator.currentValue, 1f - imageAlpha)
        currentPictureSet.drawFrame(mvpMatrix, blurAnimator.currentValue, imageAlpha)

        val blurProgress = if (blurKeyframes > 0) {
            (blurAnimator.currentValue / blurKeyframes).coerceIn(0f, 1f)
        } else {
            0f
        }
        val overlayAlpha = (userDimAmount * blurProgress * 255).toInt().coerceIn(0, 255)
        colorOverlay.color = Color.argb(overlayAlpha, 0, 0, 0)
        Matrix.setIdentityM(modelMatrix, 0)
        colorOverlay.draw(modelMatrix)

        if (!imageStillAnimating && imageAlpha >= 1f) {
            previousPictureSet?.destroyPictures()
            previousPictureSet = null
            previousBitmapAspect = null
        }

        if (stillAnimating || imageStillAnimating) {
            callbacks.requestRender()
        }
    }

    private fun updatePictureSet(preserveAspect: Boolean = false) {
        val baseOriginal = originalBitmap ?: return
        val normal = tintedOriginalBitmap ?: baseOriginal
        val blurred = blurredBitmap ?: normal

        previousPictureSet?.destroyPictures()
        previousPictureSet = pictureSet
        pictureSet = GLPictureSet(blurKeyframes).apply {
            load(listOf(normal, blurred))
        }
        imageFadeAnimator.start(startValue = 0f, endValue = 1f)

        // When preserving aspect (color changes only), ensure we use the same projection matrix
        if (preserveAspect) {
            previousBitmapAspect = bitmapAspect
            recomputeProjectionMatrix()
        }

        callbacks.requestRender()
    }

    private fun rebuildBlurredBitmap() {
        val source = originalBitmap ?: return
        val processed = processImageForRenderer(
            source,
            blurRadiusFraction,
            currentSettingsSnapshot()
        )
        blurredBitmap = processed.blurred
        tintedOriginalBitmap = processed.tintedOriginal
    }

    fun setDuotoneEnabled(enabled: Boolean) {
        if (duotoneEnabled == enabled) {
            return
        }
        duotoneEnabled = enabled
        rebuildBlurredBitmap()
        updatePictureSet(preserveAspect = true)
    }

    fun setDuotoneAlwaysOn(enabled: Boolean) {
        if (duotoneAlwaysOn == enabled) {
            return
        }
        duotoneAlwaysOn = enabled
        rebuildBlurredBitmap()
        updatePictureSet(preserveAspect = true)
    }

    fun setDuotoneColors(lightColor: Int, darkColor: Int) {
        if (duotoneLightColor == lightColor && duotoneDarkColor == darkColor) {
            return
        }
        duotoneLightColor = lightColor
        duotoneDarkColor = darkColor
        rebuildBlurredBitmap()
        updatePictureSet(preserveAspect = true)
    }

    private fun currentSettingsSnapshot(): RendererImageSettings =
        RendererImageSettings(
            blurAmount = blurRadiusFraction,
            duotoneEnabled = duotoneEnabled,
            duotoneAlwaysOn = duotoneAlwaysOn,
            duotoneLightColor = duotoneLightColor,
            duotoneDarkColor = duotoneDarkColor
        )

    fun setParallaxOffset(offset: Float) {
        normalOffsetX = offset.coerceIn(0f, 1f)
        recomputeProjectionMatrix()
        if (surfaceCreated) {
            callbacks.requestRender()
        }
    }

    private fun recomputeProjectionMatrix() {
        val safeSurface = surfaceAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeBitmapAspect = bitmapAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val screenToBitmapAspectRatio = safeSurface / safeBitmapAspect
        buildProjectionMatrix(tempProjectionMatrix, screenToBitmapAspectRatio)
        System.arraycopy(tempProjectionMatrix, 0, projectionMatrix, 0, tempProjectionMatrix.size)

        val prevAspect = previousBitmapAspect?.takeIf { it.isFinite() && it > 0f }
        if (prevAspect != null) {
            val prevRatio = safeSurface / prevAspect
            buildProjectionMatrix(previousProjectionMatrix, prevRatio)
        } else {
            System.arraycopy(projectionMatrix, 0, previousProjectionMatrix, 0, projectionMatrix.size)
        }
    }

    private fun buildProjectionMatrix(target: FloatArray, screenToBitmapAspectRatio: Float) {
        if (screenToBitmapAspectRatio == 0f) {
            Matrix.orthoM(target, 0, -1f, 1f, -1f, 1f, 0f, 1f)
            return
        }
        val zoom = max(1f, screenToBitmapAspectRatio)
        val scaledBitmapToScreenAspectRatio = zoom / screenToBitmapAspectRatio
        val maxPanScreenWidths = min(1.8f, scaledBitmapToScreenAspectRatio)
        val minPan =
            (1f - maxPanScreenWidths / scaledBitmapToScreenAspectRatio) / 2f
        val maxPan =
            (1f + (maxPanScreenWidths - 2f) / scaledBitmapToScreenAspectRatio) / 2f
        val panFraction = minPan + (maxPan - minPan) * normalOffsetX
        val left = -1f + 2f * panFraction
        val right = left + 2f / scaledBitmapToScreenAspectRatio
        val bottom = -1f / zoom
        val top = 1f / zoom
        Matrix.orthoM(target, 0, left, right, bottom, top, 0f, 1f)
    }
}
