package dev.abdus.apps.buzei

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.animation.DecelerateInterpolator
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

data class RendererImagePayload(
    val original: Bitmap,
    val blurred: Bitmap,
    val sourceUri: Uri? = null
)

class BuzeiRenderer(private val callbacks: Callbacks) :
    GLSurfaceView.Renderer {

    interface Callbacks {
        fun requestRender()
    }

    companion object {
        private const val BLUR_ANIMATION_DURATION = 1200
        private const val IMAGE_FADE_DURATION = 1200
        private const val DUOTONE_ANIMATION_DURATION = 1200
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
    private val imageFadeAnimator = TickingFloatAnimator(IMAGE_FADE_DURATION, DecelerateInterpolator()).apply {
        snapTo(1f)
    }
    private val duotoneAnimator = TickingFloatAnimator(DUOTONE_ANIMATION_DURATION, DecelerateInterpolator())

    private var userDimAmount = WallpaperPreferences.DEFAULT_DIM_AMOUNT
    private var isBlurred = false

    // Duotone state
    private var duotoneEnabled = false
    private var currentDuotoneLightColor = WallpaperPreferences.DEFAULT_DUOTONE_LIGHT
    private var currentDuotoneDarkColor = WallpaperPreferences.DEFAULT_DUOTONE_DARK
    private var targetDuotoneLightColor = WallpaperPreferences.DEFAULT_DUOTONE_LIGHT
    private var targetDuotoneDarkColor = WallpaperPreferences.DEFAULT_DUOTONE_DARK

    fun setImage(bitmap: Bitmap) {
        setImage(RendererImagePayload(original = bitmap, blurred = bitmap))
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

        // Trust the service-provided pre-processed bitmap
        blurredBitmap = image.blurred

        updatePictureSet()
        recomputeProjectionMatrix()
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

    fun setDuotoneSettings(enabled: Boolean, lightColor: Int, darkColor: Int, animate: Boolean = true) {
        duotoneEnabled = enabled

        // Check if colors are actually changing
        val colorsChanged = (lightColor != targetDuotoneLightColor || darkColor != targetDuotoneDarkColor)

        if (animate && colorsChanged) {
            // If animator is already running, use its current interpolated colors as the new start
            // Otherwise use the current target colors (which are what's currently displayed)
            if (!duotoneAnimator.isRunning) {
                currentDuotoneLightColor = targetDuotoneLightColor
                currentDuotoneDarkColor = targetDuotoneDarkColor
            }
            // If animator IS running, currentDuotone*Color will be updated in onDrawFrame
            // to the last interpolated values, so we keep those

            // Set new target colors
            targetDuotoneLightColor = lightColor
            targetDuotoneDarkColor = darkColor

            // Start animation from 0 to 1 (will interpolate between current and target)
            duotoneAnimator.start(startValue = 0f, endValue = 1f)
        } else {
            // Instant change (no animation)
            currentDuotoneLightColor = lightColor
            currentDuotoneDarkColor = darkColor
            targetDuotoneLightColor = lightColor
            targetDuotoneDarkColor = darkColor
        }

        callbacks.requestRender()
    }

    private fun interpolateColor(from: Int, to: Int, t: Float): Int {
        val fromR = Color.red(from)
        val fromG = Color.green(from)
        val fromB = Color.blue(from)
        val toR = Color.red(to)
        val toG = Color.green(to)
        val toB = Color.blue(to)

        val r = (fromR + (toR - fromR) * t).toInt().coerceIn(0, 255)
        val g = (fromG + (toG - fromG) * t).toInt().coerceIn(0, 255)
        val b = (fromB + (toB - fromB) * t).toInt().coerceIn(0, 255)

        return Color.rgb(r, g, b)
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
        val duotoneStillAnimating = duotoneAnimator.tick()

        // Update duotone colors with animation
        val lightColor: Int
        val darkColor: Int
        if (duotoneStillAnimating) {
            val t = duotoneAnimator.currentValue
            lightColor = interpolateColor(currentDuotoneLightColor, targetDuotoneLightColor, t)
            darkColor = interpolateColor(currentDuotoneDarkColor, targetDuotoneDarkColor, t)
        } else {
            lightColor = targetDuotoneLightColor
            darkColor = targetDuotoneDarkColor
            currentDuotoneLightColor = targetDuotoneLightColor
            currentDuotoneDarkColor = targetDuotoneDarkColor
        }

        val imageAlpha = imageFadeAnimator.currentValue.coerceIn(0f, 1f)
        previousPictureSet?.drawFrame(previousMvpMatrix, blurAnimator.currentValue, 1f - imageAlpha,
            duotoneEnabled, lightColor, darkColor)
        currentPictureSet.drawFrame(mvpMatrix, blurAnimator.currentValue, imageAlpha,
            duotoneEnabled, lightColor, darkColor)

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

        if (stillAnimating || imageStillAnimating || duotoneStillAnimating) {
            callbacks.requestRender()
        }
    }

    private fun updatePictureSet(preserveAspect: Boolean = false) {
        val baseOriginal = originalBitmap ?: return
        val blurred = blurredBitmap ?: baseOriginal

        previousPictureSet?.destroyPictures()
        previousPictureSet = pictureSet
        pictureSet = GLPictureSet(blurKeyframes).apply {
            load(listOf(baseOriginal, blurred))
        }
        imageFadeAnimator.start(startValue = 0f, endValue = 1f)

        // When preserving aspect (color changes only), ensure we use the same projection matrix
        if (preserveAspect) {
            previousBitmapAspect = bitmapAspect
            recomputeProjectionMatrix()
        }

        callbacks.requestRender()
    }


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
