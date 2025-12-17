package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.graphics.Rect
import android.opengl.GLES30
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Manages a set of images with progressive blur levels for smooth blur animation.
 *
 * The picture set expects bitmaps with progressive blur levels:
 * - Index 0: Original image
 * - Index 1..N: Progressively blurred versions
 *
 * Example with 3 bitmaps:
 * - pictures[0] = original
 * - pictures[1] = 50% blurred
 * - pictures[2] = 100% blurred
 */
class GLPictureSet {

    private var pictures = arrayOfNulls<GLPicture>(0)

    /**
     * Number of blur keyframes (total pictures - 1).
     * For example, 3 pictures means 2 blur keyframes.
     */
    private val blurKeyframes: Int
        get() = (pictures.size - 1).coerceAtLeast(0)

    /**
     * Loads bitmaps into the picture set.
     * @param bitmaps List of bitmaps with progressive blur levels (first = original, rest = increasingly blurred)
     */
    fun load(bitmaps: List<Bitmap?>, tileSize: Int) {
        pictures = arrayOfNulls(bitmaps.size)
        for (index in pictures.indices) {
            pictures[index] = bitmaps.getOrNull(index)?.let { GLPicture(it, tileSize) }
        }
    }

    /**
     * Draws a frame with interpolated blur level.
     * @param mvpMatrix Model-view-projection matrix
     * @param blurPercent Normalized blur amount (0.0 = no blur, 1.0 = full blur)
     * @param imageAlpha Overall opacity (0.0 = transparent, 1.0 = opaque)
     * @param duotone Duotone color effect to apply
     */
    fun drawFrame(
        handles: PictureHandles,
        tileSize: Int,
        mvpMatrix: FloatArray,
        blurPercent: Float,
        imageAlpha: Float = 1f,
        duotone: Duotone = Duotone(),
        dimAmount: Float = 0f,
        grainAmount: Float = 0f,
        grainCountX: Float = 0f,
        grainCountY: Float = 0f,
        touchPointCount: Int = 0,
        touchPoints: FloatArray = FloatArray(0),
        touchIntensities: FloatArray = FloatArray(0),
        screenSize: FloatArray = FloatArray(2),
    ) {
        if (pictures.all { it == null }) {
            return
        }


        val blurFrame = blurPercent * blurKeyframes
        // Clamp and determine which two images to interpolate between
        val clampedBlur = blurFrame.coerceIn(0f, blurKeyframes.toFloat())
        val lo = floor(clampedBlur.toDouble()).toInt().coerceAtMost(blurKeyframes)
        val hi = ceil(clampedBlur.toDouble()).toInt().coerceAtMost(blurKeyframes)

        when {
            imageAlpha <= 0f -> return

            lo == hi -> {
                // Single blur level - just draw it normally
                pictures[lo]?.draw(
                    handles,
                    tileSize,
                    mvpMatrix,
                    imageAlpha,
                    duotone,
                    dimAmount,
                    grainAmount,
                    grainCountX,
                    grainCountY,
                    enableBlending = true,
                    touchPointCount = touchPointCount,
                    touchPoints = touchPoints,
                    touchIntensities = touchIntensities,
                    screenSize = screenSize,
                )
            }

            else -> {
                val loAlpha = imageAlpha * blurPercent
                val hiAlpha = clampedBlur - lo

                // Draw first blur level without blending (replaces background)
                // Then draw second blur level with blending (crossfades on top)
                pictures[lo]?.draw(
                    handles,
                    tileSize,
                    mvpMatrix,
                    loAlpha,
                    duotone,
                    dimAmount,
                    grainAmount,
                    grainCountX,
                    grainCountY,
                    enableBlending = false,
                    touchPointCount = touchPointCount,
                    touchPoints = touchPoints,
                    touchIntensities = touchIntensities,
                    screenSize = screenSize,
                )
                pictures[hi]?.draw(
                    handles,
                    tileSize,
                    mvpMatrix,
                    hiAlpha,
                    duotone,
                    dimAmount,
                    grainAmount,
                    grainCountX,
                    grainCountY,
                    enableBlending = true,
                    touchPointCount = touchPointCount,
                    touchPoints = touchPoints,
                    touchIntensities = touchIntensities,
                    screenSize = screenSize,
                )
            }
        }
    }

    fun release() {
        pictures.forEach { it?.destroy() }
        pictures = arrayOfNulls(0)
    }
}


class GLPicture(bitmap: Bitmap, tileSize: Int) {

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
    }

    private val vertices = FloatArray(COORDS_PER_VERTEX * VERTICES)
    private val vertexBuffer: FloatBuffer = GLUtil.newFloatBuffer(vertices.size)
    private val textureCoordsBuffer: FloatBuffer = GLUtil.asFloatBuffer(SQUARE_TEXTURE_VERTICES)

    private var textureHandles: IntArray
    private val numColumns: Int
    private val numRows: Int
    private val width = bitmap.width
    private val height = bitmap.height

    init {
        val effectiveTileSize = if (tileSize > 0) tileSize else bitmap.width.coerceAtLeast(bitmap.height)
        if (effectiveTileSize == 0 || width == 0 || height == 0) {
            numColumns = 0
            numRows = 0
            textureHandles = IntArray(0)
        } else {
            val leftoverHeight = height % effectiveTileSize
            numColumns = width.divideRoundUp(effectiveTileSize)
            numRows = height.divideRoundUp(effectiveTileSize)

            textureHandles = IntArray(numColumns * numRows)
            if (numColumns == 1 && numRows == 1) {
                textureHandles[0] = GLUtil.loadTexture(bitmap)
            } else {
                val rect = Rect()
                for (y in 0 until numRows) {
                    for (x in 0 until numColumns) {
                        rect.set(
                            x * effectiveTileSize,
                            (numRows - y - 1) * effectiveTileSize,
                            (x + 1) * effectiveTileSize,
                            (numRows - y) * effectiveTileSize
                        )
                        if (leftoverHeight > 0) {
                            rect.offset(0, -effectiveTileSize + leftoverHeight)
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
        handles: PictureHandles,
        tileSize: Int,
        mvpMatrix: FloatArray, alpha: Float,
        duotone: Duotone = Duotone(),
        dimAmount: Float = 0f,
        grainAmount: Float = 0f,
        grainCountX: Float = 0f,
        grainCountY: Float = 0f,
        enableBlending: Boolean = true,
        touchPointCount: Int = 0,
        touchPoints: FloatArray = FloatArray(0),
        touchIntensities: FloatArray = FloatArray(0),
        screenSize: FloatArray = FloatArray(2),
    ) {
        if (textureHandles.isEmpty()) {
            android.util.Log.w("GLPicture", "draw: No texture handles available.")
            return
        }

        // Control blending state
        if (enableBlending) {
            GLES30.glEnable(GLES30.GL_BLEND)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        GLES30.glUseProgram(handles.program)

        GLES30.glUniformMatrix4fv(handles.uniformMvpMatrix, 1, false, mvpMatrix, 0)
        GLUtil.checkGlError("glUniformMatrix4fv")

        GLES30.glEnableVertexAttribArray(handles.attribPosition)
        GLES30.glEnableVertexAttribArray(handles.attribTexCoords)

        GLES30.glVertexAttribPointer(
            handles.attribTexCoords,
            COORDS_PER_TEXTURE_VERTEX,
            GLES30.GL_FLOAT,
            false,
            TEXTURE_VERTEX_STRIDE_BYTES,
            textureCoordsBuffer
        )

        GLES30.glUniform1f(handles.uniformAlpha, alpha)

        // Set duotone uniforms
        GLES30.glUniform3f(
            handles.uniformDuotoneLight,
            android.graphics.Color.red(duotone.lightColor) / 255f,
            android.graphics.Color.green(duotone.lightColor) / 255f,
            android.graphics.Color.blue(duotone.lightColor) / 255f
        )
        GLES30.glUniform3f(
            handles.uniformDuotoneDark,
            android.graphics.Color.red(duotone.darkColor) / 255f,
            android.graphics.Color.green(duotone.darkColor) / 255f,
            android.graphics.Color.blue(duotone.darkColor) / 255f
        )
        GLES30.glUniform1f(handles.uniformDuotoneOpacity, duotone.opacity)
        GLES30.glUniform1i(handles.uniformDuotoneBlendMode, duotone.blendMode.ordinal)
        GLES30.glUniform1f(handles.uniformDimAmount, dimAmount)
        GLES30.glUniform1f(handles.uniformGrainAmount, grainAmount)
        GLES30.glUniform2f(handles.uniformGrainCount, grainCountX, grainCountY)

        // Set touch point uniforms for chromatic aberration
        GLES30.glUniform1i(handles.uniformTouchPointCount, touchPointCount)
        if (touchPointCount > 0 && touchPoints.size >= touchPointCount * 3) {
            GLES30.glUniform3fv(handles.uniformTouchPoints, touchPointCount, touchPoints, 0)
        }
        if (touchPointCount > 0 && touchIntensities.size >= touchPointCount) {
            GLES30.glUniform1fv(handles.uniformTouchIntensities, touchPointCount, touchIntensities, 0)
        }
        GLES30.glUniform2f(handles.uniformScreenSize, screenSize[0], screenSize[1])

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(handles.uniformTexture, 0)

        var tileIndex = 0
        for (y in 0 until numRows) {
            for (x in 0 until numColumns) {
                vertices[9] = min(-1f + 2f * x * tileSize / width, 1f)
                vertices[3] = vertices[9]
                vertices[0] = vertices[3]
                vertices[16] = min(-1f + 2f * (y + 1) * tileSize / height, 1f)
                vertices[10] = vertices[16]
                vertices[1] = vertices[10]
                vertices[15] = min(-1f + 2f * (x + 1) * tileSize / width, 1f)
                vertices[12] = vertices[15]
                vertices[6] = vertices[12]
                vertices[13] = min(-1f + 2f * y * tileSize / height, 1f)
                vertices[7] = vertices[13]
                vertices[4] = vertices[7]

                vertexBuffer.put(vertices)
                vertexBuffer.position(0)

                GLES30.glVertexAttribPointer(
                    handles.attribPosition,
                    COORDS_PER_VERTEX,
                    GLES30.GL_FLOAT,
                    false,
                    VERTEX_STRIDE_BYTES,
                    vertexBuffer
                )

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandles[tileIndex])
                GLUtil.checkGlError("glBindTexture")
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertices.size / COORDS_PER_VERTEX)
                tileIndex++
            }
        }

        GLES30.glDisableVertexAttribArray(handles.attribPosition)
        GLES30.glDisableVertexAttribArray(handles.attribTexCoords)
    }

    fun destroy() {
        if (textureHandles.isNotEmpty()) {
            GLES30.glDeleteTextures(textureHandles.size, textureHandles, 0)
        }
    }

    fun release() {
        if (textureHandles.isNotEmpty()) {
            GLES30.glDeleteTextures(textureHandles.size, textureHandles, 0)
            textureHandles = IntArray(0)
        }
    }
}

fun Int.divideRoundUp(divisor: Int): Int {
    if (this == 0) {
        return 0
    }
    return (this + divisor - 1) / divisor
}


