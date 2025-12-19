package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import androidx.core.graphics.createBitmap
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

const val MAX_SUPPORTED_BLUR_RADIUS_PIXELS = 300
private const val TAG = "GLBlur"

data class BlurLevelResult(
    val bitmaps: List<Bitmap>,
    val radii: List<Float>
)

fun Bitmap.generateBlurLevels(maxRadius: Float, cancellationCheck: (() -> Unit)? = null): BlurLevelResult {
    val requestedRadius = maxRadius.coerceAtMost(MAX_SUPPORTED_BLUR_RADIUS_PIXELS.toFloat())
    if (requestedRadius < 1f) return BlurLevelResult(emptyList(), emptyList())

    val resultBitmaps = mutableListOf<Bitmap>()
    val resultRadii = mutableListOf<Float>()
    val startTime = System.currentTimeMillis()

    val numKeyframes = when {
        requestedRadius < 10f -> 1
        requestedRadius < 40f -> 2
        else -> ceil(requestedRadius / 40f).toInt().coerceIn(1, 4)
    }

    try {
        // Create the EGL Context & Renderer ONCE
        GLGaussianRenderer().use { renderer ->
            // OPTIMIZATION: Upload the FULL source image just once.
            // We will never scale this bitmap on the CPU.
            renderer.uploadSource(this)

            for (i in 1..numKeyframes) {
                cancellationCheck?.invoke()
                
                val progress = (i.toFloat() / numKeyframes).pow(2f)
                val currentRadius = requestedRadius * progress
                
                // Adaptive divisor: Higher blur = smaller texture
                val divisor = (currentRadius / 40f).coerceIn(2f, 8f)
                val targetW = max(1, (width / divisor).toInt())
                val targetH = max(1, (height / divisor).toInt())
                
                // OPTIMIZATION: GPU does the resizing and the blurring in one pipeline.
                // We pass the dimensions, not a bitmap.
                val blurred = renderer.blur(targetW, targetH, currentRadius / divisor)
                
                if (blurred != null) {
                    resultBitmaps.add(blurred)
                    resultRadii.add(currentRadius)
                }
            }
        }
    } catch (e: Throwable) {
        Log.e(TAG, "generateBlurLevels failed", e)
        resultBitmaps.forEach { it.recycle() }
        return BlurLevelResult(emptyList(), emptyList())
    }

    Log.d(TAG, "Generated $numKeyframes levels in ${System.currentTimeMillis() - startTime}ms")
    return BlurLevelResult(resultBitmaps, resultRadii)
}

private class GLGaussianRenderer : Closeable {

    companion object {
        // We fetch 2 pixels per sample using bilinear interpolation, so we only need ~half the samples.
        private const val MAX_SAMPLES = (MAX_SUPPORTED_BLUR_RADIUS_PIXELS / 2) + 5

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
        private const val FRAGMENT_SHADER_COPY = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // language=glsl
        private val FRAGMENT_SHADER_BLUR = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform vec2 uDirection;
            uniform int uSampleCount; 
            uniform float uOffsets[$MAX_SAMPLES];
            uniform float uWeights[$MAX_SAMPLES];
            varying vec2 vTexCoord;
            
            void main() {
                // Center pixel (weight[0])
                vec4 color = texture2D(uTexture, vTexCoord) * uWeights[0];
                
                // Bilinear sampling loop
                for (int i = 1; i < $MAX_SAMPLES; i++) {
                    if (i >= uSampleCount) break;
                    
                    vec2 offset = uDirection * uTexelSize * uOffsets[i];
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
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    
    // Restoration State
    private val oldDisplay = EGL14.eglGetCurrentDisplay()
    private val oldDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
    private val oldReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
    private val oldContext = EGL14.eglGetCurrentContext()

    // GL Resources
    private var programBlur: Int = 0
    private var programCopy: Int = 0
    private val framebuffers = IntArray(2)
    
    // Textures:
    // [0] = Source (Full Resolution, Immutable)
    // [1] = Ping   (Target Resolution, used for Downscale output & Horizontal Blur output)
    // [2] = Pong   (Target Resolution, used for Vertical Blur input)
    private val textures = IntArray(3) 

    private var currentWidth = 0
    private var currentHeight = 0

    // Uniforms (Blur Program)
    private val uTexelSize: Int
    private val uDirection: Int
    private val uSampleCount: Int
    private val uOffsets: Int
    private val uWeights: Int
    
    // Buffers
    private val vertexBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD_COORDS).apply { position(0) }
    private val texBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(TEX_COORDS).apply { position(0) }
    
    // Math Cache
    private val weightsCache = mutableMapOf<Int, Pair<FloatArray, FloatArray>>()

    init {
        initEGL()

        // 1. Setup Copy/Downscale Shader
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fShaderCopy = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_COPY)
        programCopy = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShaderCopy)
            GLES20.glLinkProgram(it)
        }

        // 2. Setup Blur Shader
        val fShaderBlur = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_BLUR)
        programBlur = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vShader)
            GLES20.glAttachShader(it, fShaderBlur)
            GLES20.glLinkProgram(it)
        }

        // 3. Get Locations
        val aPosition = GLES20.glGetAttribLocation(programBlur, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(programBlur, "aTexCoord")
        uTexelSize = GLES20.glGetUniformLocation(programBlur, "uTexelSize")
        uDirection = GLES20.glGetUniformLocation(programBlur, "uDirection")
        uSampleCount = GLES20.glGetUniformLocation(programBlur, "uSampleCount")
        uOffsets = GLES20.glGetUniformLocation(programBlur, "uOffsets")
        uWeights = GLES20.glGetUniformLocation(programBlur, "uWeights")

        // 4. Bind Common Attributes
        for (prog in listOf(programBlur, programCopy)) {
            GLES20.glUseProgram(prog)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)
        }

        // 5. Generate GL Objects
        GLES20.glGenFramebuffers(2, framebuffers, 0)
        GLES20.glGenTextures(3, textures, 0)
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        
        val configAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttr, 0, configs, 0, 1, numConfigs, 0)

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)

        val surfAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfAttr, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun resizeInternal(w: Int, h: Int) {
        if (currentWidth == w && currentHeight == h) return
        currentWidth = w
        currentHeight = h

        // Resize Ping/Pong textures (1 and 2). 
        // Texture 0 is source and immutable.
        setupTexture(textures[1], w, h)
        setupTexture(textures[2], w, h)
    }

    private fun setupTexture(texId: Int, w: Int, h: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
    }

    fun uploadSource(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    fun blur(targetW: Int, targetH: Int, radius: Float): Bitmap? {
        // Ensure internal buffers match the requested scale
        resizeInternal(targetW, targetH)

        // --- PASS 0: GPU DOWNSCALE ---
        // Input: Texture[0] (Full Source)
        // Output: Framebuffer[0] (Attached to Texture[1])
        GLES20.glViewport(0, 0, targetW, targetH)
        GLES20.glUseProgram(programCopy)
        
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[1], 0)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programCopy, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // --- PASS 1 & 2: BLUR ---
        val r = radius.roundToInt()
        // If radius is 0, we can just return the downscaled source
        if (r < 1) {
            return readPixels(targetW, targetH)
        }

        val (weights, offsets) = calculateOptimizedWeights(r)
        val sampleCount = weights.size

        GLES20.glUseProgram(programBlur)
        GLES20.glUniform2f(uTexelSize, 1f / targetW, 1f / targetH)
        GLES20.glUniform1i(uSampleCount, sampleCount)
        GLES20.glUniform1fv(uWeights, sampleCount, weights, 0)
        GLES20.glUniform1fv(uOffsets, sampleCount, offsets, 0)

        // Pass 1: Horizontal
        // Input: Texture[1] (Downscaled Source)
        // Output: Framebuffer[1] (Attached to Texture[2])
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[1])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[2], 0)
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
        GLES20.glUniform2f(uDirection, 1f, 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Pass 2: Vertical
        // Input: Texture[2] (Horz Blur Result)
        // Output: Framebuffer[0] (Attached to Texture[1]) - We write back to 1
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[1], 0)
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[2])
        GLES20.glUniform2f(uDirection, 0f, 1f)
        
        // SWIZZLE: Normally we swap R/B here if using ByteBuffer, 
        // but since we use createBitmap + copyPixelsFromBuffer, Android expects ABGR/RGBA depending on endianness.
        // Usually RGBA is fine for GL_RGBA readback.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        return readPixels(targetW, targetH)
    }

    private fun readPixels(w: Int, h: Int): Bitmap {
        val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        // Read from currently bound Framebuffer[0] (which has Texture[1])
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        
        val result = createBitmap(w, h)
        result.copyPixelsFromBuffer(buffer)
        return result
    }

    private fun calculateOptimizedWeights(radius: Int): Pair<FloatArray, FloatArray> {
        return weightsCache.getOrPut(radius) {
            // OPTIMIZATION: Sigma / 3.0 provides a smoother, more "natural" spread than / 2.0
            val sigma = max(radius / 3f, 0.5f) 
            
            // 1. Generate standard Gaussian weights
            val standardWeights = ArrayList<Float>()
            var sum = 0f
            for (i in 0..radius) {
                val w = exp(-0.5f * (i * i) / (sigma * sigma))
                standardWeights.add(w)
                sum += if (i == 0) w else 2f * w
            }
            for (i in standardWeights.indices) standardWeights[i] /= sum

            // 2. Compress using Linear Interpolation
            // [0] stays [0]
            // [1] & [2] become new [1]
            // [3] & [4] become new [2]
            val optWeights = ArrayList<Float>()
            val optOffsets = ArrayList<Float>()

            optWeights.add(standardWeights[0])
            optOffsets.add(0f)

            var i = 1
            while (i < standardWeights.size) {
                val w1 = standardWeights[i]
                val w2 = if (i + 1 < standardWeights.size) standardWeights[i + 1] else 0f
                
                val combinedWeight = w1 + w2
                val offset = (w1 * i + w2 * (i + 1)) / combinedWeight
                
                optWeights.add(combinedWeight)
                optOffsets.add(offset)
                
                i += 2
            }
            
            val safeSize = optWeights.size.coerceAtMost(MAX_SAMPLES)
            Pair(optWeights.take(safeSize).toFloatArray(), optOffsets.take(safeSize).toFloatArray())
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
        GLES20.glDeleteProgram(programBlur)
        GLES20.glDeleteProgram(programCopy)

        EGL14.eglMakeCurrent(oldDisplay, oldReadSurface, oldDrawSurface, oldContext)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
    }
}