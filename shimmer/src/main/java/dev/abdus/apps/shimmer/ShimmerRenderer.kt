package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
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
    val blurred: List<Bitmap> = emptyList(),
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
    val opacity: Float = 0f,
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

        /** Notifies when the renderer has finished its initial setup and is ready to receive commands. */
        fun onRendererReady()

        /** Notifies when all animations (blur, image fade) have completed and renderer is ready to accept a new image. */
        fun onReadyForNextImage()

        /** Notifies when surface dimensions change */
        fun onSurfaceDimensionsChanged(width: Int, height: Int)
    }

    // Effect transition duration (for blur, duotone, and image fade animations)
    private var effectTransitionDurationMillis = 1500

    // Image state
    // Managed by RenderState
    
    // Pending image to load when surface becomes available
    private var pendingImageSet: ImageSet? = null

    // Surface state
    private var surfaceCreated = false
    private var surfaceAspect = 1f
    private var bitmapAspect = 1f
    private var previousBitmapAspect: Float? = null

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
    private lateinit var pictureHandles: PictureHandles
    private var pictureSet: GLPictureSet? = null
    private var previousPictureSet: GLPictureSet? = null
    private var tileSize: Int = 0

    // Animation state
    private val animationController = AnimationController(effectTransitionDurationMillis).apply {
        onImageAnimationComplete = {
            Log.d(TAG, "Animation completed, notifying host")
            callbacks.onReadyForNextImage()
        }
    }

    private var isInitialPreferenceLoad = true


    /**
     * Sets the wallpaper image with pre-processed blur levels.
     * @param imageSet Image payload containing original and blur level bitmaps
     */
    fun setImage(imageSet: ImageSet) {
        Log.d(TAG, "setImage: called, ${imageSet.original.width}x${imageSet.original.height}, blurred=${imageSet.blurred.size}")
        
        if (!surfaceCreated) {
            Log.w(TAG, "setImage: Surface not created, deferring image load")
            pendingImageSet = imageSet
            return
        }
        
        val baseState = animationController.targetRenderState
        val newTargetState = baseState.copy(imageSet = imageSet)

        if (pictureSet != null) {
            previousBitmapAspect = bitmapAspect
        }
        bitmapAspect = if (imageSet.original.height == 0) 1f else imageSet.original.width.toFloat() / imageSet.original.height

        updatePictureSet(imageSet)

        animationController.updateTargetState(newTargetState)
        recomputeProjectionMatrix()
    }

    /**
     * Sets the duration for effect transitions (blur, duotone, dim, and image fade animations).
     * @param durationMillis Duration in milliseconds
     */
    fun setEffectTransitionDuration(durationMillis: Long) {
        effectTransitionDurationMillis = durationMillis.toInt()
        animationController.setDuration(effectTransitionDurationMillis)
    }

    /**
     * Exposes whether the renderer currently has a valid surface/context.
     * Used by the host to decide when it is safe to issue GL work.
     */
    fun isSurfaceReady(): Boolean = surfaceCreated

    /**
     * Marks the surface/context as lost so subsequent commands are deferred.
     */
    fun onSurfaceDestroyed() {
        surfaceCreated = false
    }

    /**
     * Toggles the blur effect on/off with animation.
     */
    fun toggleBlur() {
        Log.d(TAG, "toggleBlur: called")
        val baseState = animationController.targetRenderState
        val newTargetState = baseState.copy(
            blurAmount = if (baseState.blurAmount > 0f) 0f else 1f
        )
        animationController.updateTargetState(newTargetState)
        callbacks.requestRender()
    }

    fun enableBlur(enable: Boolean = true, immediate: Boolean = false) {
        Log.d(TAG, "enableBlur: called with enable=$enable, immediate=$immediate")
        val baseState = animationController.targetRenderState
        val targetBlurAmount = if (enable) 1f else 0f

        if (baseState.blurAmount != targetBlurAmount) {
            val newTargetState = baseState.copy(blurAmount = targetBlurAmount)
            if (immediate || isInitialPreferenceLoad) {
                animationController.setRenderStateImmediately(newTargetState)
                animationController.blurAmountAnimator.reset()
            } else {
                animationController.updateTargetState(newTargetState)
            }
            callbacks.requestRender()
        }
        isInitialPreferenceLoad = false
    }

    /**
     * Sets the dim overlay amount when blurred.
     * @param amount Dim amount (0.0 = no dimming, 1.0 = full dimming)
     */
    fun setUserDimAmount(amount: Float) {
        val newAmount = amount.coerceIn(0f, 1f)
        val baseState = animationController.targetRenderState
        if (baseState.dimAmount != newAmount) {
            val newTargetState = baseState.copy(dimAmount = newAmount)
            animationController.updateTargetState(newTargetState)
            callbacks.requestRender()
        }
    }

    /**
     * Configures the duotone color effect.
     * @param enabled Whether the duotone effect is enabled
     * @param alwaysOn If true, effect is always visible; if false, only visible when blurred
     * @param duotone Duotone colors to blend when enabled
     */
    fun setDuotoneSettings(
        enabled: Boolean,
        alwaysOn: Boolean,
        duotone: Duotone,
    ) {
        val newTargetDuotone = Duotone(
            lightColor = duotone.lightColor,
            darkColor = duotone.darkColor,
            opacity = if (enabled) 1f else 0f
        )
        val baseState = animationController.targetRenderState
        if (baseState.duotone != newTargetDuotone || baseState.duotoneAlwaysOn != alwaysOn) {
            val newTargetState = baseState.copy(
                duotone = newTargetDuotone,
                duotoneAlwaysOn = alwaysOn
            )
            animationController.updateTargetState(newTargetState)
            callbacks.requestRender()
        }
    }

    companion object {
        private const val TAG = "ShimmerRenderer"

        //language=c
        private const val PICTURE_VERTEX_SHADER_CODE = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoords;
            varying vec2 vTexCoords;

            void main() {
                vTexCoords = aTexCoords;
                gl_Position = uMVPMatrix * aPosition;
            }
        """

        //language=glsl
        private const val PICTURE_FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            uniform vec3 uDuotoneLightColor;
            uniform vec3 uDuotoneDarkColor;
            uniform float uDuotoneOpacity;
            uniform float uDimAmount;
            varying vec2 vTexCoords;

            void main() {
                vec4 color = texture2D(uTexture, vTexCoords);
                float lum = 0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b;
                vec3 duotone = mix(uDuotoneDarkColor, uDuotoneLightColor, lum);
                vec3 duotonedColor = mix(color.rgb, duotone, uDuotoneOpacity);
                vec3 dimmedColor = mix(duotonedColor, vec3(0.0), uDimAmount);
                gl_FragColor = vec4(dimmedColor, color.a * uAlpha);
            }
        """

    }


    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        surfaceCreated = true
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Picture shader
        val pictureVertexShader = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, PICTURE_VERTEX_SHADER_CODE)
        val pictureFragmentShader = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, PICTURE_FRAGMENT_SHADER_CODE)
        val pictureProgram = GLUtil.createAndLinkProgram(pictureVertexShader, pictureFragmentShader)
        pictureHandles = PictureHandles(
            program = pictureProgram,
            attribPosition = GLES20.glGetAttribLocation(pictureProgram, "aPosition"),
            attribTexCoords = GLES20.glGetAttribLocation(pictureProgram, "aTexCoords"),
            uniformMvpMatrix = GLES20.glGetUniformLocation(pictureProgram, "uMVPMatrix"),
            uniformTexture = GLES20.glGetUniformLocation(pictureProgram, "uTexture"),
            uniformAlpha = GLES20.glGetUniformLocation(pictureProgram, "uAlpha"),
            uniformDuotoneLight = GLES20.glGetUniformLocation(pictureProgram, "uDuotoneLightColor"),
            uniformDuotoneDark = GLES20.glGetUniformLocation(pictureProgram, "uDuotoneDarkColor"),
            uniformDuotoneOpacity = GLES20.glGetUniformLocation(pictureProgram, "uDuotoneOpacity"),
            uniformDimAmount = GLES20.glGetUniformLocation(pictureProgram, "uDimAmount")
        )

        val maxTextureSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        tileSize = kotlin.math.min(512, maxTextureSize[0])
        if (tileSize == 0) {
            tileSize = 512
        }

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 1f, 0f, 0f, -1f, 0f, 1f, 0f)

        // Load any pending image that was deferred
        pendingImageSet?.let { imageSet ->
            Log.d(TAG, "onSurfaceCreated: Loading pending image")
            pendingImageSet = null
            setImage(imageSet)
        }

        callbacks.onRendererReady()
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceAspect = if (height == 0) 1f else width.toFloat() / height
        recomputeProjectionMatrix()
        callbacks.onSurfaceDimensionsChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val currentRenderState = animationController.currentRenderState

        // Compute transformation matrices
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(viewModelMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(previousMvpMatrix, 0, previousProjectionMatrix, 0, viewModelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewModelMatrix, 0)

        // Update animations
        val isAnimating = animationController.tick()

        // blurAmount is normalized 0-1, convert to actual keyframe count for rendering
        val imageBlurKeyframes = currentRenderState.imageSet.blurred.size
        val blurAmount = currentRenderState.blurAmount.coerceIn(0f, 1f)
        val blurKeyframeIndex = blurAmount * imageBlurKeyframes

        val duotone = currentRenderState.duotone.copy(
            opacity = if (currentRenderState.duotoneAlwaysOn) currentRenderState.duotone.opacity else (currentRenderState.duotone.opacity * blurAmount)
        )

        val finalDimAmount = currentRenderState.dimAmount * blurAmount

        val currentImageAlpha = animationController.imageTransitionAnimator.currentValue
        val previousImageAlpha = 1f - animationController.imageTransitionAnimator.currentValue

        // Draw previous image (fade out during transition)
        previousPictureSet?.drawFrame(
            pictureHandles, tileSize, previousMvpMatrix, blurKeyframeIndex, previousImageAlpha, duotone, finalDimAmount
        )

        // Draw current image (fade in during transition)
        pictureSet?.drawFrame(
            pictureHandles, tileSize, mvpMatrix, blurKeyframeIndex, currentImageAlpha, duotone, finalDimAmount
        )

        // Clean up previous picture set after fade completes
        if (!animationController.imageTransitionAnimator.isRunning && previousImageAlpha <= 0f) {
            previousPictureSet?.destroyPictures()
            previousPictureSet = null
            previousBitmapAspect = null
        }

        // Request another frame if any animation is still running
        if (isAnimating) {
            callbacks.requestRender()
        }
    }

    /**
     * Updates the OpenGL picture set with bitmaps and initiates crossfade animation.
     * @param imageSet The image set to load
     */
    private fun updatePictureSet(imageSet: ImageSet) {
        // Double-check surface is still available before creating textures
        if (!surfaceCreated) {
            Log.w(TAG, "updatePictureSet: Surface no longer available, aborting")
            return
        }
        
        previousPictureSet?.destroyPictures()
        previousPictureSet = pictureSet

        // Build the bitmap list: [original, ...blurLevels]
        val bitmapList = buildList {
            add(imageSet.original)
            addAll(imageSet.blurred)
        }

        try {
            pictureSet = GLPictureSet().apply {
                load(bitmapList, tileSize)
            }
            callbacks.requestRender()
        } catch (e: Exception) {
            Log.w(TAG, "updatePictureSet: Error loading textures, marking surface as unavailable (will retry on next surface)", e)
            surfaceCreated = false
            pendingImageSet = imageSet
            // Swallow and wait for surface recreation; GL context likely lost.
            return
        }
    }

    /**
     * Checks if any image-relevant animations (blur or image fade) are currently running.
     * @return true if blur or image fade animation is in progress
     */
    fun isAnimating(): Boolean {
        val blurRunning = animationController.blurAmountAnimator.isRunning
        val imageRunning = animationController.imageTransitionAnimator.isRunning
        val result = blurRunning || imageRunning
        Log.d(TAG, "isAnimating: blur=$blurRunning, image=$imageRunning, result=$result")
        return result
    }

    /**
     * Sets the parallax scroll offset for the wallpaper.
     * @param offset Normalized offset (0.0 = leftmost, 0.5 = center, 1.0 = rightmost)
     */
    fun setParallaxOffset(offset: Float) {
        val newOffset = offset.coerceIn(0f, 1f)
        val baseState = animationController.targetRenderState
        if (baseState.parallaxOffset != newOffset) {
            val newTargetState = baseState.copy(parallaxOffset = newOffset)
            animationController.updateTargetState(newTargetState)
            recomputeProjectionMatrix() // Recalculate immediately as parallax is not animated
            if (surfaceCreated) {
                callbacks.requestRender()
            }
        }
    }

    /**
     * Recomputes projection matrices for current and previous images.
     * Handles aspect ratio matching and parallax offset.
     */
    private fun recomputeProjectionMatrix() {
        val parallaxOffset = animationController.currentRenderState.parallaxOffset
        val safeSurfaceAspect = surfaceAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeBitmapAspect = bitmapAspect.takeIf { it.isFinite() && it > 0f } ?: 1f
        val aspectRatio = safeSurfaceAspect / safeBitmapAspect

        buildProjectionMatrix(tempProjectionMatrix, aspectRatio, parallaxOffset)
        System.arraycopy(tempProjectionMatrix, 0, projectionMatrix, 0, tempProjectionMatrix.size)

        val prevAspect = previousBitmapAspect?.takeIf { it.isFinite() && it > 0f }
        if (prevAspect != null) {
            val prevAspectRatio = safeSurfaceAspect / prevAspect
            buildProjectionMatrix(previousProjectionMatrix, prevAspectRatio, parallaxOffset)
        } else {
            System.arraycopy(
                projectionMatrix,
                0,
                previousProjectionMatrix,
                0,
                projectionMatrix.size
            )
        }
    }

    /**
     * Builds an orthographic projection matrix with parallax scrolling support.
     * @param target Output array for the projection matrix
     * @param aspectRatio Ratio of screen aspect to bitmap aspect
     * @param parallaxOffset Parallax offset (0.0 = left, 0.5 = center, 1.0 = right)
     */
    private fun buildProjectionMatrix(target: FloatArray, aspectRatio: Float, parallaxOffset: Float) {
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
        val panFraction = minPan + (maxPan - minPan) * parallaxOffset

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
    private const val TAG = "GLUtil"
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
            Log.e(TAG, "Shader compile error: $log")
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
        attributes: Array<String>? = null,
    ): Int {
        val program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")

        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader(vertex)")
        GLES20.glAttachShader(program, fragmentShader)
        checkGlError("glAttachShader(fragment)")

        attributes?.forEachIndexed { index, name ->
            GLES20.glBindAttribLocation(program, index, name)
        }

        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "Program link error: $log")
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
     * @throws IllegalStateException if texture creation fails or no GL context is available
     */
    fun loadTexture(bitmap: Bitmap): Int {
        // Check for valid GL context before attempting texture creation
        val glError = GLES20.glGetError()
        if (glError == GLES20.GL_INVALID_OPERATION) {
            Log.e(TAG, "loadTexture: No valid GL context available (glError=$glError)")
            throw IllegalStateException("No valid GL context available for texture creation")
        }
        
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
