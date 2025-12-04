package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.graphics.Rect
import android.opengl.GLES20
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

class GLPictureSet(private val blurKeyframes: Int) {

    private val pictures = arrayOfNulls<GLPicture>(blurKeyframes + 1)

    fun load(bitmaps: List<Bitmap?>) {
        destroyPictures()
        for (index in pictures.indices) {
            pictures[index] = bitmaps.getOrNull(index)?.let { GLPicture(it) }
        }
    }

    fun drawFrame(
        mvpMatrix: FloatArray, blurFrame: Float, globalAlpha: Float = 1f,
        duotone: Duotone = Duotone()
    ) {
        if (pictures.all { it == null }) {
            return
        }

        val clampedBlur = blurFrame.coerceIn(0f, blurKeyframes.toFloat())
        val lo = floor(clampedBlur.toDouble()).toInt().coerceAtMost(blurKeyframes)
        val hi = ceil(clampedBlur.toDouble()).toInt().coerceAtMost(blurKeyframes)
        val localHiAlpha = clampedBlur - lo

        when {
            globalAlpha <= 0f -> return
            lo == hi -> {
                pictures[lo]?.draw(mvpMatrix, globalAlpha, duotone)
            }

            globalAlpha == 1f -> {
                pictures[lo]?.draw(mvpMatrix, 1f - localHiAlpha, duotone)
                pictures[hi]?.draw(mvpMatrix, localHiAlpha, duotone)
            }

            else -> {
                val loPicture = pictures[lo] ?: return
                val hiPicture = pictures[hi] ?: return
                val newLocalLoAlpha =
                    globalAlpha * (localHiAlpha - 1) / (globalAlpha * localHiAlpha - 1)
                val newLocalHiAlpha = globalAlpha * localHiAlpha
                loPicture.draw(mvpMatrix, newLocalLoAlpha, duotone)
                hiPicture.draw(mvpMatrix, newLocalHiAlpha, duotone)
            }
        }
    }

    fun destroyPictures() {
        for (i in pictures.indices) {
            pictures[i]?.destroy()
            pictures[i] = null
        }
    }
}


class GLPicture(bitmap: Bitmap) {

    companion object {
        private const val COORDS_PER_VERTEX = 3
        private const val VERTICES = 6
        private const val COORDS_PER_TEXTURE_VERTEX = 2
        private const val VERTEX_STRIDE_BYTES = COORDS_PER_VERTEX * GLUtil.BYTES_PER_FLOAT
        private const val TEXTURE_VERTEX_STRIDE_BYTES =
            COORDS_PER_TEXTURE_VERTEX * GLUtil.BYTES_PER_FLOAT

        private val SQUARE_TEXTURE_VERTICES = floatArrayOf(
            0f, 0f, // top left
            0f, 1f, // bottom left
            1f, 1f, // bottom right

            0f, 0f, // top left
            1f, 1f, // bottom right
            1f, 0f
        ) // top right

        private var PROGRAM_HANDLE = 0
        private var ATTRIB_POSITION_HANDLE = 0
        private var ATTRIB_TEXTURE_COORDS_HANDLE = 0
        private var UNIFORM_MVP_MATRIX_HANDLE = 0
        private var UNIFORM_TEXTURE_HANDLE = 0
        private var UNIFORM_ALPHA_HANDLE = 0
        private var UNIFORM_DUOTONE_LIGHT_HANDLE = 0
        private var UNIFORM_DUOTONE_DARK_HANDLE = 0
        private var UNIFORM_DUOTONE_OPACITY_HANDLE = 0

        private var TILE_SIZE: Int = 0

        fun initGl() {
            val vertexShaderHandle = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
            val fragShaderHandle =
                GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

            PROGRAM_HANDLE = GLUtil.createAndLinkProgram(vertexShaderHandle, fragShaderHandle, null)
            ATTRIB_POSITION_HANDLE = GLES20.glGetAttribLocation(PROGRAM_HANDLE, "aPosition")
            ATTRIB_TEXTURE_COORDS_HANDLE = GLES20.glGetAttribLocation(PROGRAM_HANDLE, "aTexCoords")
            UNIFORM_MVP_MATRIX_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uMVPMatrix")
            UNIFORM_TEXTURE_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uTexture")
            UNIFORM_ALPHA_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uAlpha")
            UNIFORM_DUOTONE_LIGHT_HANDLE =
                GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uDuotoneLightColor")
            UNIFORM_DUOTONE_DARK_HANDLE =
                GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uDuotoneDarkColor")
            UNIFORM_DUOTONE_OPACITY_HANDLE =
                GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uDuotoneOpacity")

            val maxTextureSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            TILE_SIZE = min(512, maxTextureSize[0])
            if (TILE_SIZE == 0) {
                TILE_SIZE = 512
            }
        }

        //language=c
        private const val VERTEX_SHADER_CODE = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoords;
            varying vec2 vTexCoords;
            
            void main() {
                vTexCoords = aTexCoords;
                gl_Position = uMVPMatrix * aPosition;
            }
        """

        //language=c
        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            uniform vec3 uDuotoneLightColor;
            uniform vec3 uDuotoneDarkColor;
            uniform float uDuotoneOpacity;
            varying vec2 vTexCoords;
            
            void main() {
                vec4 color = texture2D(uTexture, vTexCoords);
                float lum = 0.2126 * color.r + 0.7152 * color.g + 0.0722 * color.b;
                vec3 duotone = mix(uDuotoneDarkColor, uDuotoneLightColor, lum);
                vec3 finalColor = mix(color.rgb, duotone, uDuotoneOpacity);
                gl_FragColor = vec4(finalColor, color.a * uAlpha);
            }    
        """
    }

    private val vertices = FloatArray(COORDS_PER_VERTEX * VERTICES)
    private val vertexBuffer: FloatBuffer = GLUtil.newFloatBuffer(vertices.size)
    private val textureCoordsBuffer: FloatBuffer = GLUtil.asFloatBuffer(SQUARE_TEXTURE_VERTICES)

    private val textureHandles: IntArray
    private val numColumns: Int
    private val numRows: Int
    private val width = bitmap.width
    private val height = bitmap.height

    init {
        val tileSize = if (TILE_SIZE > 0) TILE_SIZE else bitmap.width.coerceAtLeast(bitmap.height)
        if (tileSize == 0 || width == 0 || height == 0) {
            numColumns = 0
            numRows = 0
            textureHandles = IntArray(0)
        } else {
            val leftoverHeight = height % tileSize
            numColumns = width.divideRoundUp(tileSize)
            numRows = height.divideRoundUp(tileSize)

            textureHandles = IntArray(numColumns * numRows)
            if (numColumns == 1 && numRows == 1) {
                textureHandles[0] = GLUtil.loadTexture(bitmap)
            } else {
                val rect = Rect()
                for (y in 0 until numRows) {
                    for (x in 0 until numColumns) {
                        rect.set(
                            x * tileSize,
                            (numRows - y - 1) * tileSize,
                            (x + 1) * tileSize,
                            (numRows - y) * tileSize
                        )
                        if (leftoverHeight > 0) {
                            rect.offset(0, -tileSize + leftoverHeight)
                        }
                        rect.intersect(0, 0, width, height)
                        val subBitmap = Bitmap.createBitmap(
                            bitmap,
                            rect.left,
                            rect.top,
                            rect.width(),
                            rect.height()
                        )
                        textureHandles[y * numColumns + x] = GLUtil.loadTexture(subBitmap)
                        subBitmap.recycle()
                    }
                }
            }
        }
    }

    fun draw(
        mvpMatrix: FloatArray, alpha: Float,
        duotone: Duotone = Duotone()
    ) {
        if (textureHandles.isEmpty()) {
            return
        }

        GLES20.glUseProgram(PROGRAM_HANDLE)

        GLES20.glUniformMatrix4fv(UNIFORM_MVP_MATRIX_HANDLE, 1, false, mvpMatrix, 0)
        GLUtil.checkGlError("glUniformMatrix4fv")

        GLES20.glEnableVertexAttribArray(ATTRIB_POSITION_HANDLE)
        GLES20.glEnableVertexAttribArray(ATTRIB_TEXTURE_COORDS_HANDLE)

        GLES20.glVertexAttribPointer(
            ATTRIB_TEXTURE_COORDS_HANDLE,
            COORDS_PER_TEXTURE_VERTEX,
            GLES20.GL_FLOAT,
            false,
            TEXTURE_VERTEX_STRIDE_BYTES,
            textureCoordsBuffer
        )

        GLES20.glUniform1f(UNIFORM_ALPHA_HANDLE, alpha)

        // Set duotone uniforms
        GLES20.glUniform3f(
            UNIFORM_DUOTONE_LIGHT_HANDLE,
            android.graphics.Color.red(duotone.lightColor) / 255f,
            android.graphics.Color.green(duotone.lightColor) / 255f,
            android.graphics.Color.blue(duotone.lightColor) / 255f
        )
        GLES20.glUniform3f(
            UNIFORM_DUOTONE_DARK_HANDLE,
            android.graphics.Color.red(duotone.darkColor) / 255f,
            android.graphics.Color.green(duotone.darkColor) / 255f,
            android.graphics.Color.blue(duotone.darkColor) / 255f
        )
        GLES20.glUniform1f(UNIFORM_DUOTONE_OPACITY_HANDLE, duotone.opacity)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(UNIFORM_TEXTURE_HANDLE, 0)

        var tileIndex = 0
        for (y in 0 until numRows) {
            for (x in 0 until numColumns) {
                vertices[9] = min(-1f + 2f * x * TILE_SIZE / width, 1f)
                vertices[3] = vertices[9]
                vertices[0] = vertices[3]
                vertices[16] = min(-1f + 2f * (y + 1) * TILE_SIZE / height, 1f)
                vertices[10] = vertices[16]
                vertices[1] = vertices[10]
                vertices[15] = min(-1f + 2f * (x + 1) * TILE_SIZE / width, 1f)
                vertices[12] = vertices[15]
                vertices[6] = vertices[12]
                vertices[13] = min(-1f + 2f * y * TILE_SIZE / height, 1f)
                vertices[7] = vertices[13]
                vertices[4] = vertices[7]

                vertexBuffer.put(vertices)
                vertexBuffer.position(0)

                GLES20.glVertexAttribPointer(
                    ATTRIB_POSITION_HANDLE,
                    COORDS_PER_VERTEX,
                    GLES20.GL_FLOAT,
                    false,
                    VERTEX_STRIDE_BYTES,
                    vertexBuffer
                )

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandles[tileIndex])
                GLUtil.checkGlError("glBindTexture")
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertices.size / COORDS_PER_VERTEX)
                tileIndex++
            }
        }

        GLES20.glDisableVertexAttribArray(ATTRIB_POSITION_HANDLE)
        GLES20.glDisableVertexAttribArray(ATTRIB_TEXTURE_COORDS_HANDLE)
    }

    fun destroy() {
        if (textureHandles.isNotEmpty()) {
            GLES20.glDeleteTextures(textureHandles.size, textureHandles, 0)
        }
    }
}

fun Int.divideRoundUp(divisor: Int): Int {
    if (this == 0) {
        return 0
    }
    return (this + divisor - 1) / divisor
}
