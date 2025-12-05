package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.animation.DecelerateInterpolator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * Payload containing image data for the renderer.
 * @property original The original unprocessed bitmap
 * @property blurred List of progressively blurred bitmaps (excluding original).
 *                   The number of blur levels determines the keyframe count.
 *                   Examples:
 *                   - 0 levels: No blur animation (just original)
 *                   - 1 level: [100% blur] → 2 states (original, blurred)
 *                   - 2 levels: [50% blur, 100% blur] → 3 states (original, 50%, 100%)
 */
data class ImageSet(
    val original: Bitmap,
    val blurred: List<Bitmap>
)

/**
 * Duotone color effect configuration.
 * @property lightColor Color for highlights (default: white)
 * @property darkColor Color for shadows (default: black)
 * @property opacity Effect opacity (0.0 = disabled, 1.0 = full effect)
 */
data class Duotone(
    val lightColor: Int = Color.WHITE,
    val darkColor: Int = Color.BLACK,
    val opacity: Float = 0f
)

/**
 * OpenGL ES 2.0 renderer for the Shimmer wallpaper.
 * Handles rendering of images with blur, duotone effects, and parallax scrolling.
 */
class ShimmerRenderer(private val callbacks: Callbacks) :
    GLSurfaceView.Renderer {

    /**
     * Callbacks for the renderer to communicate with the host.
     */
    interface Callbacks {
        /** Request a new frame to be rendered */
        fun requestRender()
    }

    companion object {
        /** Duration of blur animation in milliseconds */
        private const val BLUR_ANIMATION_DURATION = 1200

        /** Duration of image crossfade animation in milliseconds */
        private const val IMAGE_FADE_DURATION = 1200

        /** Duration of duotone color transition in milliseconds */
        private const val DUOTONE_ANIMATION_DURATION = 1200
    }

    // Image state
    private var originalBitmap: Bitmap? = null
    private var blurLevels: List<Bitmap>? = null
    private var pendingImage: ImageSet? = null

    // Surface state
    private var surfaceCreated = false
    private var surfaceAspect = 1f
    private var bitmapAspect = 1f
    private var previousBitmapAspect: Float? = null

    /** Parallax offset (0.0 = left, 0.5 = center, 1.0 = right) */
    private var normalOffsetX = 0.5f

    // Transformation matrices
    private val mvpMatrix = FloatArray(16)
    private val previousMvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val previousProjectionMatrix = FloatArray(16)
    private val tempProjectionMatrix = FloatArray(16)
    private val viewModelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // OpenGL resources
    private var pictureSet: GLPictureSet? = null
    private var previousPictureSet: GLPictureSet? = null
    private lateinit var colorOverlay: GLColorOverlay

    // Animation state
    /** Number of blur keyframes (equals the number of blur levels provided) */
    private var blurKeyframes = 0
    private val blurAnimator = TickingFloatAnimator(BLUR_ANIMATION_DURATION, DecelerateInterpolator())
    private val imageFadeAnimator = TickingFloatAnimator(IMAGE_FADE_DURATION, DecelerateInterpolator()).apply {
        snapTo(1f)
    }
    private val duotoneAnimator = TickingFloatAnimator(DUOTONE_ANIMATION_DURATION, DecelerateInterpolator())

    // Visual effects state
    private var userDimAmount = WallpaperPreferences.DEFAULT_DIM_AMOUNT
    private var isBlurred = false

    // Duotone effect state
    private var duotoneAlwaysOn = false
    private var currentDuotoneLightColor = WallpaperPreferences.DEFAULT_DUOTONE_LIGHT
    private var currentDuotoneDarkColor = WallpaperPreferences.DEFAULT_DUOTONE_DARK
    private var targetDuotoneLightColor = WallpaperPreferences.DEFAULT_DUOTONE_LIGHT
    private var targetDuotoneDarkColor = WallpaperPreferences.DEFAULT_DUOTONE_DARK
    private var targetDuotoneOpacity = 0f

    /**
     * Sets the wallpaper image with pre-processed blur levels.
     * @param imageSet Image payload containing original and blur level bitmaps
     */
    fun setImage(imageSet: ImageSet) {
        if (!surfaceCreated) {
            pendingImage = imageSet
            return
        }
        val bitmap = imageSet.original
        originalBitmap = bitmap
        if (pictureSet != null) {
            previousBitmapAspect = bitmapAspect
        }
        bitmapAspect = if (bitmap.height == 0) 1f else bitmap.width.toFloat() / bitmap.height

        blurLevels = imageSet.blurred
        // Calculate keyframes from the number of blur levels provided
        blurKeyframes = imageSet.blurred.size

        updatePictureSet()
        recomputeProjectionMatrix()
    }

    /**
     * Toggles the blur effect on/off with animation.
     */
    fun toggleBlur() {
        isBlurred = !isBlurred
        val target = if (isBlurred) blurKeyframes.toFloat() else 0f
        blurAnimator.start(startValue = blurAnimator.currentValue, endValue = target)
        callbacks.requestRender()
    }

    /**
     * Sets the dim overlay amount when blurred.
     * @param amount Dim amount (0.0 = no dimming, 1.0 = full dimming)
     */
    fun setUserDimAmount(amount: Float) {
        userDimAmount = amount.coerceIn(0f, 1f)
        callbacks.requestRender()
    }

    /**
     * Configures the duotone color effect.
     * @param enabled Whether the duotone effect is enabled
     * @param alwaysOn If true, effect is always visible; if false, only visible when blurred
     * @param lightColor Color for highlights
     * @param darkColor Color for shadows
     * @param animate Whether to animate the color transition
     */
    fun setDuotoneSettings(
        enabled: Boolean,
        alwaysOn: Boolean,
        lightColor: Int,
        darkColor: Int,
        animate: Boolean = true
    ) {
        duotoneAlwaysOn = alwaysOn
        targetDuotoneOpacity = if (enabled) 1f else 0f

        val colorsChanged = lightColor != targetDuotoneLightColor || darkColor != targetDuotoneDarkColor

        if (animate && colorsChanged) {
            // Capture current visual state
            if (duotoneAnimator.isRunning) {
                // Interpolate from mid-animation colors
                val t = duotoneAnimator.currentValue
                currentDuotoneLightColor = interpolateColor(currentDuotoneLightColor, targetDuotoneLightColor, t)
                currentDuotoneDarkColor = interpolateColor(currentDuotoneDarkColor, targetDuotoneDarkColor, t)
            } else {
                // Use current target colors as starting point
                currentDuotoneLightColor = targetDuotoneLightColor
                currentDuotoneDarkColor = targetDuotoneDarkColor
            }

            // Set new targets and animate
            targetDuotoneLightColor = lightColor
            targetDuotoneDarkColor = darkColor
            duotoneAnimator.start(startValue = 0f, endValue = 1f)
        } else {
            // Apply instantly
            currentDuotoneLightColor = lightColor
            currentDuotoneDarkColor = darkColor
            targetDuotoneLightColor = lightColor
            targetDuotoneDarkColor = darkColor
        }

        callbacks.requestRender()
    }

    /**
     * Linearly interpolates between two colors.
     * @param from Starting color
     * @param to Target color
     * @param t Interpolation factor (0.0 = from, 1.0 = to)
     * @return Interpolated color
     */
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

        // Compute transformation matrices
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(previousMvpMatrix, 0, previousProjectionMatrix, 0, viewModelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewModelMatrix, 0)

        // Update animations
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

        // Calculate duotone opacity based on blur progress (0 = no duotone, 1 = full duotone)
        val blurProgress = if (blurKeyframes > 0) {
            (blurAnimator.currentValue / blurKeyframes).coerceIn(0f, 1f)
        } else {
            0f
        }

        // Calculate final duotone opacity:
        // - If alwaysOn: use targetDuotoneOpacity (1.0 when enabled, 0.0 when disabled)
        // - If not alwaysOn: multiply by blur progress (fade in/out with blur)
        val duotoneOpacity =
            if (duotoneAlwaysOn) targetDuotoneOpacity else (targetDuotoneOpacity * blurProgress)

        val duotone = Duotone(lightColor, darkColor, duotoneOpacity)

        // Draw previous image (fade out during transition)
        previousPictureSet?.drawFrame(
            previousMvpMatrix, blurAnimator.currentValue, 1f - imageAlpha, duotone
        )

        // Draw current image (fade in during transition)
        currentPictureSet.drawFrame(
            mvpMatrix, blurAnimator.currentValue, imageAlpha, duotone
        )

        // Draw dim overlay (increases with blur progress)
        val overlayAlpha = (userDimAmount * blurProgress * 255).toInt().coerceIn(0, 255)
        colorOverlay.color = Color.argb(overlayAlpha, 0, 0, 0)
        Matrix.setIdentityM(modelMatrix, 0)
        colorOverlay.draw(modelMatrix)

        // Clean up previous picture set after fade completes
        if (!imageStillAnimating && imageAlpha >= 1f) {
            previousPictureSet?.destroyPictures()
            previousPictureSet = null
            previousBitmapAspect = null
        }

        // Request another frame if any animation is still running
        if (stillAnimating || imageStillAnimating || duotoneStillAnimating) {
            callbacks.requestRender()
        }
    }

    /**
     * Updates the OpenGL picture set with current bitmaps and initiates crossfade animation.
     * @param preserveAspect If true, preserves the aspect ratio for smooth color-only transitions
     */
    private fun updatePictureSet(preserveAspect: Boolean = false) {
        val baseOriginal = originalBitmap ?: return
        val levels = blurLevels ?: return

        previousPictureSet?.destroyPictures()
        previousPictureSet = pictureSet

        // Build the bitmap list: [original, ...blurLevels]
        val bitmapList = buildList {
            add(baseOriginal)
            addAll(levels)
        }

        pictureSet = GLPictureSet().apply {
            load(bitmapList)
        }
        imageFadeAnimator.start(startValue = 0f, endValue = 1f)

        // When preserving aspect (color changes only), ensure we use the same projection matrix
        if (preserveAspect) {
            previousBitmapAspect = bitmapAspect
            recomputeProjectionMatrix()
        }

        callbacks.requestRender()
    }

    /**
     * Sets the parallax scroll offset for the wallpaper.
     * @param offset Normalized offset (0.0 = leftmost, 0.5 = center, 1.0 = rightmost)
     */
    fun setParallaxOffset(offset: Float) {
        normalOffsetX = offset.coerceIn(0f, 1f)
        recomputeProjectionMatrix()
        if (surfaceCreated) {
            callbacks.requestRender()
        }
    }

    /**
     * Recomputes projection matrices for current and previous images.
     * Handles aspect ratio matching and parallax offset.
     */
    private fun recomputeProjectionMatrix() {
        val safeSurfaceAspect = surfaceAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeBitmapAspect = bitmapAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val aspectRatio = safeSurfaceAspect / safeBitmapAspect

        buildProjectionMatrix(tempProjectionMatrix, aspectRatio)
        System.arraycopy(tempProjectionMatrix, 0, projectionMatrix, 0, tempProjectionMatrix.size)

        val prevAspect = previousBitmapAspect?.takeIf { it.isFinite() && it > 0f }
        if (prevAspect != null) {
            val prevAspectRatio = safeSurfaceAspect / prevAspect
            buildProjectionMatrix(previousProjectionMatrix, prevAspectRatio)
        } else {
            System.arraycopy(projectionMatrix, 0, previousProjectionMatrix, 0, projectionMatrix.size)
        }
    }

    /**
     * Builds an orthographic projection matrix with parallax scrolling support.
     * @param target Output array for the projection matrix
     * @param aspectRatio Ratio of screen aspect to bitmap aspect
     */
    private fun buildProjectionMatrix(target: FloatArray, aspectRatio: Float) {
        if (aspectRatio == 0f) {
            Matrix.orthoM(target, 0, -1f, 1f, -1f, 1f, 0f, 1f)
            return
        }

        // Calculate zoom to ensure bitmap covers the screen
        val zoom = max(1f, aspectRatio)
        val scaledAspect = zoom / aspectRatio

        // Allow full panning across the entire image width
        val maxPanScreenWidths = scaledAspect

        // Calculate pan range
        val minPan = (1f - maxPanScreenWidths / scaledAspect) / 2f
        val maxPan = (1f + (maxPanScreenWidths - 2f) / scaledAspect) / 2f
        val panFraction = minPan + (maxPan - minPan) * normalOffsetX

        // Calculate orthographic projection bounds
        val left = -1f + 2f * panFraction
        val right = left + 2f / scaledAspect
        val bottom = -1f / zoom
        val top = 1f / zoom

        Matrix.orthoM(target, 0, left, right, bottom, top, 0f, 1f)
    }
}


/**
 * Utility functions for OpenGL ES operations.
 */
object GLUtil {
    /** Bytes per float value */
    const val BYTES_PER_FLOAT = 4

    /**
     * Compiles a shader from source code.
     * @param type Shader type (GL_VERTEX_SHADER or GL_FRAGMENT_SHADER)
     * @param shaderCode GLSL source code
     * @return Compiled shader handle
     */
    fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            android.util.Log.e("GLUtil", "Shader compile error: $log")
        }
        return shader
    }

    /**
     * Creates and links a shader program.
     * @param vertexShader Compiled vertex shader handle
     * @param fragmentShader Compiled fragment shader handle
     * @param attributes Optional array of attribute names to bind to specific locations
     * @return Linked program handle
     */
    fun createAndLinkProgram(
        vertexShader: Int,
        fragmentShader: Int,
        attributes: Array<String>? = null
    ): Int {
        val program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")

        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)

        attributes?.forEachIndexed { index, name ->
            GLES20.glBindAttribLocation(program, index, name)
        }

        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            android.util.Log.e("GLUtil", "Program link error: $log")
        }

        // Clean up shaders (program retains compiled code)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        return program
    }

    /**
     * Loads a bitmap as an OpenGL texture with linear filtering and clamped edges.
     * @param bitmap The bitmap to upload
     * @return OpenGL texture handle
     * @throws IllegalStateException if texture creation fails
     */
    fun loadTexture(bitmap: Bitmap): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
        checkGlError("glGenTextures")

        if (textureHandle[0] != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])

            // Set texture parameters
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )

            // Upload bitmap data
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            checkGlError("texImage2D")
        }

        if (textureHandle[0] == 0) {
            error("Error loading texture.")
        }

        return textureHandle[0]
    }

    /**
     * Converts a float array to a native-order FloatBuffer.
     * @param array Float array to convert
     * @return FloatBuffer ready for use with OpenGL
     */
    fun asFloatBuffer(array: FloatArray): FloatBuffer {
        return newFloatBuffer(array.size).apply {
            put(array)
            position(0)
        }
    }

    /**
     * Creates a new native-order FloatBuffer with the specified size.
     * @param size Number of float elements
     * @return Allocated FloatBuffer
     */
    fun newFloatBuffer(size: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { position(0) }
    }

    /**
     * Checks for OpenGL errors and throws an exception if one occurred.
     * @param op Description of the operation being checked
     * @throws RuntimeException if a GL error is detected
     */
    fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }
}