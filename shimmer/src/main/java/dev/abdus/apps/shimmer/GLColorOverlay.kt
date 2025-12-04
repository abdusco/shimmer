package dev.abdus.apps.shimmer

import android.graphics.Color
import android.opengl.GLES20
import java.nio.FloatBuffer

class GLColorOverlay {

    companion object {
        // language=c
        private const val VERTEX_SHADER_CODE = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            
            void main(){
                gl_Position = uMVPMatrix * aPosition;
            }
        """

        // language=c
        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform vec4 uColor;
            
            void main(){
                gl_FragColor = uColor;
            }
        """

        private const val COORDS_PER_VERTEX = 3
        private const val VERTEX_STRIDE_BYTES = COORDS_PER_VERTEX * GLUtil.BYTES_PER_FLOAT

        private var PROGRAM_HANDLE: Int = 0
        private var ATTRIB_POSITION_HANDLE: Int = 0
        private var UNIFORM_COLOR_HANDLE: Int = 0
        private var UNIFORM_MVP_MATRIX_HANDLE: Int = 0

        fun initGl() {
            val vertexShaderHandle = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
            val fragShaderHandle =
                GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

            PROGRAM_HANDLE = GLUtil.createAndLinkProgram(vertexShaderHandle, fragShaderHandle, null)
            ATTRIB_POSITION_HANDLE = GLES20.glGetAttribLocation(PROGRAM_HANDLE, "aPosition")
            UNIFORM_MVP_MATRIX_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uMVPMatrix")
            UNIFORM_COLOR_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uColor")
        }
    }

    private val vertices = floatArrayOf(
        -1f, 1f, 0f,
        -1f, -1f, 0f,
        1f, -1f, 0f,

        -1f, 1f, 0f,
        1f, -1f, 0f,
        1f, 1f, 0f
    )

    private val vertexBuffer: FloatBuffer = GLUtil.asFloatBuffer(vertices)
    var color: Int = 0

    fun draw(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(PROGRAM_HANDLE)

        GLES20.glEnableVertexAttribArray(ATTRIB_POSITION_HANDLE)
        GLES20.glVertexAttribPointer(
            ATTRIB_POSITION_HANDLE,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer
        )

        GLES20.glUniformMatrix4fv(UNIFORM_MVP_MATRIX_HANDLE, 1, false, mvpMatrix, 0)
        GLUtil.checkGlError("glUniformMatrix4fv")

        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val a = Color.alpha(color) / 255f
        GLES20.glUniform4f(UNIFORM_COLOR_HANDLE, r, g, b, a)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertices.size / COORDS_PER_VERTEX)
        GLES20.glDisableVertexAttribArray(ATTRIB_POSITION_HANDLE)
    }
}
