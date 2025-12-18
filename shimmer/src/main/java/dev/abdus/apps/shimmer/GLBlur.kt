package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Increase to 8 for a much smoother "radius shrinking" feel without OOM risk
const val BLUR_KEYFRAMES = 8 
const val MAX_SUPPORTED_BLUR_RADIUS_PIXELS = 300

// Efficiency Constants
const val MAX_BLUR_KEYFRAMES = 8 
private const val PIXELS_PER_STEP = 40f 
private const val BLUR_SCALE_FACTOR = 6 

// The "Ease-Out" factor. 2.0 means the radius grows quadratically.
// This puts more keyframes near the "Sharp" end for a smooth finish.
private const val RADIUS_EXPONENT = 1.8

private const val TAG = "GLBlur"

data class BlurLevelResult(
    val bitmaps: List<Bitmap>,
    val radii: List<Float>
)

/**
 * Generates dynamic blur levels with an exponential radius distribution.
 * This creates a natural ease-out effect when the wallpaper clears.
 */
fun Bitmap.generateBlurLevels(maxRadius: Float): BlurLevelResult {
    if (maxRadius < 1f) return BlurLevelResult(emptyList(), emptyList())

    // Calculate how many frames we need based on intensity
    val calculatedLevels = ceil(maxRadius / PIXELS_PER_STEP).toInt()
        .coerceIn(1, MAX_BLUR_KEYFRAMES)

    val resultBitmaps = mutableListOf<Bitmap>()
    val resultRadii = mutableListOf<Float>()
    val startTime = System.currentTimeMillis()

    val scaledWidth = max(1, width / BLUR_SCALE_FACTOR)
    val scaledHeight = max(1, height / BLUR_SCALE_FACTOR)

    try {
        val baseDownsampled = this.scale(scaledWidth, scaledHeight)

        GLGaussianRenderer(scaledWidth, scaledHeight).use { renderer ->
            for (i in 1..calculatedLevels) {
                // Exponential progress: (i/N)^2
                // Example with 4 levels: 0.06, 0.25, 0.56, 1.0
                val linearProgress = i.toFloat() / calculatedLevels
                val exponentialProgress = linearProgress.toDouble().pow(RADIUS_EXPONENT).toFloat()
                
                val currentRadius = maxRadius * exponentialProgress
                
                renderer.uploadSource(baseDownsampled)
                val blurredSmall = renderer.blur(currentRadius / BLUR_SCALE_FACTOR)

                if (blurredSmall != null) {
                    val finalBlurred = blurredSmall.scale(width, height)
                    resultBitmaps.add(finalBlurred)
                    resultRadii.add(currentRadius)
                    blurredSmall.recycle()
                }
            }
        }
        baseDownsampled.recycle()

    } catch (e: Throwable) {
        Log.e(TAG, "generateBlurLevels: Failed", e)
        resultBitmaps.forEach { it.recycle() }
        return BlurLevelResult(emptyList(), emptyList())
    }

    Log.d(TAG, "Ease-Out Blur: Generated $calculatedLevels levels (Exp: $RADIUS_EXPONENT) in ${System.currentTimeMillis() - startTime}ms")
    return BlurLevelResult(resultBitmaps, resultRadii)
}

private class GLGaussianRenderer(
    private val width: Int,
    private val height: Int,
) : Closeable {

    companion object {
        // Compile-time constant for shader array size
        private const val MAX_RADIUS_CONST = MAX_SUPPORTED_BLUR_RADIUS_PIXELS

        // language=glsl
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoord;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        // language=glsl
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform vec2 uDirection;
            uniform float uRadius;
            uniform float uWeights[${MAX_RADIUS_CONST + 1}];
            varying vec2 vTexCoord;
            
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord) * uWeights[0];
                for (int i = 1; i <= $MAX_RADIUS_CONST; i++) {
                    if (float(i) > uRadius) break;
                    
                    vec2 offset = uDirection * uTexelSize * float(i);
                    float weight = uWeights[i];
                    color += texture2D(uTexture, vTexCoord + offset) * weight;
                    color += texture2D(uTexture, vTexCoord - offset) * weight;
                }
                gl_FragColor = color;
            }
        """

        private val QUAD_COORDS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val TEX_COORDS = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    }

    // EGL State
    private val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val eglContext: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface
    private val oldDisplay = EGL14.eglGetCurrentDisplay()
    private val oldDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
    private val oldReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
    private val oldContext = EGL14.eglGetCurrentContext()

    // GL Resources (Allocated ONCE)
    private val program: Int
    private val framebuffers = IntArray(2)
    private val textures = IntArray(3) // 0: Input, 1: Ping, 2: Pong
    private val vertexBuffer: FloatBuffer
    private val texBuffer: FloatBuffer

    // Readback Buffer (Reused)
    private val readbackBuffer: ByteBuffer

    // Uniform Locations
    private val uTexelSize: Int
    private val uDirection: Int
    private val uRadius: Int
    private val uWeights: Int

    // Weights Cache
    private val weightsCache = mutableMapOf<Int, FloatArray>()

    init {
        // 1. Initialize EGL
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val configAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttr, 0, configs, 0, 1, numConfigs, 0)

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)

        val surfAttr = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfAttr, 0)

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "EGL MakeCurrent failed" }

        // 2. Setup Shaders
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShader)
            GLES20.glLinkProgram(it)
        }

        // 3. Get Locations
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexelSize = GLES20.glGetUniformLocation(program, "uTexelSize")
        uDirection = GLES20.glGetUniformLocation(program, "uDirection")
        uRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uWeights = GLES20.glGetUniformLocation(program, "uWeights")

        // 4. Setup Buffers
        vertexBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD_COORDS).apply { position(0) }
        texBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEX_COORDS).apply { position(0) }
        readbackBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        // 5. Allocate Textures & FBOs ONCE
        GLES20.glGenFramebuffers(2, framebuffers, 0)
        GLES20.glGenTextures(3, textures, 0) // 0=Source, 1=Temp, 2=Dest

        setupTexture(textures[1])
        setupTexture(textures[2])
    }

    private fun setupTexture(texId: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
    }

    fun uploadSource(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun blur(radius: Float): Bitmap? {
        val r = radius.roundToInt().coerceIn(0, MAX_RADIUS_CONST)
        if (r == 0) return null // Should handle outside, but safe check

        val weights = calculateGaussianWeights(r)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        GLES20.glUniform1f(uRadius, r.toFloat())
        GLES20.glUniform2f(uTexelSize, 1f / width, 1f / height)
        GLES20.glUniform1fv(uWeights, weights.size, weights, 0)

        // --- PASS 1: Horizontal (Source -> FBO[0]/Texture[1]) ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[1], 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]) // Read Source
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

        GLES20.glUniform2f(uDirection, 1f, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // --- PASS 2: Vertical (Texture[1] -> FBO[1]/Texture[2]) ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[1])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[2], 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1]) // Read Pass 1 Result

        GLES20.glUniform2f(uDirection, 0f, 1f)
        // ENABLE SWIZZLE: Swap Red and Blue to match Android Bitmap Little Endian format (BGRA)
        // This allows us to use raw fast copyPixelsFromBuffer
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // --- Readback ---
        readbackBuffer.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readbackBuffer)

        val result = createBitmap(width, height)
        readbackBuffer.rewind()
        result.copyPixelsFromBuffer(readbackBuffer)

        return result
    }

    private fun calculateGaussianWeights(radius: Int): FloatArray {
        return weightsCache.getOrPut(radius) {
            val sigma = max(radius / 2f, 1f) // Adjusted sigma for smoother falloff
            val weights = FloatArray(MAX_RADIUS_CONST + 1)
            var sum = 0f
            for (i in 0..radius) {
                weights[i] = exp(-0.5f * (i * i) / (sigma * sigma))
                sum += if (i == 0) weights[i] else 2f * weights[i]
            }
            for (i in 0..radius) weights[i] /= sum
            weights
        }
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
        }
    }

    override fun close() {
        GLES20.glDeleteFramebuffers(2, framebuffers, 0)
        GLES20.glDeleteTextures(3, textures, 0)
        GLES20.glDeleteProgram(program)

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        // Only terminate if we initialized it, but usually safe to leave or let system handle
        // Restoring old context is polite
        if (oldDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(oldDisplay, oldReadSurface, oldDrawSurface, oldContext)
        }
    }
}