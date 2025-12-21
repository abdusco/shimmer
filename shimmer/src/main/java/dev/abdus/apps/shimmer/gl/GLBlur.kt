package dev.abdus.apps.shimmer.gl

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
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

const val MAX_SUPPORTED_BLUR_RADIUS_PIXELS = 360
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
        GLGaussianRenderer().use { renderer ->
            renderer.uploadSource(this)

            for (i in 1..numKeyframes) {
                cancellationCheck?.invoke()

                val progress = (i.toFloat() / numKeyframes).pow(2f)
                val currentRadius = requestedRadius * progress

                val divisor = (currentRadius / 40f).coerceIn(2f, 8f)
                val targetW = max(1, (width / divisor).toInt())
                val targetH = max(1, (height / divisor).toInt())

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
        private const val MAX_SAMPLES = (MAX_SUPPORTED_BLUR_RADIUS_PIXELS / 2) + 5

        // language=glsl
        private const val VERTEX_SHADER = """#version 300 es
            in vec4 aPosition;
            in vec2 aTexCoords;
            out vec2 vTexCoord;
            void main() {
                vTexCoord = aTexCoords;
                // Flip the Y-axis. This renders the image upside down in the FBO.
                // Since glReadPixels reads from the bottom-up, the resulting
                // Bitmap will be correctly oriented.
                gl_Position = vec4(aPosition.x, -aPosition.y, aPosition.z, 1.0);
            }
        """

        // language=glsl
        private const val FRAGMENT_SHADER_COPY = """#version 300 es
            precision mediump float;
            uniform sampler2D uTexture;
            in vec2 vTexCoord;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTexture, vTexCoord);
            }
        """

        // language=glsl
        private val FRAGMENT_SHADER_BLUR = """#version 300 es
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform vec2 uDirection;
            uniform int uSampleCount;
            uniform float uOffsets[$MAX_SAMPLES];
            uniform float uWeights[$MAX_SAMPLES];
            in vec2 vTexCoord;
            out vec4 fragColor;

            void main() {
                vec4 color = texture(uTexture, vTexCoord) * uWeights[0];
                for (int i = 1; i < $MAX_SAMPLES; i++) {
                    if (i >= uSampleCount) break;
                    vec2 offset = uDirection * uTexelSize * uOffsets[i];
                    color += texture(uTexture, vTexCoord + offset) * uWeights[i];
                    color += texture(uTexture, vTexCoord - offset) * uWeights[i];
                }
                fragColor = color;
            }
        """
    }

    // EGL State
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    private val oldDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
    private val oldReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

    // Program Handles
    private var programBlur: Int = 0
    private var programCopy: Int = 0
    private val framebuffers = IntArray(2)
    private val textures = IntArray(3)

    private var currentWidth = 0
    private var currentHeight = 0

    // Attribute Locations
    private var aPositionLoc = -1
    private var aTexCoordsLoc = -1

    // Uniform Locations (Blur)
    private var uTexelSize: Int = -1
    private var uDirection: Int = -1
    private var uSampleCount: Int = -1
    private var uOffsets: Int = -1
    private var uWeights: Int = -1

    private val weightsCache = mutableMapOf<Int, Pair<FloatArray, FloatArray>>()

    init {
        val currentContext = EGL14.eglGetCurrentContext()
        if (currentContext != EGL14.EGL_NO_CONTEXT) {
             val threadName = Thread.currentThread().name
             if (threadName.contains("GLThread")) {
                 error("GLGaussianRenderer MUST NOT be used on the main GLThread")
             }
        }

        initEGL()

        // 1. Setup Programs using ShaderCompiler
        val vShader = ShaderCompiler.compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)

        programCopy = ShaderCompiler.linkProgram(vShader,
                                                 ShaderCompiler.compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_COPY))

        programBlur = ShaderCompiler.linkProgram(vShader,
                                                 ShaderCompiler.compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_BLUR))

        // 2. Cache Locations (Use names from QuadMesh/ShimmerProgram)
        aPositionLoc = GLES30.glGetAttribLocation(programBlur, "aPosition")
        aTexCoordsLoc = GLES30.glGetAttribLocation(programBlur, "aTexCoords")

        uTexelSize = GLES30.glGetUniformLocation(programBlur, "uTexelSize")
        uDirection = GLES30.glGetUniformLocation(programBlur, "uDirection")
        uSampleCount = GLES30.glGetUniformLocation(programBlur, "uSampleCount")
        uOffsets = GLES30.glGetUniformLocation(programBlur, "uOffsets")
        uWeights = GLES30.glGetUniformLocation(programBlur, "uWeights")

        // 3. Generate GL Objects
        GLES30.glGenFramebuffers(2, framebuffers, 0)
        GLES30.glGenTextures(3, textures, 0)
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttr, 0, configs, 0, 1, numConfigs, 0)

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)

        // Make the Pbuffer tiny - we only need it to make the context current.
        // We do our actual rendering into Framebuffers (FBOs).
        val surfAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfAttr, 0)

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            error("Failed to make blur context current")
        }
    }

    private fun resizeInternal(w: Int, h: Int) {
        if (currentWidth == w && currentHeight == h) return

        // Since TextureLoader.allocate uses glTexStorage2D (immutable),
        // we must delete and re-generate if the size actually changes.
        if (currentWidth != 0) {
            GLES30.glDeleteTextures(2, textures, 1)
            GLES30.glGenTextures(2, textures, 1)
        }

        currentWidth = w
        currentHeight = h

        textures[1] = TextureLoader.allocate(w, h)
        textures[2] = TextureLoader.allocate(w, h)
    }

    fun uploadSource(bitmap: Bitmap) {
        // Source is usually uploaded once, but if reused, we use basic load
        if (textures[0] != 0) GLES30.glDeleteTextures(1, textures, 0)
        textures[0] = TextureLoader.load(bitmap)
    }

    fun blur(targetW: Int, targetH: Int, radius: Float): Bitmap? {
        resizeInternal(targetW, targetH)

        // --- PASS 0: DOWNSCALE ---
        GLES30.glViewport(0, 0, targetW, targetH)
        GLES30.glUseProgram(programCopy)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textures[1], 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programCopy, "uTexture"), 0)

        QuadMesh.draw(aPositionLoc, aTexCoordsLoc)

        val r = radius.roundToInt()
        if (r < 1) return readPixels(targetW, targetH)

        // --- PASS 1 & 2: BLUR ---
        val (weights, offsets) = calculateOptimizedWeights(r)
        val sampleCount = weights.size

        GLES30.glUseProgram(programBlur)
        GLES30.glUniform2f(uTexelSize, 1f / targetW, 1f / targetH)
        GLES30.glUniform1i(uSampleCount, sampleCount)
        GLES30.glUniform1fv(uWeights, sampleCount, weights, 0)
        GLES30.glUniform1fv(uOffsets, sampleCount, offsets, 0)

        // Pass 1: Horizontal (Input: 1, Output: 2)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[1])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textures[2], 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[1])
        GLES30.glUniform2f(uDirection, 1f, 0f)
        QuadMesh.draw(aPositionLoc, aTexCoordsLoc)

        // Pass 2: Vertical (Input: 2, Output: 1)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffers[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textures[1], 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[2])
        GLES30.glUniform2f(uDirection, 0f, 1f)
        QuadMesh.draw(aPositionLoc, aTexCoordsLoc)

        return readPixels(targetW, targetH)
    }

    private fun readPixels(w: Int, h: Int): Bitmap {
        val buffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)

        val result = createBitmap(w, h)
        result.copyPixelsFromBuffer(buffer)
        return result
    }

    private fun calculateOptimizedWeights(radius: Int): Pair<FloatArray, FloatArray> {
        return weightsCache.getOrPut(radius) {
            val sigma = max(radius / 3f, 0.5f)
            val standardWeights = ArrayList<Float>()
            var sum = 0f
            for (i in 0..radius) {
                val w = exp(-0.5f * (i * i) / (sigma * sigma))
                standardWeights.add(w)
                sum += if (i == 0) w else 2f * w
            }
            for (i in standardWeights.indices) standardWeights[i] /= sum

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

    override fun close() {
        // 1. Delete GL objects
        GLES30.glDeleteFramebuffers(2, framebuffers, 0)
        GLES30.glDeleteTextures(3, textures, 0)
        GLES30.glDeleteProgram(programBlur)
        GLES30.glDeleteProgram(programCopy)

        // 2. UNBIND the context from this thread completely.
        // This prevents "Zombie" context issues when Dispatchers.IO reuses this thread.
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )

        // 3. Destroy resources
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}
