package dev.abdus.apps.shimmer

import android.graphics.Color
import android.opengl.GLES20
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red

/**
 * Renders a solid color overlay using OpenGL ES 2.0.
 * Draws two triangles forming a quad that covers the entire screen.
 */
class GLColorOverlay {

    companion object {
        // Vertex coordinates: 3 floats per vertex (x, y, z)
        private const val COORDS_PER_VERTEX = 3

        // Number of vertices to draw (2 triangles × 3 vertices)
        private const val VERTEX_COUNT = 6

        // Stride in bytes between consecutive vertices (3 coords × 4 bytes per float)
        private const val VERTEX_STRIDE_BYTES = COORDS_PER_VERTEX * 4

        // Color normalization factor (convert 0-255 to 0.0-1.0)
        private const val COLOR_NORMALIZE = 255f
    }
    
    /**
     * Vertex buffer containing two triangles that cover the entire screen in normalized device coordinates.
     * Each vertex has 3 coordinates (x, y, z).
     */
    private val vertexBuffer = GLUtil.asFloatBuffer(
        floatArrayOf(
            -1f, 1f, 0f,    // top left
            -1f, -1f, 0f,   // bottom left
            1f, -1f, 0f,    // bottom right
            -1f, 1f, 0f,    // top left
            1f, -1f, 0f,    // bottom right
            1f, 1f, 0f      // top right
        )
    )

    /** The color to render (ARGB format) */
    var color: Int = Color.TRANSPARENT

    /**
     * Draws the color overlay.
     * @param mvpMatrix Model-View-Projection matrix for transforming vertices
     */
    fun draw(handles: ColorOverlayHandles, mvpMatrix: FloatArray) {
        // Activate shader program
        GLES20.glUseProgram(handles.program)
        GLES20.glEnableVertexAttribArray(handles.attribPosition)

        // Set vertex positions
        GLES20.glVertexAttribPointer(
            handles.attribPosition,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer
        )

        // Set transformation matrix
        GLES20.glUniformMatrix4fv(handles.uniformMvpMatrix, 1, false, mvpMatrix, 0)

        // Set color (normalize from 0-255 to 0.0-1.0)
        GLES20.glUniform4f(
            handles.uniformColor,
            color.red / COLOR_NORMALIZE,
            color.green / COLOR_NORMALIZE,
            color.blue / COLOR_NORMALIZE,
            color.alpha / COLOR_NORMALIZE
        )

        // Draw the triangles
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(handles.attribPosition)
    }
}

