package dev.abdus.apps.shimmer.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The fullscreen quad every image is drawn with. Positions and texture coordinates live in
 * one static VBO wrapped in a VAO, so drawing is a bind plus a draw call rather than handing
 * the driver client-side vertex arrays to re-validate and copy on every frame.
 *
 * Holds GL object names, so each EGL context needs its own instance.
 */
class QuadMesh {
    private val vertexData = floatArrayOf(
        -1f, 1f, 0f,   // Top-left
        -1f, -1f, 0f,  // Bottom-left
        1f, -1f, 0f,   // Bottom-right
        -1f, 1f, 0f,   // Top-left
        1f, -1f, 0f,   // Bottom-right
        1f, 1f, 0f     // Top-right
    )

    private val texCoordData = floatArrayOf(
        0f, 0f,  0f, 1f,  1f, 1f,
        0f, 0f,  1f, 1f,  1f, 0f
    )

    private var vao = 0
    private var vbo = 0

    companion object {
        private const val VERTEX_COUNT = 6
        private const val POSITION_BYTES = VERTEX_COUNT * 3 * 4
        private const val TEX_COORD_BYTES = VERTEX_COUNT * 2 * 4
    }

    /** Must be called with the program linked; the VAO records the given attribute locations. */
    fun init(positionAttrib: Int, texCoordAttrib: Int) {
        release()

        val data = ByteBuffer.allocateDirect(POSITION_BYTES + TEX_COORD_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
            .put(texCoordData)
        data.position(0)

        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, POSITION_BYTES + TEX_COORD_BYTES, data, GLES30.GL_STATIC_DRAW
        )

        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glEnableVertexAttribArray(positionAttrib)
        GLES30.glVertexAttribPointer(positionAttrib, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glEnableVertexAttribArray(texCoordAttrib)
        GLES30.glVertexAttribPointer(texCoordAttrib, 2, GLES30.GL_FLOAT, false, 0, POSITION_BYTES)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw() {
        if (vao == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, VERTEX_COUNT)
    }

    fun release() {
        val ids = IntArray(1)
        if (vao != 0) {
            ids[0] = vao
            GLES30.glDeleteVertexArrays(1, ids, 0)
            vao = 0
        }
        if (vbo != 0) {
            ids[0] = vbo
            GLES30.glDeleteBuffers(1, ids, 0)
            vbo = 0
        }
    }
}
