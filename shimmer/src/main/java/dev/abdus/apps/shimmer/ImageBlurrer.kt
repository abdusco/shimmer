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

fun Bitmap?.blur(radius: Float): Bitmap? {
    val blurrer = ImageBlurrer(this)
    val blurred = blurrer.blurBitmap(radius)
    return blurred
}

private class ImageBlurrer(private val sourceBitmap: Bitmap?) {

    companion object {
        const val MAX_SUPPORTED_BLUR_RADIUS_PIXELS = 200
    }

    fun blurBitmap(radius: Float): Bitmap? {
        val config = sourceBitmap?.config ?: return null
        val original = sourceBitmap
        if (radius <= 0f || original.width == 0 || original.height == 0) {
            return original.copy(config, true)
        }

        val normalizedRadius = radius.coerceIn(0f, MAX_SUPPORTED_BLUR_RADIUS_PIXELS.toFloat())

        return try {
            GaussianBlurGPURenderer(original.width, original.height).use { renderer ->
                renderer.blur(original, normalizedRadius)
            }
        } catch (t: Throwable) {
            original.copy(config, true)
        }
    }
}

private class GaussianBlurGPURenderer(
    private val width: Int,
    private val height: Int
) : Closeable {

    companion object {
        private const val MAX_RADIUS = ImageBlurrer.MAX_SUPPORTED_BLUR_RADIUS_PIXELS
        private const val POSITION_COMPONENTS = 2
        private const val TEX_COMPONENTS = 2
        private const val FLOAT_BYTES = 4

        private val QUAD_POSITIONS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )

        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        // language=c
        private const val VERTEX_SHADER_CODE = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main(){
              vTexCoord = aTexCoord;
              gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        // language=c
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
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("Unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("Unable to initialize EGL14")
        }
        val eglConfig = chooseConfig(eglDisplay)
        eglContext = createContext(eglDisplay, eglConfig)
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val surface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("Unable to create EGL surface")
        }
        eglSurface = surface
        makeCurrent()

        vertexBuffer = newFloatBuffer(QUAD_POSITIONS)
        texBuffer = newFloatBuffer(QUAD_TEX_COORDS)

        val vertexShader = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)
        program = GLUtil.createAndLinkProgram(vertexShader, fragmentShader)

        attribPosition = GLES20.glGetAttribLocation(program, "aPosition")
        attribTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uniformTex = GLES20.glGetUniformLocation(program, "uTexture")
        uniformTexelSize = GLES20.glGetUniformLocation(program, "uTexelSize")
        uniformDirection = GLES20.glGetUniformLocation(program, "uDirection")
        uniformRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uniformWeights = GLES20.glGetUniformLocation(program, "uWeights")
    }

    fun blur(source: Bitmap, radius: Float): Bitmap? {
        if (width == 0 || height == 0) {
            return null
        }

        makeCurrent()

        val radiusInt = radius.roundToInt().coerceIn(0, MAX_RADIUS)
        val weights = gaussianWeights(radiusInt)
        val texelSize = floatArrayOf(1f / max(1, width), 1f / max(1, height))

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
            radiusInt,
            weights
        )

        // Second pass: vertical blur
        renderPass(
            pingPongTextures[0],
            framebuffers[1],
            floatArrayOf(0f, 1f),
            texelSize,
            radiusInt,
            weights
        )

        val result = readFramebuffer(framebuffers[1])

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        GLES20.glDeleteTextures(2, pingPongTextures, 0)
        GLES20.glDeleteFramebuffers(2, framebuffers, 0)
        GLES20.glFinish()

        return result
    }

    private fun renderPass(
        inputTexture: Int,
        targetFramebuffer: Int,
        direction: FloatArray,
        texelSize: FloatArray,
        radius: Int,
        weights: FloatArray
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

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

        GLES20.glUniform2f(uniformTexelSize, texelSize[0], texelSize[1])
        GLES20.glUniform2f(uniformDirection, direction[0], direction[1])
        GLES20.glUniform1f(uniformRadius, radius.toFloat())
        GLES20.glUniform1fv(uniformWeights, MAX_RADIUS + 1, weights, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexture)
        GLES20.glUniform1i(uniformTex, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(attribPosition)
        GLES20.glDisableVertexAttribArray(attribTexCoord)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun readFramebuffer(framebuffer: Int): Bitmap {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
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

        buffer.rewind()
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = buffer.get().toInt() and 0xFF
                val g = buffer.get().toInt() and 0xFF
                val b = buffer.get().toInt() and 0xFF
                val a = buffer.get().toInt() and 0xFF
                val destIndex = y * width + x
                pixels[destIndex] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun createEmptyTexture(): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
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

    private fun bindTextureToFramebuffer(texture: Int, framebuffer: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            texture,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("Framebuffer incomplete: $status")
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun gaussianWeights(radius: Int): FloatArray {
        val weights = FloatArray(MAX_RADIUS + 1)
        if (radius <= 0) {
            weights[0] = 1f
            return weights
        }
        val sigma = radius / 3f
        val twoSigmaSquare = 2f * sigma * sigma
        var sum = 0f
        for (i in 0..radius) {
            val value = exp(-(i * i) / twoSigmaSquare)
            weights[i] = value
            sum += if (i == 0) value else 2f * value
        }
        for (i in 0..radius) {
            weights[i] /= sum
        }
        return weights
    }

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

    private fun createContext(display: EGLDisplay, config: EGLConfig): EGLContext {
        val attribList = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attribList, 0)
    }

    private fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }
    }

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

    private fun newFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }
}
