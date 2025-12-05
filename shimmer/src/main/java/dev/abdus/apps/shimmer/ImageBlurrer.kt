package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

const val MAX_SUPPORTED_BLUR_RADIUS_PIXELS = 200
const val BLUR_KEYFRAMES = 1

/**
 * Applies a Gaussian blur to this bitmap using GPU acceleration.
 * @param radius Blur radius in pixels (0-200)
 * @return Blurred bitmap, or a copy of the original if blur fails
 */
fun Bitmap?.blur(radius: Float): Bitmap? {
    val original = this ?: return null
    val config = original.config ?: Bitmap.Config.ARGB_8888

    // Return copy if no blur needed
    if (radius <= 0f || original.width == 0 || original.height == 0) {
        return original.copy(config, true)
    }

    val clampedRadius = radius.coerceIn(0f, MAX_SUPPORTED_BLUR_RADIUS_PIXELS.toFloat())

    return try {
        GaussianBlurGPURenderer(original.width, original.height).use { renderer ->
            renderer.blur(original, clampedRadius)
        }
    } catch (_: Throwable) {
        // Fallback to unblurred copy on error
        original.copy(config, true)
    }
}

/**
 * Generates a list of progressively blurred bitmaps.
 * @param levels Number of blur keyframes (e.g., 2 = [50% blur, 100% blur])
 *               This produces `levels` bitmaps. Combined with the original, you get `levels + 1` total states.
 *               Example: levels=2 produces [50% blur, 100% blur], which with original = 3 states (0%, 50%, 100%)
 * @param maxRadius Maximum blur radius in pixels for the final level
 * @return List of blurred bitmaps, or empty list if generation fails
 */
fun Bitmap.generateBlurLevels(levels: Int, maxRadius: Float): List<Bitmap> {
    if (levels <= 0) return emptyList()

    return buildList {
        for (i in 1..levels) {
            val radius = maxRadius * i / levels
            val blurred = this@generateBlurLevels.blur(radius)
                ?: // If any level fails, return what we have so far
                return this
            add(blurred)
        }
    }
}


/**
 * GPU-accelerated Gaussian blur renderer using OpenGL ES 2.0.
 * Performs a two-pass separable blur (horizontal then vertical) for efficiency.
 */
private class GaussianBlurGPURenderer(
    private val width: Int,
    private val height: Int
) : Closeable {

    companion object {
        /** Maximum blur radius */
        private const val MAX_RADIUS = MAX_SUPPORTED_BLUR_RADIUS_PIXELS

        /** Number of position components per vertex (x, y) */
        private const val POSITION_COMPONENTS = 2

        /** Number of texture coordinate components per vertex (u, v) */
        private const val TEX_COMPONENTS = 2

        /** Bytes per float value */
        private const val FLOAT_BYTES = 4

        /** Full-screen quad positions in normalized device coordinates */
        private val QUAD_POSITIONS = floatArrayOf(
            -1f, -1f,  // bottom-left
            1f, -1f,   // bottom-right
            -1f, 1f,   // top-left
            1f, 1f     // top-right
        )

        /** Texture coordinates for the full-screen quad */
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,  // bottom-left
            1f, 0f,  // bottom-right
            0f, 1f,  // top-left
            1f, 1f   // top-right
        )

        // language=glsl
        private const val VERTEX_SHADER_CODE = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main(){
              vTexCoord = aTexCoord;
              gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        // language=glsl
        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform vec2 uDirection;
            uniform float uRadius;
            uniform float uWeights[${MAX_RADIUS + 1}];
            varying vec2 vTexCoord;
            void main(){
              vec4 color = texture2D(uTexture, vTexCoord) * uWeights[0];
              for (int i = 1; i <= $MAX_RADIUS; i++) {
                if (float(i) > uRadius) {
                  break;
                }
                vec2 offset = uDirection * uTexelSize * float(i);
                float weight = uWeights[i];
                color += texture2D(uTexture, vTexCoord + offset) * weight;
                color += texture2D(uTexture, vTexCoord - offset) * weight;
              }
              gl_FragColor = color;
            }
        """
    }

    private val previousDisplay: EGLDisplay = EGL14.eglGetCurrentDisplay()
    private val previousDrawSurface: EGLSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
    private val previousReadSurface: EGLSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
    private val previousContext: EGLContext = EGL14.eglGetCurrentContext()

    private val eglDisplay: EGLDisplay
    private val eglContext: EGLContext
    private val eglSurface: EGLSurface
    private val vertexBuffer: FloatBuffer
    private val texBuffer: FloatBuffer
    private val program: Int

    private val attribPosition: Int
    private val attribTexCoord: Int
    private val uniformTex: Int
    private val uniformTexelSize: Int
    private val uniformDirection: Int
    private val uniformRadius: Int
    private val uniformWeights: Int

    init {
        // Initialize EGL display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }

        val version = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            "Unable to initialize EGL14"
        }

        // Create EGL context and surface
        val eglConfig = chooseConfig(eglDisplay)
        eglContext = createContext(eglDisplay, eglConfig)

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val surface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        require(surface != null && surface != EGL14.EGL_NO_SURFACE) {
            "Unable to create EGL surface"
        }
        eglSurface = surface
        makeCurrent()

        // Initialize vertex and texture coordinate buffers
        vertexBuffer = newFloatBuffer(QUAD_POSITIONS)
        texBuffer = newFloatBuffer(QUAD_TEX_COORDS)

        // Compile and link shader program
        val vertexShader = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
        program = GLUtil.createAndLinkProgram(vertexShader, fragmentShader)

        // Get attribute and uniform locations
        attribPosition = GLES20.glGetAttribLocation(program, "aPosition")
        attribTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uniformTex = GLES20.glGetUniformLocation(program, "uTexture")
        uniformTexelSize = GLES20.glGetUniformLocation(program, "uTexelSize")
        uniformDirection = GLES20.glGetUniformLocation(program, "uDirection")
        uniformRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uniformWeights = GLES20.glGetUniformLocation(program, "uWeights")
    }

    /**
     * Applies a two-pass separable Gaussian blur to the source bitmap.
     * @param source The bitmap to blur
     * @param radius Blur radius in pixels
     * @return Blurred bitmap, or null if dimensions are invalid
     */
    fun blur(source: Bitmap, radius: Float): Bitmap? {
        if (width == 0 || height == 0) {
            return null
        }

        makeCurrent()

        val blurRadius = radius.roundToInt().coerceIn(0, MAX_RADIUS)
        val weights = gaussianWeights(blurRadius)
        val texelSize = floatArrayOf(1f / max(1, width), 1f / max(1, height))

        // Create framebuffers and textures for ping-pong rendering
        val framebuffers = IntArray(2)
        GLES20.glGenFramebuffers(2, framebuffers, 0)

        val pingPongTextures = IntArray(2)
        pingPongTextures[0] = createEmptyTexture()
        pingPongTextures[1] = createEmptyTexture()
        bindTextureToFramebuffer(pingPongTextures[0], framebuffers[0])
        bindTextureToFramebuffer(pingPongTextures[1], framebuffers[1])

        val inputTexture = GLUtil.loadTexture(source)

        // First pass: horizontal blur
        renderPass(
            inputTexture,
            framebuffers[0],
            floatArrayOf(1f, 0f),
            texelSize,
            blurRadius,
            weights
        )

        // Second pass: vertical blur
        renderPass(
            pingPongTextures[0],
            framebuffers[1],
            floatArrayOf(0f, 1f),
            texelSize,
            blurRadius,
            weights
        )

        val result = readFramebuffer(framebuffers[1])

        // Cleanup GL resources
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        GLES20.glDeleteTextures(2, pingPongTextures, 0)
        GLES20.glDeleteFramebuffers(2, framebuffers, 0)
        GLES20.glFinish()

        return result
    }

    /**
     * Renders a single blur pass (either horizontal or vertical).
     * @param inputTexture Source texture to blur
     * @param targetFramebuffer Destination framebuffer
     * @param direction Blur direction: [1,0] for horizontal, [0,1] for vertical
     * @param texelSize Size of a single texel in normalized coordinates
     * @param radius Blur radius in pixels
     * @param weights Precomputed Gaussian weights
     */
    private fun renderPass(
        inputTexture: Int,
        targetFramebuffer: Int,
        direction: FloatArray,
        texelSize: FloatArray,
        radius: Int,
        weights: FloatArray
    ) {
        // Set render target
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        // Set vertex positions
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            attribPosition,
            POSITION_COMPONENTS,
            GLES20.GL_FLOAT,
            false,
            POSITION_COMPONENTS * FLOAT_BYTES,
            vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(attribPosition)

        // Set texture coordinates
        texBuffer.position(0)
        GLES20.glVertexAttribPointer(
            attribTexCoord,
            TEX_COMPONENTS,
            GLES20.GL_FLOAT,
            false,
            TEX_COMPONENTS * FLOAT_BYTES,
            texBuffer
        )
        GLES20.glEnableVertexAttribArray(attribTexCoord)

        // Set shader uniforms
        GLES20.glUniform2f(uniformTexelSize, texelSize[0], texelSize[1])
        GLES20.glUniform2f(uniformDirection, direction[0], direction[1])
        GLES20.glUniform1f(uniformRadius, radius.toFloat())
        GLES20.glUniform1fv(uniformWeights, MAX_RADIUS + 1, weights, 0)

        // Bind input texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        GLES20.glUniform1i(uniformTex, 0)

        // Draw full-screen quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Cleanup
        GLES20.glDisableVertexAttribArray(attribPosition)
        GLES20.glDisableVertexAttribArray(attribTexCoord)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Reads the contents of a framebuffer into a Bitmap.
     * @param framebuffer The framebuffer to read from
     * @return Bitmap containing the framebuffer contents
     */
    private fun readFramebuffer(framebuffer: Int): Bitmap {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)

        // Allocate buffer for RGBA pixels (4 bytes per pixel)
        val bytesPerPixel = 4
        val buffer = ByteBuffer.allocateDirect(width * height * bytesPerPixel)
            .order(ByteOrder.nativeOrder())

        GLES20.glReadPixels(
            0,
            0,
            width,
            height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buffer
        )

        // Convert RGBA bytes to ARGB integer format
        buffer.rewind()
        val pixels = IntArray(width * height)
        val channelMask = 0xFF

        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = buffer.get().toInt() and channelMask
                val g = buffer.get().toInt() and channelMask
                val b = buffer.get().toInt() and channelMask
                val a = buffer.get().toInt() and channelMask
                val destIndex = y * width + x
                // Pack ARGB into single integer
                pixels[destIndex] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Creates an empty RGBA texture with the renderer's dimensions.
     * @return OpenGL texture handle
     */
    private fun createEmptyTexture(): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])

        // Set texture filtering and wrapping parameters
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Allocate texture storage
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return textureHandle[0]
    }

    /**
     * Attaches a texture to a framebuffer as the color attachment.
     * @param texture Texture to attach
     * @param framebuffer Framebuffer to attach to
     * @throws IllegalStateException if framebuffer is incomplete
     */
    private fun bindTextureToFramebuffer(texture: Int, framebuffer: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture,
            0
        )

        // Verify framebuffer is complete
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("Framebuffer incomplete: $status")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Computes normalized Gaussian weights for blur kernel.
     * Uses the Gaussian function: exp(-(x²)/(2σ²)) where σ = radius/3
     * @param radius Blur radius in pixels
     * @return Array of normalized weights (sum of all weighted samples = 1.0)
     */
    private fun gaussianWeights(radius: Int): FloatArray {
        val weights = FloatArray(MAX_RADIUS + 1)
        if (radius <= 0) {
            weights[0] = 1f
            return weights
        }

        // Calculate standard deviation (sigma) for Gaussian distribution
        val sigma = radius / 3f
        val twoSigmaSquare = 2f * sigma * sigma

        // Compute unnormalized weights
        var sum = 0f
        for (i in 0..radius) {
            val value = exp(-(i * i) / twoSigmaSquare)
            weights[i] = value
            // Each weight (except center) is used twice (positive and negative offset)
            sum += if (i == 0) value else 2f * value
        }

        // Normalize weights so they sum to 1.0
        for (i in 0..radius) {
            weights[i] /= sum
        }

        return weights
    }

    /**
     * Chooses an EGL configuration that supports RGBA8888 and OpenGL ES 2.0.
     * @param display EGL display to query
     * @return Selected EGL configuration
     * @throws IllegalStateException if no suitable config is found
     */
    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, configs.size, numConfigs, 0) ||
            numConfigs[0] <= 0
        ) {
            throw IllegalStateException("Unable to find RGB8888 EGL config")
        }
        return configs[0]!!
    }

    /**
     * Creates an OpenGL ES 2.0 context.
     * @param display EGL display
     * @param config EGL configuration
     * @return Created EGL context
     */
    private fun createContext(display: EGLDisplay, config: EGLConfig): EGLContext {
        val attribList = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribList, 0)
    }

    /**
     * Makes this renderer's EGL context current on the calling thread.
     * @throws IllegalStateException if the operation fails
     */
    private fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
    }

    /**
     * Cleans up OpenGL and EGL resources.
     * Restores the previous EGL context if one existed.
     */
    override fun close() {
        GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        if (previousDisplay == EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
        }
        restorePreviousContext()
    }

    /**
     * Restores the EGL context that was active before this renderer was created.
     */
    private fun restorePreviousContext() {
        if (previousDisplay != EGL14.EGL_NO_DISPLAY && previousContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(
                previousDisplay,
                previousDrawSurface,
                previousReadSurface,
                previousContext
            )
        }
    }

    /**
     * Creates a native-order float buffer from the given data array.
     * @param data Float array to convert
     * @return FloatBuffer ready for use with OpenGL
     */
    private fun newFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }
}
