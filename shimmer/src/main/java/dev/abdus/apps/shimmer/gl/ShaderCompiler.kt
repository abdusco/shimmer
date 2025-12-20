package dev.abdus.apps.shimmer.gl

import android.opengl.GLES30

object ShaderCompiler {
    private const val TAG = "ShaderCompiler"

    fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        require(shader != 0) { "glCreateShader failed" }

        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            val typeName = when (type) {
                GLES30.GL_VERTEX_SHADER -> "vertex"
                GLES30.GL_FRAGMENT_SHADER -> "fragment"
                else -> "unknown"
            }
            GLES30.glDeleteShader(shader)
            error("$typeName shader compilation failed:\n$log")
        }
        return shader
    }

    fun linkProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Program linking failed:\n$log")
        }

        // Shaders no longer needed after linking
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        return program
    }
}