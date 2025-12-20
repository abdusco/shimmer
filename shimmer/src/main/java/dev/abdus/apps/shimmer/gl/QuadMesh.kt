package dev.abdus.apps.shimmer.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object QuadMesh {
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

    private val vertexBuffer: FloatBuffer = createBuffer(vertexData)
    private val texCoordBuffer: FloatBuffer = createBuffer(texCoordData)

    private fun createBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                flip()
            }
    }

    fun draw(positionAttrib: Int, texCoordAttrib: Int) {
        GLES30.glEnableVertexAttribArray(positionAttrib)
        GLES30.glEnableVertexAttribArray(texCoordAttrib)

        GLES30.glVertexAttribPointer(positionAttrib, 3, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glVertexAttribPointer(texCoordAttrib, 2, GLES30.GL_FLOAT, false, 0, texCoordBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }
}