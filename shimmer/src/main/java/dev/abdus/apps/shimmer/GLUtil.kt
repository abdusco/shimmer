package dev.abdus.apps.shimmer

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Utility functions for OpenGL ES operations. */
object GLUtil {
    private const val TAG = "GLUtil"
    /** Bytes per float value */
    const val BYTES_PER_FLOAT = 4

    /**
     * Compiles a shader from source code.
     * @param type Shader type (GL_VERTEX_SHADER or GL_FRAGMENT_SHADER)
     * @param shaderCode GLSL source code
     * @return Compiled shader handle
     * @throws RuntimeException if shader compilation fails
     */
    fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            throw RuntimeException("Failed to create shader (glCreateShader returned 0)")
        }

        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            val shaderTypeName =
                    when (type) {
                        GLES30.GL_VERTEX_SHADER -> "vertex"
                        GLES30.GL_FRAGMENT_SHADER -> "fragment"
                        else -> "unknown"
                    }
            val errorMsg = "Shader compilation failed ($shaderTypeName shader):\n$log"
            Log.e(TAG, errorMsg)
            GLES30.glDeleteShader(shader)
            throw RuntimeException(errorMsg)
        }
        return shader
    }

    /**
     * Creates and links a shader program.
     * @param vertexShader Compiled vertex shader handle
     * @param fragmentShader Compiled fragment shader handle
     * @param attributes Optional array of attribute names to bind to specific locations
     * @return Linked program handle
     */
    fun createAndLinkProgram(
            vertexShader: Int,
            fragmentShader: Int,
            attributes: Array<String>? = null,
    ): Int {
        val program = GLES30.glCreateProgram()
        checkGlError("glCreateProgram")

        GLES30.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader(vertex)")
        GLES30.glAttachShader(program, fragmentShader)
        checkGlError("glAttachShader(fragment)")

        attributes?.forEachIndexed { index, name ->
            GLES30.glBindAttribLocation(program, index, name)
        }

        GLES30.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            val errorMsg = "Program linking failed:\n$log"
            Log.e(TAG, errorMsg)
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
            throw RuntimeException(errorMsg)
        }

        // Clean up shaders (program retains compiled code)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        return program
    }

    /**
     * Loads a bitmap as an OpenGL texture with linear filtering and clamped edges.
     * @param bitmap The bitmap to upload
     * @return OpenGL texture handle
     * @throws IllegalStateException if texture creation fails or no GL context is available
     */
    fun loadTexture(bitmap: Bitmap): Int {
        val textureHandle = IntArray(1)
        GLES30.glGenTextures(1, textureHandle, 0)
        checkGlError("glGenTextures")

        if (textureHandle[0] != 0) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandle[0])

            // Set texture parameters
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_WRAP_S,
                    GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_WRAP_T,
                    GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_MIN_FILTER,
                    GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                    GLES30.GL_TEXTURE_2D,
                    GLES30.GL_TEXTURE_MAG_FILTER,
                    GLES30.GL_LINEAR
            )

            // Upload bitmap data
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            checkGlError("texImage2D")
        }

        if (textureHandle[0] == 0) {
            error("Error loading texture.")
        }

        return textureHandle[0]
    }

    /**
     * Converts a float array to a native-order FloatBuffer.
     * @param array Float array to convert
     * @return FloatBuffer ready for use with OpenGL
     */
    fun asFloatBuffer(array: FloatArray): FloatBuffer {
        return newFloatBuffer(array.size).apply {
            put(array)
            position(0)
        }
    }

    /**
     * Creates a new native-order FloatBuffer with the specified size.
     * @param size Number of float elements
     * @return Allocated FloatBuffer
     */
    fun newFloatBuffer(size: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(size * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { position(0) }
    }

    /**
     * Checks for OpenGL errors and throws an exception if one occurred.
     * @param op Description of the operation being checked
     * @throws RuntimeException if a GL error is detected
     */
    fun checkGlError(op: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }
}
